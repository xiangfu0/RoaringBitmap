package org.roaringbitmap.buffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

@Execution(ExecutionMode.CONCURRENT)
public class TestMutableRoaringBitmapUnion {

  /** High keys spanning the signed boundary (0x8000 and above are "negative" ints). */
  private static final int[] KEYS = {0, 1, 2, 7, 100, 0x7FFF, 0x8000, 0xFFFE, 0xFFFF};

  private static int value(int key, int low) {
    return (key << 16) | low;
  }

  private static long unsigned(int value) {
    return Integer.toUnsignedLong(value);
  }

  /** Serializes into a heap buffer and maps it read-only, like a memory-mapped file. */
  static ImmutableRoaringBitmap toMapped(ImmutableRoaringBitmap b) {
    ByteArrayOutputStream bos = new ByteArrayOutputStream();
    try {
      b.serialize(new DataOutputStream(bos));
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
    ByteBuffer bb = ByteBuffer.wrap(bos.toByteArray()).asReadOnlyBuffer();
    ImmutableRoaringBitmap mapped = new ImmutableRoaringBitmap(bb);
    assertTrue(mapped.validate());
    return mapped;
  }

  static ImmutableRoaringBitmap toDirect(ImmutableRoaringBitmap b) {
    ByteBuffer bb = ByteBuffer.allocateDirect(b.serializedSizeInBytes());
    b.serialize(bb);
    bb.flip();
    ImmutableRoaringBitmap direct = new ImmutableRoaringBitmap(bb);
    assertTrue(direct.validate());
    return direct;
  }

  /** Picks a heap, a read-only mapped or a direct-buffer view of the same values. */
  static ImmutableRoaringBitmap asInput(MutableRoaringBitmap b, Random r) {
    switch (r.nextInt(3)) {
      case 0:
        return b;
      case 1:
        return toMapped(b);
      default:
        return toDirect(b);
    }
  }

  private static void addSparseArray(MutableRoaringBitmap b, int key, Random r) {
    int n = 1 + r.nextInt(64);
    for (int i = 0; i < n; i++) {
      b.add(value(key, r.nextInt(65536)));
    }
  }

  /** Two of these in the same key exceed ARRAY_LAZY_LOWERBOUND (1024) and promote lazily. */
  private static void addDenseArray(MutableRoaringBitmap b, int key, Random r) {
    int n = 700 + r.nextInt(300);
    for (int i = 0; i < n; i++) {
      b.add(value(key, r.nextInt(65536)));
    }
  }

  private static void addBitmap(MutableRoaringBitmap b, int key, Random r) {
    int n = 5000 + r.nextInt(3000);
    for (int i = 0; i < n; i++) {
      b.add(value(key, r.nextInt(65536)));
    }
  }

  private static void addRun(MutableRoaringBitmap b, int key, Random r) {
    int start = r.nextInt(60000);
    int length = 1 + r.nextInt(5000);
    b.add(unsigned(value(key, start)), unsigned(value(key, start)) + length);
  }

  static MutableRoaringBitmap randomBitmap(Random r) {
    MutableRoaringBitmap b = new MutableRoaringBitmap();
    int keys = 1 + r.nextInt(4);
    for (int k = 0; k < keys; k++) {
      int key = KEYS[r.nextInt(KEYS.length)];
      switch (r.nextInt(4)) {
        case 0:
          addSparseArray(b, key, r);
          break;
        case 1:
          addDenseArray(b, key, r);
          break;
        case 2:
          addBitmap(b, key, r);
          break;
        default:
          addRun(b, key, r);
          break;
      }
    }
    if (r.nextBoolean()) {
      b.runOptimize();
    } else {
      // add(range) followed by point adds leaves run containers with thousands of runs, which
      // validate() rejects as inefficient; inputs are documented as valid, so normalize either way
      b.removeRunCompression();
    }
    assertTrue(b.validate());
    return b;
  }

  static ImmutableRoaringBitmap randomInput(Random r) {
    return asInput(randomBitmap(r), r);
  }

  /** Valid, repaired and canonical: no lazy cardinality, no bitmap container at array size. */
  private static void assertValid(MutableRoaringBitmap b) {
    assertTrue(b.validate());
    MutableRoaringArray array = b.getMappeableRoaringArray();
    for (int k = 0; k < array.size(); k++) {
      MappeableContainer c = array.getContainerAtIndex(k);
      assertTrue(c.getCardinality() >= 0, "lazy container escaped at index " + k);
      if (c instanceof MappeableBitmapContainer) {
        assertTrue(
            c.getCardinality() > MappeableArrayContainer.DEFAULT_MAX_SIZE,
            "bitmap container at array size");
      }
    }
  }

  private static MutableRoaringBitmap roundTrip(MutableRoaringBitmap b) throws IOException {
    ByteArrayOutputStream bos = new ByteArrayOutputStream();
    b.serialize(new DataOutputStream(bos));
    MutableRoaringBitmap copy = new MutableRoaringBitmap();
    copy.deserialize(new DataInputStream(new ByteArrayInputStream(bos.toByteArray())));
    return copy;
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8})
  public void seededFoldsMatchEagerUnion(int seed) throws IOException {
    Random r = new Random(seed);
    List<ImmutableRoaringBitmap> inputs = new ArrayList<>();
    List<MutableRoaringBitmap> snapshots = new ArrayList<>();
    ImmutableRoaringBitmap repeated = randomInput(r);
    for (int i = 0; i < 40; i++) {
      int choice = r.nextInt(10);
      ImmutableRoaringBitmap in =
          choice == 0 ? new MutableRoaringBitmap() : choice == 1 ? repeated : randomInput(r);
      inputs.add(in);
      snapshots.add(in.toMutableRoaringBitmap());
    }
    MutableRoaringBitmapUnion union = new MutableRoaringBitmapUnion();
    MutableRoaringBitmap expected = new MutableRoaringBitmap();
    for (int i = 0; i < inputs.size(); i++) {
      union.add(inputs.get(i));
      expected.or(inputs.get(i));
      if (i % 7 == 6) {
        MutableRoaringBitmap partial = union.get();
        assertValid(partial);
        assertEquals(expected, partial);
      }
    }
    MutableRoaringBitmap result = union.get();
    assertValid(result);
    assertEquals(expected, result);
    assertEquals(BufferFastAggregation.or(inputs.toArray(new ImmutableRoaringBitmap[0])), result);
    assertEquals(expected.getCardinality(), result.getCardinality());
    assertEquals(result, roundTrip(result));

    MutableRoaringBitmap taken = union.take();
    assertSame(result, taken);
    assertValid(taken);
    assertTrue(union.get().isEmpty());
    assertFalse(union.isDirty());
    for (int i = 0; i < inputs.size(); i++) {
      assertEquals(snapshots.get(i), inputs.get(i), "input " + i + " was modified");
    }
  }

