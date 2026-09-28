package org.roaringbitmap;

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
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

@Execution(ExecutionMode.CONCURRENT)
public class TestRoaringBitmapUnion {

  /** High keys spanning the signed boundary (0x8000 and above are "negative" ints). */
  private static final int[] KEYS = {0, 1, 2, 7, 100, 0x7FFF, 0x8000, 0xFFFE, 0xFFFF};

  private static int value(int key, int low) {
    return (key << 16) | low;
  }

  private static long unsigned(int value) {
    return Integer.toUnsignedLong(value);
  }

  private static void addSparseArray(RoaringBitmap b, int key, Random r) {
    int n = 1 + r.nextInt(64);
    for (int i = 0; i < n; i++) {
      b.add(value(key, r.nextInt(65536)));
    }
  }

  /** Two of these in the same key exceed ARRAY_LAZY_LOWERBOUND (1024) and promote lazily. */
  private static void addDenseArray(RoaringBitmap b, int key, Random r) {
    int n = 700 + r.nextInt(300);
    for (int i = 0; i < n; i++) {
      b.add(value(key, r.nextInt(65536)));
    }
  }

  private static void addBitmap(RoaringBitmap b, int key, Random r) {
    int n = 5000 + r.nextInt(3000);
    for (int i = 0; i < n; i++) {
      b.add(value(key, r.nextInt(65536)));
    }
  }

  private static void addRun(RoaringBitmap b, int key, Random r) {
    int start = r.nextInt(60000);
    int length = 1 + r.nextInt(5000);
    b.add(unsigned(value(key, start)), unsigned(value(key, start)) + length);
  }

