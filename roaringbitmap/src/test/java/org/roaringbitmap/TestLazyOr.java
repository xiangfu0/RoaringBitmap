package org.roaringbitmap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * Tests for the protected {@link RoaringBitmap#lazyor(RoaringBitmap)} path, in particular the
 * bulk insertion of source-only keys (the same {@code mergeBulk} pass that {@code or} and {@code
 * naivelazyor} use) and the container types it leaves behind before {@link
 * RoaringBitmap#repairAfterLazy()}.
 */
@Execution(ExecutionMode.CONCURRENT)
public class TestLazyOr {

  /** Mirror of the private {@code ArrayContainer.ARRAY_LAZY_LOWERBOUND}. */
  private static final int ARRAY_LAZY_LOWERBOUND = 1024;

  /** Container shapes used on either side of a union; {@code offset} shifts the values. */
  enum Kind {
    /** 300 values spaced by three. */
    ARRAY,
    /** A single value. */
    ARRAY1,
    /** 6000 alternating values: a bitmap container that run optimization would not touch. */
    BITMAP,
    /** One run of 10000 values. */
    RUN;

    Container build(int offset) {
      Container c;
      switch (this) {
        case ARRAY:
          c = new ArrayContainer();
          for (int i = 0; i < 300; i++) {
            c = c.add((char) (7 + 3 * i + offset));
          }
          assertInstanceOf(ArrayContainer.class, c);
          return c;
        case ARRAY1:
          c = new ArrayContainer().add((char) (1000 + offset));
          assertInstanceOf(ArrayContainer.class, c);
          return c;
        case BITMAP:
          c = new BitmapContainer();
          for (int i = 0; i < 6000; i++) {
            c = c.add((char) (2 * i + offset));
          }
          assertInstanceOf(BitmapContainer.class, c);
          return c;
        case RUN:
        default:
          c = new RunContainer().iadd(100 + 5000 * offset, 10100 + 5000 * offset);
          assertInstanceOf(RunContainer.class, c);
          return c;
      }
    }
  }

  private static int value(int key, int low) {
    return (key << 16) | low;
  }

  /** Appends one container of the given kind per key; keys must be increasing (as chars). */
  private static RoaringBitmap bitmapOf(int offset, int[] keys, Kind... kinds) {
    RoaringBitmap b = new RoaringBitmap();
    for (int i = 0; i < keys.length; i++) {
      Kind kind = kinds[Math.min(i, kinds.length - 1)];
      b.highLowContainer.append((char) keys[i], kind.build(offset));
    }
    assertTrue(b.validate());
    return b;
  }

  /** A bitmap with a small array container (a few spaced values) at each key. */
  private static RoaringBitmap smallArrays(int[] keys, int low) {
    RoaringBitmap b = new RoaringBitmap();
    for (int key : keys) {
      for (int i = 0; i < 5; i++) {
        b.add(value(key, low + 4 * i));
      }
    }
    return b;
  }

  private static RoaringBitmap arrayAt(int key, int firstLow, int cardinality) {
    RoaringBitmap b = new RoaringBitmap();
    for (int i = 0; i < cardinality; i++) {
      b.add(value(key, firstLow + i));
    }
    return b;
  }

  private static int[] range(int from, int toExclusive, int step) {
    List<Integer> keys = new ArrayList<>();
    for (int k = from; k < toExclusive; k += step) {
      keys.add(k);
    }
    int[] result = new int[keys.size()];
    for (int i = 0; i < result.length; i++) {
      result[i] = keys.get(i);
    }
    return result;
  }

  private static RoaringBitmap roundTrip(RoaringBitmap b) throws IOException {
    ByteArrayOutputStream bos = new ByteArrayOutputStream();
    b.serialize(new DataOutputStream(bos));
    RoaringBitmap copy = new RoaringBitmap();
    copy.deserialize(new DataInputStream(new ByteArrayInputStream(bos.toByteArray())));
    assertTrue(copy.validate());
    return copy;
  }

  private static Container containerAt(RoaringBitmap b, int key) {
    int index = b.highLowContainer.getIndex((char) key);
    assertTrue(index >= 0, "key " + key + " missing");
    return b.highLowContainer.getContainerAtIndex(index);
  }

  /**
   * Asserts that no container of {@code lazy} is the same instance as a container of {@code
   * source}: {@code lazyor} must copy source-only containers (also on the bulk path) and {@code
   * lazyIOR} never returns its argument.
   */
  private static void assertNoSharedContainers(RoaringBitmap lazy, RoaringBitmap source) {
    for (int i = 0; i < lazy.highLowContainer.size(); i++) {
      Container c = lazy.highLowContainer.getContainerAtIndex(i);
      for (int j = 0; j < source.highLowContainer.size(); j++) {
        assertNotSame(
            source.highLowContainer.getContainerAtIndex(j),
            c,
            "receiver key "
                + (int) lazy.highLowContainer.getKeyAtIndex(i)
                + " shares its container with source key "
                + (int) source.highLowContainer.getKeyAtIndex(j));
      }
    }
  }

  /** Adds a value to and removes a value from every container of {@code b}, in place. */
  private static void mutateEveryContainer(RoaringBitmap b) {
    for (int i = 0; i < b.highLowContainer.size(); i++) {
      int key = b.highLowContainer.getKeyAtIndex(i);
      int present = value(key, b.highLowContainer.getContainerAtIndex(i).first());
      b.add(value(key, 60000));
      b.remove(present);
    }
  }

  /**
   * Asserts that {@code lazyor} followed by {@code repairAfterLazy} equals the eager union and the
   * {@link FastAggregation} union, validates, survives a serialize round trip and leaves the source
   * untouched. Also asserts that the receiver never holds a container instance of the source: by
   * identity before repair, and by mutating every container of the repaired result afterwards.
   * Neither argument is modified. Returns the repaired lazy result.
   */
  private static RoaringBitmap assertLazyUnion(RoaringBitmap receiver, RoaringBitmap source)
      throws IOException {
    RoaringBitmap sourceSnapshot = source.clone();
    RoaringBitmap eager = receiver.clone();
    eager.or(source);
    RoaringBitmap fast = FastAggregation.or(receiver.clone(), source);

    RoaringBitmap lazy = receiver.clone();
    lazy.lazyor(source);
    assertNoSharedContainers(lazy, source);
    lazy.repairAfterLazy();

    assertTrue(lazy.validate());
    assertEquals(eager, lazy);
    assertEquals(fast, lazy);
    assertEquals(eager.getCardinality(), lazy.getCardinality());
    assertEquals(eager.highLowContainer.size(), lazy.highLowContainer.size());
    assertEquals(lazy, roundTrip(lazy));
    assertEquals(sourceSnapshot, source);
    assertTrue(source.validate());

    // mutating the result must not leak into the source: source-only containers were copied
    RoaringBitmap result = lazy.clone();
    mutateEveryContainer(lazy);
    assertEquals(sourceSnapshot, source);
    assertTrue(source.validate());
    return result;
  }

  @Test
  public void interleavedKeys() throws IOException {
    // receiver has the even keys, the source the odd ones: one source-only key between every pair
    // of receiver keys, the case that was quadratic with a per-key insert
    RoaringBitmap receiver = smallArrays(range(0, 400, 2), 5);
    RoaringBitmap source = smallArrays(range(1, 400, 2), 7);
    RoaringBitmap result = assertLazyUnion(receiver, source);
    assertEquals(400, result.highLowContainer.size());
    // and the mirror image, where the first source-only key comes after the first receiver key
    assertLazyUnion(source, receiver);
  }

  @Test
  public void leadingSourceOnlyKeys() throws IOException {
    RoaringBitmap receiver = smallArrays(range(10, 20, 1), 5);
    RoaringBitmap source = smallArrays(new int[] {0, 1, 2, 3, 4, 12, 15}, 7);
    assertLazyUnion(receiver, source);
    // source-only keys only, all before the receiver's keys
    assertLazyUnion(receiver, smallArrays(range(0, 5, 1), 7));
  }

  @Test
  public void trailingSourceOnlyKeys() throws IOException {
    // shared keys first, then source-only keys: the appendCopy tail path
    RoaringBitmap receiver = smallArrays(range(0, 10, 1), 5);
    assertLazyUnion(receiver, smallArrays(new int[] {3, 7, 20, 21, 22, 23}, 7));
    // pure append: every source key follows every receiver key
    assertLazyUnion(receiver, smallArrays(range(10, 20, 1), 7));
    // interior source-only key, then a tail of source-only keys: one bulk pass handles both
    assertLazyUnion(smallArrays(new int[] {0, 2, 4}, 5), smallArrays(new int[] {1, 3, 5, 6, 7}, 7));
    // receiver-only tail after the bulk pass started
    assertLazyUnion(smallArrays(new int[] {0, 2, 4, 6, 8}, 5), smallArrays(new int[] {1, 3}, 7));
  }

  @Test
  public void unsignedHighKeys() throws IOException {
    RoaringBitmap receiver = smallArrays(new int[] {1, 0x8000, 0xFFFE}, 5);
    RoaringBitmap source = smallArrays(new int[] {0x7FFF, 0x8001, 0xFFFF}, 7);
    RoaringBitmap result = assertLazyUnion(receiver, source);
    assertEquals(6, result.highLowContainer.size());
    assertTrue(result.contains(value(0xFFFF, 7)));
    assertTrue(result.contains(value(0x8000, 5)));
    assertLazyUnion(source, receiver);
    // shared high key plus a source-only key at the very end of the key space
    assertLazyUnion(
        smallArrays(new int[] {0x7FFF, 0x8000}, 5), smallArrays(new int[] {0x8000, 0xFFFF}, 7));
  }

  static Stream<Arguments> kindPairs() {
    List<Arguments> args = new ArrayList<>();
    for (Kind receiver : Kind.values()) {
      for (Kind source : Kind.values()) {
        args.add(Arguments.of(receiver, source));
      }
    }
    return args.stream();
  }

  @ParameterizedTest
  @MethodSource("kindPairs")
  public void containerKinds(Kind receiverKind, Kind sourceKind) throws IOException {
    // source-only key 3 precedes the shared keys 5 and 9, so both shared keys merge inside the
    // bulk pass; key 7 is source-only in the interior of that pass
    RoaringBitmap receiver = bitmapOf(0, new int[] {5, 9}, receiverKind);
    RoaringBitmap source = bitmapOf(1, new int[] {3, 5, 7, 9}, Kind.ARRAY, sourceKind);
    assertLazyUnion(receiver, source);
    assertLazyUnion(source, receiver);

    // shared key first (merged by the per-key loop), then a source-only key (bulk pass)
    RoaringBitmap receiver2 = bitmapOf(0, new int[] {1, 4}, receiverKind);
    RoaringBitmap source2 = bitmapOf(1, new int[] {1, 2}, sourceKind);
    assertLazyUnion(receiver2, source2);

    // identical content on both sides
    assertLazyUnion(receiver, bitmapOf(0, new int[] {3, 5, 9}, Kind.ARRAY1, receiverKind));
  }

  @Test
  public void severalLazyOrsBeforeOneRepair() throws IOException {
    Random random = new Random(20260922);
    Kind[] kinds = Kind.values();
    List<RoaringBitmap> inputs = new ArrayList<>();
    for (int n = 0; n < 8; n++) {
      TreeSet<Integer> keys = new TreeSet<>();
      while (keys.size() < 30) {
        keys.add(random.nextInt(80));
      }
      keys.add(0x8000 + random.nextInt(3));
      keys.add(0xFFFD + random.nextInt(3));
      RoaringBitmap b = new RoaringBitmap();
      for (int key : keys) {
        b.highLowContainer.append(
            (char) key, kinds[random.nextInt(kinds.length)].build(random.nextInt(3)));
      }
      assertTrue(b.validate());
      inputs.add(b);
    }
    List<RoaringBitmap> snapshots = new ArrayList<>();
    for (RoaringBitmap b : inputs) {
      snapshots.add(b.clone());
    }

    RoaringBitmap lazy = inputs.get(0).clone();
    RoaringBitmap eager = inputs.get(0).clone();
    for (int i = 1; i < inputs.size(); i++) {
      lazy.lazyor(inputs.get(i));
      eager.or(inputs.get(i));
    }
    for (RoaringBitmap input : inputs) {
      assertNoSharedContainers(lazy, input);
    }
    lazy.repairAfterLazy();
    assertTrue(lazy.validate());
    assertEquals(eager, lazy);
    assertEquals(FastAggregation.or(inputs.toArray(new RoaringBitmap[0])), lazy);
    assertEquals(lazy, roundTrip(lazy));
    for (int i = 0; i < inputs.size(); i++) {
      assertEquals(snapshots.get(i), inputs.get(i));
    }
    // mutating the result must not leak into any input
    mutateEveryContainer(lazy);
    for (int i = 0; i < inputs.size(); i++) {
      assertEquals(snapshots.get(i), inputs.get(i));
      assertTrue(inputs.get(i).validate());
    }
  }

  @Test
  public void selfUnionIsNoOp() {
    RoaringBitmap b = bitmapOf(0, new int[] {1, 2, 3, 4}, Kind.ARRAY, Kind.BITMAP, Kind.RUN);
    RoaringBitmap snapshot = b.clone();
    b.lazyor(b);
    assertEquals(snapshot, b);
    assertTrue(b.validate());
    b.repairAfterLazy();
    assertEquals(snapshot, b);
  }

  @Test
  public void emptyReceiver() throws IOException {
    RoaringBitmap source = bitmapOf(0, new int[] {0, 7, 0x8000}, Kind.ARRAY, Kind.RUN, Kind.BITMAP);
    RoaringBitmap result = assertLazyUnion(new RoaringBitmap(), source);
    assertEquals(source, result);
    // the receiver holds copies, not the source's containers
    RoaringBitmap lazy = new RoaringBitmap();
    lazy.lazyor(source);
    lazy.repairAfterLazy();
    for (int i = 0; i < lazy.highLowContainer.size(); i++) {
      assertNotSame(
          source.highLowContainer.getContainerAtIndex(i),
          lazy.highLowContainer.getContainerAtIndex(i));
    }
    lazy.add(value(7, 20000));
    assertEquals(bitmapOf(0, new int[] {0, 7, 0x8000}, Kind.ARRAY, Kind.RUN, Kind.BITMAP), source);
  }

  @Test
  public void emptyInput() throws IOException {
    RoaringBitmap receiver =
        bitmapOf(0, new int[] {0, 7, 0x8000}, Kind.ARRAY, Kind.RUN, Kind.BITMAP);
    assertEquals(receiver, assertLazyUnion(receiver, new RoaringBitmap()));
    assertEquals(new RoaringBitmap(), assertLazyUnion(new RoaringBitmap(), new RoaringBitmap()));
  }

  @Test
  public void arrayStaysArrayThroughBulkPath() throws IOException {
    // key 1 is source-only, so key 2 is merged by the bulk pass; 500 + 500 <= 1024 stays an array
    RoaringBitmap receiver = arrayAt(0, 0, 500);
    receiver.or(arrayAt(2, 0, 500));
    RoaringBitmap source = arrayAt(1, 0, 1);
    source.or(arrayAt(2, 500, 500));

    RoaringBitmap lazy = receiver.clone();
    lazy.lazyor(source);
    Container merged = containerAt(lazy, 2);
    assertInstanceOf(ArrayContainer.class, merged);
    assertEquals(1000, merged.getCardinality());
    assertTrue(lazy.validate());
    lazy.repairAfterLazy();
    assertInstanceOf(ArrayContainer.class, containerAt(lazy, 2));
    assertEquals(assertLazyUnion(receiver, source), lazy);

    // exactly at the bound: 512 + 512 = 1024 also stays an array
    RoaringBitmap atBound = arrayAt(2, 0, 512);
    RoaringBitmap atBoundSource = arrayAt(1, 0, 1);
    atBoundSource.or(arrayAt(2, 512, 512));
    RoaringBitmap lazyAtBound = atBound.clone();
    lazyAtBound.lazyor(atBoundSource);
    Container mergedAtBound = containerAt(lazyAtBound, 2);
    assertInstanceOf(ArrayContainer.class, mergedAtBound);
    assertEquals(ARRAY_LAZY_LOWERBOUND, mergedAtBound.getCardinality());
    lazyAtBound.repairAfterLazy();
    assertEquals(assertLazyUnion(atBound, atBoundSource), lazyAtBound);
  }

  @Test
  public void arrayPromotesAbove1024AndRepairDemotes() throws IOException {
    // 513 + 512 = 1025 > 1024: lazy bitmap container before repair, array again after repair
    RoaringBitmap receiver = arrayAt(2, 0, 513);
    RoaringBitmap source = arrayAt(1, 0, 1);
    source.or(arrayAt(2, 513, 512));

    RoaringBitmap lazy = receiver.clone();
    lazy.lazyor(source);
    Container merged = containerAt(lazy, 2);
    assertInstanceOf(BitmapContainer.class, merged);
    assertTrue(merged.getCardinality() < 0, "lazy bitmap container has an invalid cardinality");
    lazy.repairAfterLazy();
    Container repaired = containerAt(lazy, 2);
    assertInstanceOf(ArrayContainer.class, repaired);
    assertEquals(1025, repaired.getCardinality());
    assertEquals(assertLazyUnion(receiver, source), lazy);

    // above 4096 the bitmap container stays after repair
    RoaringBitmap big = arrayAt(2, 0, 2100);
    RoaringBitmap bigSource = arrayAt(1, 0, 1);
    bigSource.or(arrayAt(2, 2100, 2100));
    RoaringBitmap lazyBig = big.clone();
    lazyBig.lazyor(bigSource);
    assertInstanceOf(BitmapContainer.class, containerAt(lazyBig, 2));
    assertTrue(containerAt(lazyBig, 2).getCardinality() < 0);
    lazyBig.repairAfterLazy();
    assertInstanceOf(BitmapContainer.class, containerAt(lazyBig, 2));
    assertEquals(4200, containerAt(lazyBig, 2).getCardinality());
    assertEquals(assertLazyUnion(big, bigSource), lazyBig);

    // repair demotes at exactly 4096 and keeps the bitmap container at 4097
    for (int extra : new int[] {2048, 2049}) {
      RoaringBitmap edge = arrayAt(2, 0, extra);
      RoaringBitmap edgeSource = arrayAt(1, 0, 1);
      edgeSource.or(arrayAt(2, extra, 2048));
      RoaringBitmap lazyEdge = edge.clone();
      lazyEdge.lazyor(edgeSource);
      assertInstanceOf(BitmapContainer.class, containerAt(lazyEdge, 2));
      lazyEdge.repairAfterLazy();
      Container repairedEdge = containerAt(lazyEdge, 2);
      assertEquals(extra + 2048, repairedEdge.getCardinality());
      if (extra + 2048 <= 4096) {
        assertInstanceOf(
            ArrayContainer.class, repairedEdge, "4096 values repair to an array container");
      } else {
        assertInstanceOf(
            BitmapContainer.class, repairedEdge, "4097 values stay a bitmap container");
      }
      assertEquals(assertLazyUnion(edge, edgeSource), lazyEdge);
    }
  }

  @Test
  public void sparseSourceGallopsOverReceiverOnlyKeys() throws IOException {
    // many receiver-only keys between the source keys: the receiver walk gallops with advanceUntil
    // instead of stepping one key at a time; results must not depend on how it advances
    RoaringBitmap receiver = smallArrays(range(0, 4096, 1), 5);
    assertLazyUnion(receiver.clone(), smallArrays(new int[] {7, 1000, 4095}, 40)); // all present
    assertLazyUnion(receiver.clone(), smallArrays(new int[] {7, 1000, 5000}, 40)); // missing tail
    assertLazyUnion(receiver.clone(), smallArrays(new int[] {0, 2048, 65535}, 40)); // both ends
    assertLazyUnion(receiver.clone(), smallArrays(new int[] {4096}, 40)); // append only
    assertLazyUnion(receiver.clone(), smallArrays(new int[] {1, 2, 3}, 40)); // adjacent keys
    // naivelazyor takes the same walk
    RoaringBitmap source = smallArrays(new int[] {7, 1000, 4095}, 40);
    RoaringBitmap expected = receiver.clone();
    expected.or(source);
    RoaringBitmap naive = receiver.clone();
    naive.naivelazyor(source);
    naive.repairAfterLazy();
    assertEquals(expected, naive);
    assertTrue(naive.validate());
  }

  @Test
  public void naiveLazyOrStillPromotesOnBulkPath() throws IOException {
    // receiver key 2 is shared after the source-only key 1, so it is merged by the bulk pass;
    // naivelazyor must still promote it to a bitmap container (MERGE_NAIVE_LAZY_OR), while the
    // receiver-only key 4 is left alone
    RoaringBitmap receiver = arrayAt(0, 0, 10);
    receiver.or(arrayAt(2, 0, 10));
    receiver.or(arrayAt(4, 0, 10));
    RoaringBitmap source = arrayAt(0, 10, 10);
    source.or(arrayAt(1, 0, 1));
    source.or(arrayAt(2, 10, 10));
    RoaringBitmap expected = receiver.clone();
    expected.or(source);

    RoaringBitmap naive = receiver.clone();
    naive.naivelazyor(source);
    // key 0 is merged by the per-key loop, key 2 by the bulk pass: both promoted
    assertInstanceOf(BitmapContainer.class, containerAt(naive, 0));
    assertTrue(containerAt(naive, 0).getCardinality() < 0);
    assertInstanceOf(BitmapContainer.class, containerAt(naive, 2));
    assertTrue(containerAt(naive, 2).getCardinality() < 0);
    assertInstanceOf(ArrayContainer.class, containerAt(naive, 1));
    assertInstanceOf(ArrayContainer.class, containerAt(naive, 4));
    naive.repairAfterLazy();
    assertTrue(naive.validate());
    assertEquals(expected, naive);
    assertInstanceOf(ArrayContainer.class, containerAt(naive, 2));

    // lazyor on the same input does not promote
    RoaringBitmap lazy = receiver.clone();
    lazy.lazyor(source);
    assertInstanceOf(ArrayContainer.class, containerAt(lazy, 0));
    assertInstanceOf(ArrayContainer.class, containerAt(lazy, 2));
    assertTrue(lazy.validate());
    lazy.repairAfterLazy();
    assertEquals(expected, lazy);

    assertEquals(expected, FastAggregation.naive_or(receiver, source));
    assertEquals(expected, FastAggregation.naive_or(source, receiver));
    assertEquals(expected, FastAggregation.or(receiver, source));
  }
}