  @ParameterizedTest
  @ValueSource(ints = {11, 12, 13, 14})
  public void interleavedAddsPointInsertsAndReadsMatchEagerFold(int seed) {
    Random r = new Random(seed);
    for (int variant = 0; variant < 3; variant++) {
      MutableRoaringBitmap expected = new MutableRoaringBitmap();
      MutableRoaringBitmapUnion union;
      if (variant == 0) {
        union = new MutableRoaringBitmapUnion();
      } else if (variant == 1) {
        MutableRoaringBitmap initial = randomBitmap(r);
        expected.or(initial);
        union = MutableRoaringBitmapUnion.takeOwnership(initial);
      } else {
        // receiver adopted from a mapped bitmap
        ImmutableRoaringBitmap mapped = toMapped(randomBitmap(r));
        expected.or(mapped);
        union = MutableRoaringBitmapUnion.takeOwnership(mapped.toMutableRoaringBitmap());
      }
      List<MutableRoaringBitmap> published = new ArrayList<>();
      List<MutableRoaringBitmap> publishedSnapshots = new ArrayList<>();
      for (int step = 0; step < 300; step++) {
        int op = r.nextInt(100);
        if (op < 40) {
          ImmutableRoaringBitmap in = randomInput(r);
          union.add(in);
          expected.or(in);
        } else if (op < 80) {
          int v = value(KEYS[r.nextInt(KEYS.length)], r.nextInt(65536));
          union.add(v);
          expected.add(v);
        } else if (op < 95) {
          MutableRoaringBitmap g = union.get();
          assertValid(g);
          assertEquals(expected, g);
          published.add(g);
          publishedSnapshots.add(g.clone());
        } else {
          MutableRoaringBitmap t = union.take();
          assertValid(t);
          assertEquals(expected, t);
          published.add(t);
          publishedSnapshots.add(t.clone());
          expected = new MutableRoaringBitmap();
        }
      }
      MutableRoaringBitmap end = union.take();
      assertValid(end);
      assertEquals(expected, end);
      // nothing handed out through get() or take() was modified afterwards
      for (int i = 0; i < published.size(); i++) {
        assertEquals(publishedSnapshots.get(i), published.get(i), "published bitmap " + i);
        assertValid(published.get(i));
      }
    }
  }