  static RoaringBitmap randomInput(Random r) {
    RoaringBitmap b = new RoaringBitmap();
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

  /** Valid, repaired and canonical: no lazy cardinality, no bitmap container at array size. */
  private static void assertValid(RoaringBitmap b) {
    assertTrue(b.validate());
    for (int k = 0; k < b.highLowContainer.size(); k++) {
      Container c = b.highLowContainer.getContainerAtIndex(k);
      assertTrue(c.getCardinality() >= 0, "lazy container escaped at index " + k);
      if (c instanceof BitmapContainer) {
        assertTrue(
            c.getCardinality() > ArrayContainer.DEFAULT_MAX_SIZE, "bitmap container at array size");
      }
    }
  }

  private static RoaringBitmap roundTrip(RoaringBitmap b) throws IOException {
    ByteArrayOutputStream bos = new ByteArrayOutputStream();
    b.serialize(new DataOutputStream(bos));
    RoaringBitmap copy = new RoaringBitmap();
    copy.deserialize(new DataInputStream(new ByteArrayInputStream(bos.toByteArray())));
    return copy;
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8})
  public void seededFoldsMatchEagerUnion(int seed) throws IOException {
    Random r = new Random(seed);
    List<RoaringBitmap> inputs = new ArrayList<>();
    List<RoaringBitmap> snapshots = new ArrayList<>();
    RoaringBitmap repeated = randomInput(r);
    for (int i = 0; i < 40; i++) {
      int choice = r.nextInt(10);
      RoaringBitmap in =
          choice == 0 ? new RoaringBitmap() : choice == 1 ? repeated : randomInput(r);
      inputs.add(in);
      snapshots.add(in.clone());
    }
    RoaringBitmapUnion union = new RoaringBitmapUnion();
    RoaringBitmap expected = new RoaringBitmap();
    for (int i = 0; i < inputs.size(); i++) {
      union.add(inputs.get(i));
      expected.or(inputs.get(i));
      if (i % 7 == 6) {
        RoaringBitmap partial = union.get();
        assertValid(partial);
        assertEquals(expected, partial);
      }
    }
    RoaringBitmap result = union.get();
    assertValid(result);
    assertEquals(expected, result);
    assertEquals(FastAggregation.or(inputs.toArray(new RoaringBitmap[0])), result);
    assertEquals(expected.getCardinality(), result.getCardinality());
    assertEquals(result, roundTrip(result));

    RoaringBitmap taken = union.take();
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
    for (int variant = 0; variant < 2; variant++) {
      boolean adopt = variant == 1;
      RoaringBitmap expected = new RoaringBitmap();
      RoaringBitmapUnion union;
      if (adopt) {
        RoaringBitmap initial = randomInput(r);
        expected.or(initial);
        union = RoaringBitmapUnion.takeOwnership(initial);
      } else {
        union = new RoaringBitmapUnion();
      }
      List<RoaringBitmap> published = new ArrayList<>();
      List<RoaringBitmap> publishedSnapshots = new ArrayList<>();
      for (int step = 0; step < 300; step++) {
        int op = r.nextInt(100);
        if (op < 40) {
          RoaringBitmap in = randomInput(r);
          union.add(in);
          expected.or(in);
        } else if (op < 80) {
          int v = value(KEYS[r.nextInt(KEYS.length)], r.nextInt(65536));
          union.add(v);
          expected.add(v);
        } else if (op < 95) {
          RoaringBitmap g = union.get();
          assertValid(g);
          assertEquals(expected, g);
          published.add(g);
          publishedSnapshots.add(g.clone());
        } else {
          RoaringBitmap t = union.take();
          assertValid(t);
          assertEquals(expected, t);
          published.add(t);
          publishedSnapshots.add(t.clone());
          expected = new RoaringBitmap();
        }
      }
      RoaringBitmap end = union.take();
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
    RoaringBitmapUnion union = new RoaringBitmapUnion();
    union.add(RoaringBitmap.bitmapOf(1, 2, 3));
    union.add(RoaringBitmap.bitmapOf(3, 4, value(0x8000, 5)));
    RoaringBitmap first = union.get();
    assertSame(first, union.get());
    assertSame(first, union.get());
    assertValid(first);
    assertEquals(RoaringBitmap.bitmapOf(1, 2, 3, 4, value(0x8000, 5)), first);
  }

  @Test
  public void getThenTakeReturnsTheSameInstance() {
    RoaringBitmapUnion union = new RoaringBitmapUnion();
    union.add(RoaringBitmap.bitmapOf(10, 20));
    RoaringBitmap got = union.get();
    assertSame(got, union.take());
    assertValid(got);
    assertTrue(union.get().isEmpty());
    assertNotSame(got, union.get());
  }

  @Test
  public void getIsCopyOnWriteForBitmapAdds() {
    RoaringBitmapUnion union = new RoaringBitmapUnion();
    union.add(RoaringBitmap.bitmapOf(1, 2, 3));
    RoaringBitmap alias = union.get();
    RoaringBitmap snapshot = alias.clone();
    assertTrue(union.isPublished());

    RoaringBitmap next = RoaringBitmap.bitmapOf(3, 4, value(7, 9));
    union.add(next);
    assertFalse(union.isPublished());
    assertEquals(snapshot, alias);
    assertValid(alias);
    RoaringBitmap after = union.get();
    assertNotSame(alias, after);
    assertEquals(RoaringBitmap.or(snapshot, next), after);
    assertValid(after);
    assertEquals(snapshot, alias);
  }

  @Test
  public void getIsCopyOnWriteForPointAdds() {
    RoaringBitmapUnion union = new RoaringBitmapUnion();
    union.add(RoaringBitmap.bitmapOf(1, 2, 3));
    RoaringBitmap alias = union.get();
    RoaringBitmap snapshot = alias.clone();

    union.add(4);
    union.add(value(0xFFFF, 1));
    assertEquals(snapshot, alias);
    RoaringBitmap after = union.get();
    assertNotSame(alias, after);
    assertEquals(RoaringBitmap.bitmapOf(1, 2, 3, 4, value(0xFFFF, 1)), after);
    assertValid(after);
    // the new alias is published as well: the next mutation copies again and leaves it untouched
    union.add(5);
    RoaringBitmap afterAgain = union.get();
    assertNotSame(after, afterAgain);
    assertEquals(RoaringBitmap.bitmapOf(1, 2, 3, 4, value(0xFFFF, 1)), after);
    assertTrue(afterAgain.contains(5));
  }

  @Test
  public void takeDetachesTheResultAndResetsTheUnion() {
    RoaringBitmapUnion union = new RoaringBitmapUnion();
    union.add(RoaringBitmap.bitmapOf(1, 2, 3));
    RoaringBitmap taken = union.take();
    RoaringBitmap snapshot = taken.clone();
    assertFalse(union.isDirty());
    assertFalse(union.isPublished());

    RoaringBitmap empty = union.take();
    assertTrue(empty.isEmpty());
    assertNotSame(taken, empty);

    union.add(RoaringBitmap.bitmapOf(3, 4));
    union.add(99);
    assertEquals(snapshot, taken);
    assertValid(taken);
    assertEquals(RoaringBitmap.bitmapOf(3, 4, 99), union.get());
    assertEquals(snapshot, taken);
  }

  @Test
  public void takeOwnershipReturnsTheAdoptedInstance() {
    RoaringBitmap owned = RoaringBitmap.bitmapOf(1, 2, 3);
    RoaringBitmapUnion union = RoaringBitmapUnion.takeOwnership(owned);
    assertSame(owned, union.get());
    assertSame(owned, union.take());
    assertTrue(union.get().isEmpty());

    RoaringBitmap owned2 = RoaringBitmap.bitmapOf(1, 2, 3);
    RoaringBitmapUnion union2 = RoaringBitmapUnion.takeOwnership(owned2);
    union2.add(RoaringBitmap.bitmapOf(3, 4, value(2, 2)));
    union2.add(value(0x8000, 8));
    assertSame(owned2, union2.get()); // never published before, so mutated in place
    assertValid(owned2);
    assertEquals(RoaringBitmap.bitmapOf(1, 2, 3, 4, value(2, 2), value(0x8000, 8)), owned2);
  }

  @Test
  public void takeOwnershipRejectsSubclasses() {
    IllegalArgumentException e =
        assertThrows(
            IllegalArgumentException.class,
            () -> RoaringBitmapUnion.takeOwnership(new FastRankRoaringBitmap()));
    assertTrue(e.getMessage().contains(FastRankRoaringBitmap.class.getName()), e.getMessage());
    // subclasses are fine as inputs: they are only read
    FastRankRoaringBitmap input = new FastRankRoaringBitmap();
    input.add(1);
    input.add(value(0xFFFF, 2));
    RoaringBitmapUnion union = new RoaringBitmapUnion();
    union.add(input);
    assertEquals(RoaringBitmap.bitmapOf(1, value(0xFFFF, 2)), union.get());
  }

  @Test
  public void takeOwnershipNormalizesInefficientRunContainers() {
    // 300 isolated values encoded as 300 runs: 4 bytes per run versus 2 bytes per value as array
    Container values = new ArrayContainer();
    int[] expectedValues = new int[300];
    for (int i = 0; i < 300; i++) {
      values = values.add((char) (i * 3));
      expectedValues[i] = value(3, i * 3);
    }
    RunContainer runs = new RunContainer((ArrayContainer) values, values.numberOfRuns());
    assertEquals(300, runs.numberOfRuns());
    RoaringBitmap adopted = new RoaringBitmap();
    adopted.highLowContainer.append((char) 3, runs);
    // not canonical: validate() even rejects run containers that are not the most compact encoding
    long sizeBefore = adopted.getLongSizeInBytes();
    int serializedBefore = adopted.serializedSizeInBytes();

    RoaringBitmap result = RoaringBitmapUnion.takeOwnership(adopted).get();
    assertSame(adopted, result);
    assertValid(result);
    assertInstanceOf(ArrayContainer.class, result.highLowContainer.getContainerAtIndex(0));
    assertEquals(RoaringBitmap.bitmapOf(expectedValues), result);
    assertTrue(result.getLongSizeInBytes() < sizeBefore);
    assertTrue(result.serializedSizeInBytes() < serializedBefore);
  }

  @Test
  public void addingOwnGetResultIsANoOp() {
    RoaringBitmapUnion union = new RoaringBitmapUnion();
    union.add(RoaringBitmap.bitmapOf(1, 2, 3));
    RoaringBitmap alias = union.get();
    RoaringBitmap snapshot = alias.clone();
    assertFalse(union.isDirty());
    union.add(alias);
    assertFalse(union.isDirty());
    assertTrue(union.isPublished());
    assertSame(alias, union.get());
    assertEquals(snapshot, alias);
  }

  @Test
  public void addingEmptyBitmapDoesNotDirty() {
    RoaringBitmapUnion union = new RoaringBitmapUnion();
    union.add(new RoaringBitmap());
    assertFalse(union.isDirty());
    union.add(RoaringBitmap.bitmapOf(5));
    assertTrue(union.isDirty());
    RoaringBitmap alias = union.get();
    assertFalse(union.isDirty());
    union.add(new RoaringBitmap());
    assertFalse(union.isDirty());
    assertSame(alias, union.get()); // no copy was needed either
    assertEquals(RoaringBitmap.bitmapOf(5), alias);
  }

  @Test
  public void pointInsertIntoLazyBitmapContainerKeepsItLazyUntilRepair() {
    RoaringBitmap receiver = new RoaringBitmap();
    RoaringBitmap input = new RoaringBitmap();
    for (int i = 0; i < 600; i++) {
      receiver.add(value(5, 2 * i));
      input.add(value(5, 2 * i + 1));
    }
    RoaringBitmap expected = RoaringBitmap.or(receiver, input);
    RoaringBitmapUnion union = RoaringBitmapUnion.takeOwnership(receiver);
    union.add(input);
    Container c = receiver.highLowContainer.getContainerAtIndex(0);
    // 1200 values exceed ARRAY_LAZY_LOWERBOUND: the lazy union promoted to a lazy bitmap
    assertInstanceOf(BitmapContainer.class, c);
    assertTrue(c.getCardinality() < 0, "precondition: the container is lazy");

    int[] points = {value(5, 1300), value(5, 0), value(5, 65535), value(5, 1201)};
    for (int p : points) {
      union.add(p);
      expected.add(p);
    }
    assertSame(c, receiver.highLowContainer.getContainerAtIndex(0));
    assertTrue(c.getCardinality() < 0, "point inserts must not disturb the lazy marker");
    assertTrue(union.isDirty());

    RoaringBitmap result = union.get();
    assertSame(receiver, result);
    assertValid(result);
    assertEquals(expected, result);
    assertEquals(1203, result.getCardinality());
    assertInstanceOf(ArrayContainer.class, result.highLowContainer.getContainerAtIndex(0));
    assertTrue(result.contains(value(5, 1300)));
    assertTrue(result.contains(value(5, 65535)));
  }

  @Test
  public void pointInsertCrossesArrayToBitmapThreshold() {
    RoaringBitmapUnion union = new RoaringBitmapUnion();
    RoaringBitmap expected = new RoaringBitmap();
    for (int i = 0; i < ArrayContainer.DEFAULT_MAX_SIZE; i++) {
      union.add(value(2, i));
      expected.add(value(2, i));
    }
    assertFalse(union.isDirty());
    RoaringBitmap atLimit = union.get();
    assertInstanceOf(ArrayContainer.class, atLimit.highLowContainer.getContainerAtIndex(0));
    assertEquals(ArrayContainer.DEFAULT_MAX_SIZE, atLimit.getCardinality());

    union.add(value(2, 5)); // present value: nothing changes but a copy is taken
    RoaringBitmap stillArray = union.get();
    assertNotSame(atLimit, stillArray);
    assertEquals(expected, stillArray);
    assertInstanceOf(ArrayContainer.class, stillArray.highLowContainer.getContainerAtIndex(0));

    union.add(value(2, ArrayContainer.DEFAULT_MAX_SIZE));
    expected.add(value(2, ArrayContainer.DEFAULT_MAX_SIZE));
    assertFalse(union.isDirty());
    RoaringBitmap crossed = union.get();
    assertNotSame(stillArray, crossed);
    assertInstanceOf(BitmapContainer.class, crossed.highLowContainer.getContainerAtIndex(0));
    assertEquals(expected, crossed);
    assertValid(crossed);
    // published aliases are untouched
    assertEquals(ArrayContainer.DEFAULT_MAX_SIZE, atLimit.getCardinality());
    assertInstanceOf(ArrayContainer.class, atLimit.highLowContainer.getContainerAtIndex(0));
    assertInstanceOf(ArrayContainer.class, stillArray.highLowContainer.getContainerAtIndex(0));
  }

  @Test
  public void pointInsertIntoRunContainer() {
    RoaringBitmap runs = new RoaringBitmap();
    runs.add((long) value(9, 10), (long) value(9, 1000));
    runs.add((long) value(9, 2000), (long) value(9, 3000));
    assertTrue(runs.runOptimize());
    assertInstanceOf(RunContainer.class, runs.highLowContainer.getContainerAtIndex(0));
    RoaringBitmap expected = runs.clone();
    RoaringBitmapUnion union = RoaringBitmapUnion.takeOwnership(runs);

    int[] points = {
      value(9, 5), value(9, 1000), value(9, 500), value(9, 1500), value(9, 3000), value(9, 65535)
    };
    for (int p : points) {
      union.add(p);
      expected.add(p);
    }
    // a lazily unioned array input keeps the receiver a run container; insert again afterwards
    RoaringBitmap arrayInput = RoaringBitmap.bitmapOf(value(9, 7), value(9, 4000), value(9, 4002));
    union.add(arrayInput);
    expected.or(arrayInput);
    assertInstanceOf(RunContainer.class, runs.highLowContainer.getContainerAtIndex(0));
    union.add(value(9, 4001));
    expected.add(value(9, 4001));

    RoaringBitmap result = union.get();
    assertSame(runs, result);
    assertValid(result);
    assertEquals(expected, result);
    assertEquals(expected.getCardinality(), result.getCardinality());
  }

  @Test
  public void pointInsertWithNewHighKeys() {
    RoaringBitmapUnion union = new RoaringBitmapUnion();
    int[] values = {value(0xFFFF, 1), value(0x8000, 2), value(0, 3), -1, value(0x7FFF, 65535)};
    for (int v : values) {
      union.add(v);
    }
    assertFalse(union.isDirty());
    RoaringBitmap result = union.get();
    assertValid(result);
    assertEquals(RoaringBitmap.bitmapOf(values), result);
    assertEquals(values.length, result.getCardinality());
    assertEquals(4, result.highLowContainer.size()); // keys 0, 0x7FFF, 0x8000, 0xFFFF (-1 too)
  }

  @Test
  public void nullArgumentsThrowWithParameterName() {
    RoaringBitmapUnion union = new RoaringBitmapUnion();
    NullPointerException input =
        assertThrows(NullPointerException.class, () -> union.add((RoaringBitmap) null));
    assertEquals("input", input.getMessage());
    NullPointerException bitmap =
        assertThrows(NullPointerException.class, () -> RoaringBitmapUnion.takeOwnership(null));
    assertEquals("bitmap", bitmap.getMessage());
  }
}
