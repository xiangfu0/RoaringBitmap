package org.roaringbitmap.buffer;

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
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * Tests for the protected {@link MutableRoaringBitmap#lazyor(ImmutableRoaringBitmap)} path, in
 * particular the bulk insertion of source-only keys (the same {@code mergeBulk} pass that {@code
 * or} and {@code naivelazyor} use) and the container types it leaves behind before {@link
 * MutableRoaringBitmap#repairAfterLazy()}. Every source is exercised as a heap bitmap, as a
 * read-only mapped {@link ImmutableRoaringBitmap} and as a direct-buffer one.
 */
@Execution(ExecutionMode.CONCURRENT)
public class TestLazyOr {

  /** Mirror of the private {@code MappeableArrayContainer.ARRAY_LAZY_LOWERBOUND}. */
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

    MappeableContainer build(int offset) {
      MappeableContainer c;
      switch (this) {
        case ARRAY:
          c = new MappeableArrayContainer();
          for (int i = 0; i < 300; i++) {
            c = c.add((char) (7 + 3 * i + offset));
          }
          assertInstanceOf(MappeableArrayContainer.class, c);
          return c;
        case ARRAY1:
          c = new MappeableArrayContainer().add((char) (1000 + offset));
          assertInstanceOf(MappeableArrayContainer.class, c);
          return c;
        case BITMAP:
          c = new MappeableBitmapContainer();
          for (int i = 0; i < 6000; i++) {
            c = c.add((char) (2 * i + offset));
          }
          assertInstanceOf(MappeableBitmapContainer.class, c);
          return c;
        case RUN:
        default:
          c = new MappeableRunContainer().iadd(100 + 5000 * offset, 10100 + 5000 * offset);
          assertInstanceOf(MappeableRunContainer.class, c);
          return c;
      }
    }
  }

  /** How a source bitmap is presented to {@code lazyor}. */
  enum Source {
    HEAP,
    MAPPED_READ_ONLY,
    DIRECT;

    ImmutableRoaringBitmap view(MutableRoaringBitmap b) {
      switch (this) {
        case HEAP:
          return b;
        case MAPPED_READ_ONLY:
          {
            ByteBuffer buffer = ByteBuffer.allocate(b.serializedSizeInBytes());
            b.serialize(buffer);
            buffer.flip();
            return new ImmutableRoaringBitmap(buffer.asReadOnlyBuffer());
          }
        case DIRECT:
        default:
          {
            ByteBuffer buffer = ByteBuffer.allocateDirect(b.serializedSizeInBytes());
            b.serialize(buffer);
            buffer.flip();
            return new ImmutableRoaringBitmap(buffer);
          }
      }
    }
  }

  private static int value(int key, int low) {
    return (key << 16) | low;
  }

  /** Appends one container of the given kind per key; keys must be increasing (as chars). */
  private static MutableRoaringBitmap bitmapOf(int offset, int[] keys, Kind... kinds) {
    MutableRoaringBitmap b = new MutableRoaringBitmap();
    for (int i = 0; i < keys.length; i++) {
      Kind kind = kinds[Math.min(i, kinds.length - 1)];
      b.getMappeableRoaringArray().append((char) keys[i], kind.build(offset));
    }
    assertTrue(b.validate());
    return b;
  }

  /** A bitmap with a small array container (a few spaced values) at each key. */
  private static MutableRoaringBitmap smallArrays(int[] keys, int low) {
    MutableRoaringBitmap b = new MutableRoaringBitmap();
    for (int key : keys) {
      for (int i = 0; i < 5; i++) {
        b.add(value(key, low + 4 * i));
      }
    }
    return b;
  }

  private static MutableRoaringBitmap arrayAt(int key, int firstLow, int cardinality) {
    MutableRoaringBitmap b = new MutableRoaringBitmap();
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

  private static MutableRoaringBitmap roundTrip(MutableRoaringBitmap b) throws IOException {
    ByteArrayOutputStream bos = new ByteArrayOutputStream();
    b.serialize(new DataOutputStream(bos));
    MutableRoaringBitmap copy = new MutableRoaringBitmap();
    copy.deserialize(new DataInputStream(new ByteArrayInputStream(bos.toByteArray())));
    assertTrue(copy.validate());
    return copy;
  }

  private static MappeableContainer containerAt(MutableRoaringBitmap b, int key) {
    int index = b.highLowContainer.getIndex((char) key);
    assertTrue(index >= 0, "key " + key + " missing");
    return b.highLowContainer.getContainerAtIndex(index);
  }

  /**
   * Asserts that no container of {@code lazy} is the same instance as a container of {@code
   * source}: {@code lazyor} must copy source-only containers (also on the bulk path) and {@code
   * lazyIOR} never returns its argument. Only a heap source can share instances; a mapped source
   * hands out a fresh view per call, so the check is trivially true there.
   */
  private static void assertNoSharedContainers(
      MutableRoaringBitmap lazy, ImmutableRoaringBitmap source) {
    for (int i = 0; i < lazy.highLowContainer.size(); i++) {
      MappeableContainer c = lazy.highLowContainer.getContainerAtIndex(i);
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

  /**
   * Adds a value to and removes a value from every container of {@code b}, in place. A receiver
   * container that still wrapped a source buffer would corrupt a direct source or throw on a
   * read-only one.
   */
  private static void mutateEveryContainer(MutableRoaringBitmap b) {
    for (int i = 0; i < b.highLowContainer.size(); i++) {
      int key = b.highLowContainer.getKeyAtIndex(i);
      int present = value(key, b.highLowContainer.getContainerAtIndex(i).first());
      b.add(value(key, 60000));
      b.remove(present);
    }
  }

  /**
   * Asserts, for every {@link Source} presentation of {@code source}, that {@code lazyor} followed
   * by {@code repairAfterLazy} equals the eager union and the {@link BufferFastAggregation} union,
   * validates, survives a serialize round trip and leaves the source untouched. Also asserts that
   * the receiver never holds a container instance of the source: by identity before repair, and by
   * mutating every container of the repaired result afterwards. Neither argument is modified.
   * Returns the repaired lazy result of the heap source.
   */
  private static MutableRoaringBitmap assertLazyUnion(
      MutableRoaringBitmap receiver, MutableRoaringBitmap source) throws IOException {
    MutableRoaringBitmap sourceSnapshot = source.clone();
    MutableRoaringBitmap eager = receiver.clone();
    eager.or(source);
    MutableRoaringBitmap heapResult = null;
    for (Source presentation : Source.values()) {
      ImmutableRoaringBitmap view = presentation.view(source);
      MutableRoaringBitmap fast = BufferFastAggregation.or(receiver.clone(), view);

      MutableRoaringBitmap lazy = receiver.clone();
      lazy.lazyor(view);
      assertNoSharedContainers(lazy, view);
      lazy.repairAfterLazy();

      assertTrue(lazy.validate(), presentation.toString());
      assertEquals(eager, lazy, presentation.toString());
      assertEquals(fast, lazy, presentation.toString());
      assertEquals(eager.getCardinality(), lazy.getCardinality());
      assertEquals(eager.highLowContainer.size(), lazy.highLowContainer.size());
      assertEquals(lazy, roundTrip(lazy));
      assertEquals(sourceSnapshot, view, presentation.toString());
      assertTrue(view.validate());

      // mutating the result must not leak into the source: source-only containers were copied
      MutableRoaringBitmap result = lazy.clone();
      mutateEveryContainer(lazy);
      assertEquals(sourceSnapshot, view, presentation.toString());
      assertTrue(view.validate());
      if (presentation == Source.HEAP) {
        heapResult = result;
      }
    }
    assertEquals(sourceSnapshot, source);
    assertTrue(source.validate());
    return heapResult;
  }

  @Test
  public void interleavedKeys() throws IOException {
    // receiver has the even keys, the source the odd ones: one source-only key between every pair
    // of receiver keys, the case that was quadratic with a per-key insert
    MutableRoaringBitmap receiver = smallArrays(range(0, 400, 2), 5);
    MutableRoaringBitmap source = smallArrays(range(1, 400, 2), 7);
    MutableRoaringBitmap result = assertLazyUnion(receiver, source);
    assertEquals(400, result.highLowContainer.size());
    // and the mirror image, where the first source-only key comes after the first receiver key
    assertLazyUnion(source, receiver);
  }

  @Test
  public void leadingSourceOnlyKeys() throws IOException {
    MutableRoaringBitmap receiver = smallArrays(range(10, 20, 1), 5);
    MutableRoaringBitmap source = smallArrays(new int[] {0, 1, 2, 3, 4, 12, 15}, 7);
    assertLazyUnion(receiver, source);
    // source-only keys only, all before the receiver's keys
    assertLazyUnion(receiver, smallArrays(range(0, 5, 1), 7));
  }

  @Test
  public void trailingSourceOnlyKeys() throws IOException {
    // shared keys first, then source-only keys: the appendCopy tail path
    MutableRoaringBitmap receiver = smallArrays(range(0, 10, 1), 5);
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
    MutableRoaringBitmap receiver = smallArrays(new int[] {1, 0x8000, 0xFFFE}, 5);
    MutableRoaringBitmap source = smallArrays(new int[] {0x7FFF, 0x8001, 0xFFFF}, 7);
    MutableRoaringBitmap result = assertLazyUnion(receiver, source);
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
    MutableRoaringBitmap receiver = bitmapOf(0, new int[] {5, 9}, receiverKind);
    MutableRoaringBitmap source = bitmapOf(1, new int[] {3, 5, 7, 9}, Kind.ARRAY, sourceKind);
    assertLazyUnion(receiver, source);
    assertLazyUnion(source, receiver);

    // shared key first (merged by the per-key loop), then a source-only key (bulk pass)
    MutableRoaringBitmap receiver2 = bitmapOf(0, new int[] {1, 4}, receiverKind);
    MutableRoaringBitmap source2 = bitmapOf(1, new int[] {1, 2}, sourceKind);
    assertLazyUnion(receiver2, source2);

    // identical content on both sides
    assertLazyUnion(receiver, bitmapOf(0, new int[] {3, 5, 9}, Kind.ARRAY1, receiverKind));
  }

  @Test
  public void severalLazyOrsBeforeOneRepair() throws IOException {
    Random random = new Random(20260922);
    Kind[] kinds = Kind.values();
    Source[] presentations = Source.values();
    List<MutableRoaringBitmap> inputs = new ArrayList<>();
    for (int n = 0; n < 9; n++) {
      TreeSet<Integer> keys = new TreeSet<>();
      while (keys.size() < 30) {
        keys.add(random.nextInt(80));
      }
      keys.add(0x8000 + random.nextInt(3));
      keys.add(0xFFFD + random.nextInt(3));
      MutableRoaringBitmap b = new MutableRoaringBitmap();
      for (int key : keys) {
        b.getMappeableRoaringArray()
            .append((char) key, kinds[random.nextInt(kinds.length)].build(random.nextInt(3)));
      }
      assertTrue(b.validate());
      inputs.add(b);
    }
    List<ImmutableRoaringBitmap> views = new ArrayList<>();
    List<MutableRoaringBitmap> snapshots = new ArrayList<>();
    for (int i = 0; i < inputs.size(); i++) {
      // round-robin over heap, read-only mapped and direct sources
      views.add(presentations[i % presentations.length].view(inputs.get(i)));
      snapshots.add(inputs.get(i).clone());
    }

    // the receiver is a heap copy of a mapped bitmap
    MutableRoaringBitmap lazy =
        Source.MAPPED_READ_ONLY.view(inputs.get(0)).toMutableRoaringBitmap();
    MutableRoaringBitmap eager = inputs.get(0).clone();
    for (int i = 1; i < views.size(); i++) {
      lazy.lazyor(views.get(i));
      eager.or(views.get(i));
    }
    for (ImmutableRoaringBitmap view : views) {
      assertNoSharedContainers(lazy, view);
    }
    lazy.repairAfterLazy();
    assertTrue(lazy.validate());
    assertEquals(eager, lazy);
    assertEquals(BufferFastAggregation.or(views.toArray(new ImmutableRoaringBitmap[0])), lazy);
    assertEquals(lazy, roundTrip(lazy));
    for (int i = 0; i < inputs.size(); i++) {
      assertEquals(snapshots.get(i), inputs.get(i));
      assertEquals(snapshots.get(i), views.get(i));
    }
    // mutating the result must not leak into any input, whether heap, mapped or direct
    mutateEveryContainer(lazy);
    for (int i = 0; i < inputs.size(); i++) {
      assertEquals(snapshots.get(i), inputs.get(i));
      assertEquals(snapshots.get(i), views.get(i));
      assertTrue(views.get(i).validate());
    }
  }

  @Test
  public void selfUnionIsNoOp() {
    MutableRoaringBitmap b = bitmapOf(0, new int[] {1, 2, 3, 4}, Kind.ARRAY, Kind.BITMAP, Kind.RUN);
    MutableRoaringBitmap snapshot = b.clone();
    b.lazyor(b);
    assertEquals(snapshot, b);
    assertTrue(b.validate());
    b.repairAfterLazy();
    assertEquals(snapshot, b);
  }

  @Test
  public void emptyReceiver() throws IOException {
    MutableRoaringBitmap source =
        bitmapOf(0, new int[] {0, 7, 0x8000}, Kind.ARRAY, Kind.RUN, Kind.BITMAP);
    MutableRoaringBitmap result = assertLazyUnion(new MutableRoaringBitmap(), source);
    assertEquals(source, result);
    // the receiver holds copies, not the source's containers
    MutableRoaringBitmap lazy = new MutableRoaringBitmap();
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
    MutableRoaringBitmap receiver =
        bitmapOf(0, new int[] {0, 7, 0x8000}, Kind.ARRAY, Kind.RUN, Kind.BITMAP);
    assertEquals(receiver, assertLazyUnion(receiver, new MutableRoaringBitmap()));
    assertEquals(
        new MutableRoaringBitmap(),
        assertLazyUnion(new MutableRoaringBitmap(), new MutableRoaringBitmap()));
  }

  @Test
  public void arrayStaysArrayThroughBulkPath() throws IOException {
    // key 1 is source-only, so key 2 is merged by the bulk pass; 500 + 500 <= 1024 stays an array
    MutableRoaringBitmap receiver = arrayAt(0, 0, 500);
    receiver.or(arrayAt(2, 0, 500));
    MutableRoaringBitmap source = arrayAt(1, 0, 1);
    source.or(arrayAt(2, 500, 500));

    for (Source presentation : Source.values()) {
      MutableRoaringBitmap lazy = receiver.clone();
      lazy.lazyor(presentation.view(source));
      MappeableContainer merged = containerAt(lazy, 2);
      assertInstanceOf(MappeableArrayContainer.class, merged, presentation.toString());
      assertEquals(1000, merged.getCardinality());
      assertTrue(lazy.validate());
      lazy.repairAfterLazy();
      assertInstanceOf(MappeableArrayContainer.class, containerAt(lazy, 2));
      assertEquals(assertLazyUnion(receiver, source), lazy);
    }

    // exactly at the bound: 512 + 512 = 1024 also stays an array
    MutableRoaringBitmap atBound = arrayAt(2, 0, 512);
    MutableRoaringBitmap atBoundSource = arrayAt(1, 0, 1);
    atBoundSource.or(arrayAt(2, 512, 512));
    MutableRoaringBitmap lazyAtBound = atBound.clone();
    lazyAtBound.lazyor(Source.MAPPED_READ_ONLY.view(atBoundSource));
    MappeableContainer mergedAtBound = containerAt(lazyAtBound, 2);
    assertInstanceOf(MappeableArrayContainer.class, mergedAtBound);
    assertEquals(ARRAY_LAZY_LOWERBOUND, mergedAtBound.getCardinality());
    lazyAtBound.repairAfterLazy();
    assertEquals(assertLazyUnion(atBound, atBoundSource), lazyAtBound);
  }

  @Test
  public void arrayPromotesAbove1024AndRepairDemotes() throws IOException {
    // 513 + 512 = 1025 > 1024: lazy bitmap container before repair, array again after repair
    MutableRoaringBitmap receiver = arrayAt(2, 0, 513);
    MutableRoaringBitmap source = arrayAt(1, 0, 1);
    source.or(arrayAt(2, 513, 512));

    for (Source presentation : Source.values()) {
      MutableRoaringBitmap lazy = receiver.clone();
      lazy.lazyor(presentation.view(source));
      MappeableContainer merged = containerAt(lazy, 2);
      assertInstanceOf(MappeableBitmapContainer.class, merged, presentation.toString());
      assertTrue(merged.getCardinality() < 0, "lazy bitmap container has an invalid cardinality");
      lazy.repairAfterLazy();
      MappeableContainer repaired = containerAt(lazy, 2);
      assertInstanceOf(MappeableArrayContainer.class, repaired);
      assertEquals(1025, repaired.getCardinality());
      assertEquals(assertLazyUnion(receiver, source), lazy);
    }

    // above 4096 the bitmap container stays after repair
    MutableRoaringBitmap big = arrayAt(2, 0, 2100);
    MutableRoaringBitmap bigSource = arrayAt(1, 0, 1);
    bigSource.or(arrayAt(2, 2100, 2100));
    MutableRoaringBitmap lazyBig = big.clone();
    lazyBig.lazyor(Source.DIRECT.view(bigSource));
    assertInstanceOf(MappeableBitmapContainer.class, containerAt(lazyBig, 2));
    assertTrue(containerAt(lazyBig, 2).getCardinality() < 0);
    lazyBig.repairAfterLazy();
    assertInstanceOf(MappeableBitmapContainer.class, containerAt(lazyBig, 2));
    assertEquals(4200, containerAt(lazyBig, 2).getCardinality());
    assertEquals(assertLazyUnion(big, bigSource), lazyBig);

    // repair demotes at exactly 4096 and keeps the bitmap container at 4097
    for (int extra : new int[] {2048, 2049}) {
      MutableRoaringBitmap edge = arrayAt(2, 0, extra);
      MutableRoaringBitmap edgeSource = arrayAt(1, 0, 1);
      edgeSource.or(arrayAt(2, extra, 2048));
      MutableRoaringBitmap lazyEdge = edge.clone();
      lazyEdge.lazyor(Source.DIRECT.view(edgeSource));
      assertInstanceOf(MappeableBitmapContainer.class, containerAt(lazyEdge, 2));
      lazyEdge.repairAfterLazy();
      MappeableContainer repairedEdge = containerAt(lazyEdge, 2);
      assertEquals(extra + 2048, repairedEdge.getCardinality());
      if (extra + 2048 <= 4096) {
        assertInstanceOf(
            MappeableArrayContainer.class,
            repairedEdge,
            "4096 values repair to an array container");
      } else {
        assertInstanceOf(
            MappeableBitmapContainer.class, repairedEdge, "4097 values stay a bitmap container");
      }
      assertEquals(assertLazyUnion(edge, edgeSource), lazyEdge);
    }
  }

  @Test
  public void sparseSourceGallopsOverReceiverOnlyKeys() throws IOException {
    // many receiver-only keys between the source keys: the receiver walk gallops with advanceUntil
    // instead of stepping one key at a time; results must not depend on how it advances
    MutableRoaringBitmap receiver = smallArrays(range(0, 4096, 1), 5);
    assertLazyUnion(receiver.clone(), smallArrays(new int[] {7, 1000, 4095}, 40)); // all present
    assertLazyUnion(receiver.clone(), smallArrays(new int[] {7, 1000, 5000}, 40)); // missing tail
    assertLazyUnion(receiver.clone(), smallArrays(new int[] {0, 2048, 65535}, 40)); // both ends
    assertLazyUnion(receiver.clone(), smallArrays(new int[] {4096}, 40)); // append only
    assertLazyUnion(receiver.clone(), smallArrays(new int[] {1, 2, 3}, 40)); // adjacent keys
    // naivelazyor takes the same walk, for every source presentation
    MutableRoaringBitmap source = smallArrays(new int[] {7, 1000, 4095}, 40);
    MutableRoaringBitmap expected = receiver.clone();
    expected.or(source);
    for (Source presentation : Source.values()) {
      MutableRoaringBitmap naive = receiver.clone();
      naive.naivelazyor(presentation.view(source));
      naive.repairAfterLazy();
      assertEquals(expected, naive, presentation.toString());
      assertTrue(naive.validate());
    }
  }

  @Test
  public void naiveLazyOrStillPromotesOnBulkPath() throws IOException {
    // receiver key 2 is shared after the source-only key 1, so it is merged by the bulk pass;
    // naivelazyor must still promote it to a bitmap container (MERGE_NAIVE_LAZY_OR), while the
    // receiver-only key 4 is left alone
    MutableRoaringBitmap receiver = arrayAt(0, 0, 10);
    receiver.or(arrayAt(2, 0, 10));
    receiver.or(arrayAt(4, 0, 10));
    MutableRoaringBitmap source = arrayAt(0, 10, 10);
    source.or(arrayAt(1, 0, 1));
    source.or(arrayAt(2, 10, 10));
    MutableRoaringBitmap expected = receiver.clone();
    expected.or(source);

    for (Source presentation : Source.values()) {
      ImmutableRoaringBitmap view = presentation.view(source);
      MutableRoaringBitmap naive = receiver.clone();
      naive.naivelazyor(view);
      // key 0 is merged by the per-key loop, key 2 by the bulk pass: both promoted
      assertInstanceOf(MappeableBitmapContainer.class, containerAt(naive, 0));
      assertTrue(containerAt(naive, 0).getCardinality() < 0);
      assertInstanceOf(
          MappeableBitmapContainer.class, containerAt(naive, 2), presentation.toString());
      assertTrue(containerAt(naive, 2).getCardinality() < 0);
      assertInstanceOf(MappeableArrayContainer.class, containerAt(naive, 1));
      assertInstanceOf(MappeableArrayContainer.class, containerAt(naive, 4));
      naive.repairAfterLazy();
      assertTrue(naive.validate());
      assertEquals(expected, naive);
      assertInstanceOf(MappeableArrayContainer.class, containerAt(naive, 2));

      // lazyor on the same input does not promote
      MutableRoaringBitmap lazy = receiver.clone();
      lazy.lazyor(view);
      assertInstanceOf(MappeableArrayContainer.class, containerAt(lazy, 0));
      assertInstanceOf(MappeableArrayContainer.class, containerAt(lazy, 2));
      assertTrue(lazy.validate());
      lazy.repairAfterLazy();
      assertEquals(expected, lazy);

      assertEquals(expected, BufferFastAggregation.naive_or(receiver, view));
      assertEquals(expected, BufferFastAggregation.naive_or(view, receiver));
      assertEquals(expected, BufferFastAggregation.or(receiver, view));
    }
  }
}