  @Test
  public void getIsIdempotentWithoutMutation() {
    MutableRoaringBitmapUnion union = new MutableRoaringBitmapUnion();
    union.add(toMapped(MutableRoaringBitmap.bitmapOf(1, 2, 3)));
    union.add(toDirect(MutableRoaringBitmap.bitmapOf(3, 4, value(0x8000, 5))));
    MutableRoaringBitmap first = union.get();
    assertSame(first, union.get());
    assertSame(first, union.get());
    assertValid(first);
    assertEquals(MutableRoaringBitmap.bitmapOf(1, 2, 3, 4, value(0x8000, 5)), first);
  }

  @Test
  public void getThenTakeReturnsTheSameInstance() {
    MutableRoaringBitmapUnion union = new MutableRoaringBitmapUnion();
    union.add(toMapped(MutableRoaringBitmap.bitmapOf(10, 20)));
    MutableRoaringBitmap got = union.get();
    assertSame(got, union.take());
    assertValid(got);
    assertTrue(union.get().isEmpty());
    assertNotSame(got, union.get());
  }

  @Test
  public void getIsCopyOnWriteForBitmapAdds() {
    MutableRoaringBitmapUnion union = new MutableRoaringBitmapUnion();
    union.add(toMapped(MutableRoaringBitmap.bitmapOf(1, 2, 3)));
    MutableRoaringBitmap alias = union.get();
    MutableRoaringBitmap snapshot = alias.clone();
    assertTrue(union.isPublished());

    ImmutableRoaringBitmap next = toMapped(MutableRoaringBitmap.bitmapOf(3, 4, value(7, 9)));
    union.add(next);
    assertFalse(union.isPublished());
    assertEquals(snapshot, alias);
    assertValid(alias);
    MutableRoaringBitmap after = union.get();
    assertNotSame(alias, after);
    assertEquals(ImmutableRoaringBitmap.or(snapshot, next), after);
    assertValid(after);
    assertEquals(snapshot, alias);
  }

  @Test
  public void getIsCopyOnWriteForPointAdds() {
    MutableRoaringBitmapUnion union = new MutableRoaringBitmapUnion();
    union.add(toDirect(MutableRoaringBitmap.bitmapOf(1, 2, 3)));
    MutableRoaringBitmap alias = union.get();
    MutableRoaringBitmap snapshot = alias.clone();

    union.add(4);
    union.add(value(0xFFFF, 1));
    assertEquals(snapshot, alias);
    MutableRoaringBitmap after = union.get();
    assertNotSame(alias, after);
    assertEquals(MutableRoaringBitmap.bitmapOf(1, 2, 3, 4, value(0xFFFF, 1)), after);
    assertValid(after);
    // the new alias is published as well: the next mutation copies again and leaves it untouched
    union.add(5);
    MutableRoaringBitmap afterAgain = union.get();
    assertNotSame(after, afterAgain);
    assertEquals(MutableRoaringBitmap.bitmapOf(1, 2, 3, 4, value(0xFFFF, 1)), after);
    assertTrue(afterAgain.contains(5));
  }

  @Test
  public void takeDetachesTheResultAndResetsTheUnion() {
    MutableRoaringBitmapUnion union = new MutableRoaringBitmapUnion();
    union.add(toMapped(MutableRoaringBitmap.bitmapOf(1, 2, 3)));
    MutableRoaringBitmap taken = union.take();
    MutableRoaringBitmap snapshot = taken.clone();
    assertFalse(union.isDirty());
    assertFalse(union.isPublished());

    MutableRoaringBitmap empty = union.take();
    assertTrue(empty.isEmpty());
    assertNotSame(taken, empty);

    union.add(toDirect(MutableRoaringBitmap.bitmapOf(3, 4)));
    union.add(99);
    assertEquals(snapshot, taken);
    assertValid(taken);
    assertEquals(MutableRoaringBitmap.bitmapOf(3, 4, 99), union.get());
    assertEquals(snapshot, taken);
  }

  @Test
  public void takeOwnershipReturnsTheAdoptedInstance() {
    MutableRoaringBitmap owned = MutableRoaringBitmap.bitmapOf(1, 2, 3);
    MutableRoaringBitmapUnion union = MutableRoaringBitmapUnion.takeOwnership(owned);
    assertSame(owned, union.get());
    assertSame(owned, union.take());
    assertTrue(union.get().isEmpty());

    MutableRoaringBitmap owned2 = MutableRoaringBitmap.bitmapOf(1, 2, 3);
    MutableRoaringBitmapUnion union2 = MutableRoaringBitmapUnion.takeOwnership(owned2);
    union2.add(toMapped(MutableRoaringBitmap.bitmapOf(3, 4, value(2, 2))));
    union2.add(value(0x8000, 8));
    assertSame(owned2, union2.get()); // never published before, so mutated in place
    assertValid(owned2);
    assertEquals(MutableRoaringBitmap.bitmapOf(1, 2, 3, 4, value(2, 2), value(0x8000, 8)), owned2);
  }

  @Test
  public void takeOwnershipAdoptsReceiverConvertedFromMappedBitmap() {
    Random r = new Random(42);
    MutableRoaringBitmap source = randomBitmap(r);
    ImmutableRoaringBitmap mapped = toMapped(source);
    MutableRoaringBitmap receiver = mapped.toMutableRoaringBitmap();
    MutableRoaringBitmap expected = receiver.clone();
    MutableRoaringBitmapUnion union = MutableRoaringBitmapUnion.takeOwnership(receiver);
    for (int i = 0; i < 20; i++) {
      ImmutableRoaringBitmap in =
          i % 2 == 0 ? toMapped(randomBitmap(r)) : toDirect(randomBitmap(r));
      union.add(in);
      expected.or(in);
      int v = value(KEYS[r.nextInt(KEYS.length)], r.nextInt(65536));
      union.add(v);
      expected.add(v);
    }
    MutableRoaringBitmap result = union.get();
    assertSame(receiver, result);
    assertValid(result);
    assertEquals(expected, result);
    assertEquals(source, mapped); // the mapped source is untouched
  }

  @Test
  public void takeOwnershipRejectsSubclasses() {
    IllegalArgumentException e =
        assertThrows(
            IllegalArgumentException.class,
            () -> MutableRoaringBitmapUnion.takeOwnership(new CopyOnWriteRoaringBitmap()));
    assertTrue(e.getMessage().contains(CopyOnWriteRoaringBitmap.class.getName()), e.getMessage());
    // subclasses are fine as inputs: they are only read
    CopyOnWriteRoaringBitmap input = new CopyOnWriteRoaringBitmap();
    input.add(1);
    input.add(value(0xFFFF, 2));
    MutableRoaringBitmapUnion union = new MutableRoaringBitmapUnion();
    union.add(input);
    assertEquals(MutableRoaringBitmap.bitmapOf(1, value(0xFFFF, 2)), union.get());
  }

  @Test
  public void takeOwnershipNormalizesInefficientRunContainers() {
    // 300 isolated values encoded as 300 runs: 4 bytes per run versus 2 bytes per value as array
    MappeableContainer values = new MappeableArrayContainer();
    int[] expectedValues = new int[300];
    for (int i = 0; i < 300; i++) {
      values = values.add((char) (i * 3));
      expectedValues[i] = value(3, i * 3);
    }
    MappeableRunContainer runs =
        new MappeableRunContainer((MappeableArrayContainer) values, values.numberOfRuns());
    assertEquals(300, runs.numberOfRuns());
    MutableRoaringBitmap adopted = new MutableRoaringBitmap();
    adopted.getMappeableRoaringArray().append((char) 3, runs);
    // not canonical: 300 runs for 300 values
    long sizeBefore = adopted.getLongSizeInBytes();
    int serializedBefore = adopted.serializedSizeInBytes();

    MutableRoaringBitmap result = MutableRoaringBitmapUnion.takeOwnership(adopted).get();
    assertSame(adopted, result);
    assertValid(result);
    assertInstanceOf(
        MappeableArrayContainer.class, result.getMappeableRoaringArray().getContainerAtIndex(0));
    assertEquals(MutableRoaringBitmap.bitmapOf(expectedValues), result);
    assertTrue(result.getLongSizeInBytes() < sizeBefore);
    assertTrue(result.serializedSizeInBytes() < serializedBefore);
  }

  @Test
  public void addingOwnGetResultIsANoOp() {
    MutableRoaringBitmapUnion union = new MutableRoaringBitmapUnion();
    union.add(toMapped(MutableRoaringBitmap.bitmapOf(1, 2, 3)));
    MutableRoaringBitmap alias = union.get();
    MutableRoaringBitmap snapshot = alias.clone();
    assertFalse(union.isDirty());
    union.add(alias);
    assertFalse(union.isDirty());
    assertTrue(union.isPublished());
    assertSame(alias, union.get());
    assertEquals(snapshot, alias);
  }

  @Test
  public void addingEmptyBitmapDoesNotDirty() {
    MutableRoaringBitmapUnion union = new MutableRoaringBitmapUnion();
    union.add(new MutableRoaringBitmap());
    union.add(toMapped(new MutableRoaringBitmap()));
    assertFalse(union.isDirty());
    union.add(MutableRoaringBitmap.bitmapOf(5));
    assertTrue(union.isDirty());
    MutableRoaringBitmap alias = union.get();
    assertFalse(union.isDirty());
    union.add(toDirect(new MutableRoaringBitmap()));
    assertFalse(union.isDirty());
    assertSame(alias, union.get()); // no copy was needed either
    assertEquals(MutableRoaringBitmap.bitmapOf(5), alias);
  }

  @Test
  public void pointInsertIntoLazyBitmapContainerKeepsItLazyUntilRepair() {
    MutableRoaringBitmap receiver = new MutableRoaringBitmap();
    MutableRoaringBitmap odds = new MutableRoaringBitmap();
    for (int i = 0; i < 600; i++) {
      receiver.add(value(5, 2 * i));
      odds.add(value(5, 2 * i + 1));
    }
    ImmutableRoaringBitmap input = toMapped(odds);
    MutableRoaringBitmap expected = ImmutableRoaringBitmap.or(receiver, input);
    MutableRoaringBitmapUnion union = MutableRoaringBitmapUnion.takeOwnership(receiver);
    union.add(input);
    MappeableContainer c = receiver.getMappeableRoaringArray().getContainerAtIndex(0);
    // 1200 values exceed ARRAY_LAZY_LOWERBOUND: the lazy union promoted to a lazy bitmap
    assertInstanceOf(MappeableBitmapContainer.class, c);
    assertTrue(c.getCardinality() < 0, "precondition: the container is lazy");

    int[] points = {value(5, 1300), value(5, 0), value(5, 65535), value(5, 1201)};
    for (int p : points) {
      union.add(p);
      expected.add(p);
    }
    assertSame(c, receiver.getMappeableRoaringArray().getContainerAtIndex(0));
    assertTrue(c.getCardinality() < 0, "point inserts must not disturb the lazy marker");
    assertTrue(union.isDirty());

    MutableRoaringBitmap result = union.get();
    assertSame(receiver, result);
    assertValid(result);
    assertEquals(expected, result);
    assertEquals(1203, result.getCardinality());
    assertInstanceOf(
        MappeableArrayContainer.class, result.getMappeableRoaringArray().getContainerAtIndex(0));
    assertTrue(result.contains(value(5, 1300)));
    assertTrue(result.contains(value(5, 65535)));
  }

  @Test
  public void pointInsertCrossesArrayToBitmapThreshold() {
    MutableRoaringBitmapUnion union = new MutableRoaringBitmapUnion();
    MutableRoaringBitmap expected = new MutableRoaringBitmap();
    for (int i = 0; i < MappeableArrayContainer.DEFAULT_MAX_SIZE; i++) {
      union.add(value(2, i));
      expected.add(value(2, i));
    }
    assertFalse(union.isDirty());
    MutableRoaringBitmap atLimit = union.get();
    assertInstanceOf(
        MappeableArrayContainer.class, atLimit.getMappeableRoaringArray().getContainerAtIndex(0));
    assertEquals(MappeableArrayContainer.DEFAULT_MAX_SIZE, atLimit.getCardinality());

    union.add(value(2, 5)); // present value: nothing changes but a copy is taken
    MutableRoaringBitmap stillArray = union.get();
    assertNotSame(atLimit, stillArray);
    assertEquals(expected, stillArray);
    assertInstanceOf(
        MappeableArrayContainer.class,
        stillArray.getMappeableRoaringArray().getContainerAtIndex(0));

    union.add(value(2, MappeableArrayContainer.DEFAULT_MAX_SIZE));
    expected.add(value(2, MappeableArrayContainer.DEFAULT_MAX_SIZE));
    assertFalse(union.isDirty());
    MutableRoaringBitmap crossed = union.get();
    assertNotSame(stillArray, crossed);
    assertInstanceOf(
        MappeableBitmapContainer.class, crossed.getMappeableRoaringArray().getContainerAtIndex(0));
    assertEquals(expected, crossed);
    assertValid(crossed);
    // published aliases are untouched
    assertEquals(MappeableArrayContainer.DEFAULT_MAX_SIZE, atLimit.getCardinality());
    assertInstanceOf(
        MappeableArrayContainer.class, atLimit.getMappeableRoaringArray().getContainerAtIndex(0));
    assertInstanceOf(
        MappeableArrayContainer.class,
        stillArray.getMappeableRoaringArray().getContainerAtIndex(0));
  }

  @Test
  public void pointInsertIntoRunContainer() {
    MutableRoaringBitmap runs = new MutableRoaringBitmap();
    runs.add((long) value(9, 10), (long) value(9, 1000));
    runs.add((long) value(9, 2000), (long) value(9, 3000));
    assertTrue(runs.runOptimize());
    assertInstanceOf(
        MappeableRunContainer.class, runs.getMappeableRoaringArray().getContainerAtIndex(0));
    MutableRoaringBitmap expected = runs.clone();
    MutableRoaringBitmapUnion union = MutableRoaringBitmapUnion.takeOwnership(runs);

    int[] points = {
      value(9, 5), value(9, 1000), value(9, 500), value(9, 1500), value(9, 3000), value(9, 65535)
    };
    for (int p : points) {
      union.add(p);
      expected.add(p);
    }
    // a lazily unioned mapped array input keeps the receiver a run container; insert afterwards
    ImmutableRoaringBitmap arrayInput =
        toMapped(MutableRoaringBitmap.bitmapOf(value(9, 7), value(9, 4000), value(9, 4002)));
    union.add(arrayInput);
    expected.or(arrayInput);
    assertInstanceOf(
        MappeableRunContainer.class, runs.getMappeableRoaringArray().getContainerAtIndex(0));
    union.add(value(9, 4001));
    expected.add(value(9, 4001));

    MutableRoaringBitmap result = union.get();
    assertSame(runs, result);
    assertValid(result);
    assertEquals(expected, result);
    assertEquals(expected.getCardinality(), result.getCardinality());
  }

  @Test
  public void pointInsertWithNewHighKeys() {
    MutableRoaringBitmapUnion union = new MutableRoaringBitmapUnion();
    int[] values = {value(0xFFFF, 1), value(0x8000, 2), value(0, 3), -1, value(0x7FFF, 65535)};
    for (int v : values) {
      union.add(v);
    }
    assertFalse(union.isDirty());
    MutableRoaringBitmap result = union.get();
    assertValid(result);
    assertEquals(MutableRoaringBitmap.bitmapOf(values), result);
    assertEquals(values.length, result.getCardinality());
    assertEquals(4, result.getMappeableRoaringArray().size()); // keys 0, 0x7FFF, 0x8000, 0xFFFF
  }

  @Test
  public void nullArgumentsThrowWithParameterName() {
    MutableRoaringBitmapUnion union = new MutableRoaringBitmapUnion();
    NullPointerException input =
        assertThrows(NullPointerException.class, () -> union.add((ImmutableRoaringBitmap) null));
    assertEquals("input", input.getMessage());
    NullPointerException bitmap =
        assertThrows(
            NullPointerException.class, () -> MutableRoaringBitmapUnion.takeOwnership(null));
    assertEquals("bitmap", bitmap.getMessage());
  }
}
