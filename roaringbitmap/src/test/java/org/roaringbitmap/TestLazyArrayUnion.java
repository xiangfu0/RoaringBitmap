package org.roaringbitmap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Random;

/**
 * Tests the in-place array union behind {@link Container#lazyIOR(Container)}: when two array
 * containers combine to at most {@code ARRAY_LAZY_LOWERBOUND} values the receiver is merged into,
 * as {@link ArrayContainer#ior(ArrayContainer)} does, instead of being replaced by a new container.
 * Results, promotion thresholds and the non-mutating {@link Container#lazyOR(Container)} are
 * unchanged, and whole-bitmap lazy unions still agree with the eager ones.
 */
@Execution(ExecutionMode.CONCURRENT)
public class TestLazyArrayUnion {

  // ArrayContainer.ARRAY_LAZY_LOWERBOUND is private; mirror it here.
  private static final int LAZY_BOUND = 1024;

  private static char[] toChars(BitSet bits) {
    char[] values = new char[bits.cardinality()];
    int k = 0;
    for (int v = bits.nextSetBit(0); v >= 0; v = bits.nextSetBit(v + 1)) {
      values[k++] = (char) v;
    }
    return values;
  }

  private static char[] randomValues(Random random, int count) {
    BitSet chosen = new BitSet(1 << 16);
    int n = 0;
    while (n < count) {
      int v = random.nextInt(1 << 16);
      if (!chosen.get(v)) {
        chosen.set(v);
        n++;
      }
    }
    return toChars(chosen);
  }

  private static ArrayContainer arrayOf(char... values) {
    return new ArrayContainer(values.length, values);
  }

  private static ArrayContainer randomArray(Random random, int count) {
    return arrayOf(randomValues(random, count));
  }

  /** {@code count} values, the first {@code overlap} of which are taken from {@code left}. */
  private static ArrayContainer overlapping(
      Random random, ArrayContainer left, int count, int overlap) {
    BitSet inLeft = new BitSet(1 << 16);
    for (int k = 0; k < left.getCardinality(); k++) {
      inLeft.set(left.content[k]);
    }
    BitSet chosen = new BitSet(1 << 16);
    for (int k = 0; k < overlap; k++) {
      chosen.set(left.content[k]);
    }
    int n = overlap;
    while (n < count) {
      int v = random.nextInt(1 << 16);
      if (!inLeft.get(v) && !chosen.get(v)) {
        chosen.set(v);
        n++;
      }
    }
    return arrayOf(toChars(chosen));
  }

  private static void assertSameValues(Container expected, Container actual) {
    assertEquals(expected.getCardinality(), actual.getCardinality());
    PeekableCharIterator e = expected.getCharIterator();
    PeekableCharIterator a = actual.getCharIterator();
    while (e.hasNext()) {
      assertTrue(a.hasNext());
      assertEquals(e.next(), a.next());
    }
    assertFalse(a.hasNext());
  }

  @ParameterizedTest(name = "left={0} right={1} overlap={2}")
  @CsvSource({
    "0, 0, 0",
    "0, 5, 0",
    "5, 0, 0",
    "1, 1, 0",
    "1, 1, 1",
    "8, 8, 0",
    "8, 8, 4",
    "512, 512, 0", // exactly at the lazy bound: stays an array
    "512, 513, 0", // one past the bound: promoted to a lazy bitmap
    "1000, 24, 0",
    "1000, 25, 0",
    "1024, 0, 0",
    "0, 1024, 0",
    "1024, 1, 0",
    "700, 700, 700", // the bound counts duplicates: promoted although the union has 700 values
    "2048, 2048, 0", // union 4096: bitmap while lazy, array after repair
    "2048, 2049, 0", // union 4097: bitmap after repair
    "4090, 10, 0",
    "4096, 0, 0",
    "4096, 4096, 4096"
  })
  public void lazyIORMatchesPreviousBehaviour(int leftCard, int rightCard, int overlap) {
    Random random = new Random(31L * leftCard + 7L * rightCard + overlap);
    ArrayContainer left = randomArray(random, leftCard);
    ArrayContainer right = overlapping(random, left, rightCard, overlap);
    ArrayContainer rightSnapshot = right.clone();
    // lazyOR never touches its receiver and, for two arrays, computes what lazyIOR used to
    Container expectedLazy = left.clone().lazyOR(right);

    ArrayContainer receiver = left.clone();
    Container actualLazy = receiver.lazyIOR(right);

    assertEquals(expectedLazy.getClass(), actualLazy.getClass());
    assertEquals(expectedLazy.getCardinality(), actualLazy.getCardinality());
    assertEquals(rightSnapshot, right);
    if (leftCard + rightCard <= LAZY_BOUND) {
      assertSame(receiver, actualLazy);
      assertInstanceOf(ArrayContainer.class, actualLazy);
    } else {
      assertInstanceOf(BitmapContainer.class, actualLazy);
      assertEquals(-1, actualLazy.getCardinality());
    }

    Container expected = expectedLazy.repairAfterLazy();
    Container actual = actualLazy.repairAfterLazy();
    assertEquals(expected.getClass(), actual.getClass());
    assertSameValues(expected, actual);
    assertSameValues(left.clone().or(right), actual);
    int unionCardinality = leftCard + rightCard - overlap;
    assertEquals(unionCardinality, actual.getCardinality());
    assertEquals(
        unionCardinality <= ArrayContainer.DEFAULT_MAX_SIZE
            ? ArrayContainer.class
            : BitmapContainer.class,
        actual.getClass());
  }

  @Test
  public void mergesIntoSpareCapacityWithoutReallocating() {
    ArrayContainer receiver = new ArrayContainer(64);
    for (int v = 0; v < 16; v += 2) {
      receiver.add((char) v);
    }
    char[] before = receiver.content;
    Container result =
        receiver.lazyIOR(
            arrayOf(
                (char) 1, (char) 3, (char) 5, (char) 7, (char) 9, (char) 11, (char) 13, (char) 15));
    assertSame(receiver, result);
    assertSame(before, receiver.content);
    assertSameValues(new ArrayContainer(0, 16), result);
  }

  @Test
  public void singleValueInputIsInsertedInPlace() {
    ArrayContainer receiver = new ArrayContainer(64);
    for (int v = 0; v < 16; v += 2) {
      receiver.add((char) v);
    }
    char[] before = receiver.content;
    // a new value: inserted with add(), same container and same backing array
    Container result = receiver.lazyIOR(arrayOf((char) 7));
    assertSame(receiver, result);
    assertSame(before, receiver.content);
    assertSameValues(
        arrayOf(
            (char) 0, (char) 2, (char) 4, (char) 6, (char) 7, (char) 8, (char) 10, (char) 12,
            (char) 14),
        result);
    // a present value: nothing changes
    result = receiver.lazyIOR(arrayOf((char) 8));
    assertSame(receiver, result);
    assertSame(before, receiver.content);
    assertEquals(9, result.getCardinality());
    // the lazy bound still applies: 1023 + 1 stays an array, 1024 + 1 promotes to a lazy bitmap
    Container atBound = new ArrayContainer(0, 1023).lazyIOR(arrayOf((char) 5000));
    assertInstanceOf(ArrayContainer.class, atBound);
    assertEquals(1024, atBound.getCardinality());
    Container pastBound = new ArrayContainer(0, 1024).lazyIOR(arrayOf((char) 5000));
    assertInstanceOf(BitmapContainer.class, pastBound);
    assertEquals(1025, pastBound.repairAfterLazy().getCardinality());
  }

  @Test
  public void growsTheBackingArrayButKeepsTheContainer() {
    // capacity == cardinality, so the merge has to grow the array
    ArrayContainer receiver = new ArrayContainer(new char[] {0, 2, 4, 6});
    char[] before = receiver.content;
    Container result = receiver.lazyIOR(arrayOf((char) 1, (char) 3, (char) 5, (char) 7));
    assertSame(receiver, result);
    assertNotSame(before, receiver.content);
    assertSameValues(new ArrayContainer(0, 8), result);
  }

  @Test
  public void promotesOnlyPastTheLazyBound() {
    Random random = new Random(42);
    ArrayContainer atBound = randomArray(random, LAZY_BOUND / 2);
    ArrayContainer half = overlapping(random, atBound, LAZY_BOUND / 2, 0);
    Container stillArray = atBound.lazyIOR(half);
    assertSame(atBound, stillArray);
    assertEquals(LAZY_BOUND, stillArray.getCardinality());

    ArrayContainer pastBound = randomArray(random, LAZY_BOUND / 2);
    ArrayContainer halfPlusOne = overlapping(random, pastBound, LAZY_BOUND / 2 + 1, 0);
    Container lazyBitmap = pastBound.lazyIOR(halfPlusOne);
    assertInstanceOf(BitmapContainer.class, lazyBitmap);
    assertEquals(-1, lazyBitmap.getCardinality());
    Container repaired = lazyBitmap.repairAfterLazy();
    assertInstanceOf(ArrayContainer.class, repaired);
    assertEquals(LAZY_BOUND + 1, repaired.getCardinality());
  }

  @Test
  public void lazyORLeavesTheReceiverUntouched() {
    Random random = new Random(7);
    ArrayContainer left = randomArray(random, 100);
    ArrayContainer snapshot = left.clone();
    char[] before = left.content;
    ArrayContainer right = randomArray(random, 100);
    Container result = left.lazyOR(right);
    assertNotSame(left, result);
    assertSame(before, left.content);
    assertEquals(snapshot, left);
    assertSameValues(left.clone().or(right), result.repairAfterLazy());
  }

  @Test
  public void selfUnionIsSafe() {
    for (int cardinality : new int[] {0, 1, 8, 512, 513, 600}) {
      ArrayContainer container = randomArray(new Random(cardinality), cardinality);
      ArrayContainer snapshot = container.clone();
      assertSameValues(snapshot, container.lazyIOR(container).repairAfterLazy());
    }
    // with spare capacity the merge happens inside the shared backing array
    ArrayContainer spacious = new ArrayContainer(64);
    for (int v = 0; v < 16; v += 2) {
      spacious.add((char) v);
    }
    ArrayContainer snapshot = spacious.clone();
    Container result = spacious.lazyIOR(spacious);
    assertSame(spacious, result);
    assertSameValues(snapshot, result);
  }

  // ---- whole-bitmap folds through RoaringBitmap.lazyor ----

  private static char[] randomKeys(Random random, int count) {
    BitSet chosen = new BitSet(1 << 16);
    // always cover both ends of the key range and the unsigned boundary
    for (int key : new int[] {0, 1, 0x7FFF, 0x8000, 0xFFFE, 0xFFFF}) {
      chosen.set(key);
    }
    int n = chosen.cardinality();
    while (n < count) {
      int key = random.nextInt(1 << 16);
      if (!chosen.get(key)) {
        chosen.set(key);
        n++;
      }
    }
    return toChars(chosen);
  }

  private static RoaringBitmap bitmapWith(Random random, char[] keys, int valuesPerKey) {
    RoaringBitmap bitmap = new RoaringBitmap();
    for (char key : keys) {
      for (int i = 0; i < valuesPerKey; i++) {
        bitmap.add((key << 16) | random.nextInt(1 << 16));
      }
    }
    return bitmap;
  }

  private static List<RoaringBitmap> clones(List<RoaringBitmap> bitmaps) {
    List<RoaringBitmap> copies = new ArrayList<>(bitmaps.size());
    for (RoaringBitmap bitmap : bitmaps) {
      copies.add(bitmap.clone());
    }
    return copies;
  }

  private static RoaringBitmap eagerFold(RoaringBitmap first, List<RoaringBitmap> inputs) {
    RoaringBitmap accumulator = first.clone();
    for (RoaringBitmap input : inputs) {
      accumulator.or(input);
    }
    return accumulator;
  }

  private static RoaringBitmap roundTrip(RoaringBitmap bitmap) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    bitmap.serialize(new DataOutputStream(bytes));
    RoaringBitmap result = new RoaringBitmap();
    result.deserialize(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())));
    return result;
  }

  private static RoaringBitmap[] withFirst(RoaringBitmap first, List<RoaringBitmap> rest) {
    RoaringBitmap[] all = new RoaringBitmap[rest.size() + 1];
    all[0] = first;
    for (int i = 0; i < rest.size(); i++) {
      all[i + 1] = rest.get(i);
    }
    return all;
  }

  @Test
  public void lazyorFoldMergesInPlaceBelowTheBound() throws IOException {
    Random random = new Random(123);
    char[] keys = randomKeys(random, 64);
    RoaringBitmap receiver = bitmapWith(random, keys, 8);
    List<RoaringBitmap> inputs = new ArrayList<>();
    for (int i = 0; i < 60; i++) {
      inputs.add(bitmapWith(random, keys, 8)); // at most 488 values per key: arrays throughout
    }
    List<RoaringBitmap> snapshots = clones(inputs);
    RoaringBitmap expected = eagerFold(receiver, inputs);

    int size = receiver.highLowContainer.size();
    Container[] before = new Container[size];
    for (int i = 0; i < size; i++) {
      before[i] = receiver.highLowContainer.getContainerAtIndex(i);
    }

    for (RoaringBitmap input : inputs) {
      receiver.lazyor(input);
    }

    // every container was merged into, none was replaced or promoted
    assertEquals(size, receiver.highLowContainer.size());
    for (int i = 0; i < size; i++) {
      Container container = receiver.highLowContainer.getContainerAtIndex(i);
      assertSame(before[i], container);
      assertInstanceOf(ArrayContainer.class, container);
    }
    receiver.repairAfterLazy();
    assertEquals(expected, receiver);
    assertTrue(receiver.validate());
    assertEquals(expected, roundTrip(receiver));
    assertEquals(snapshots, inputs);
  }

  @Test
  public void lazyorFoldPromotesAndRepairsAcrossTheBound() throws IOException {
    Random random = new Random(321);
    char[] keys = randomKeys(random, 24);
    RoaringBitmap receiver = bitmapWith(random, keys, 8);
    RoaringBitmap original = receiver.clone();
    List<RoaringBitmap> inputs = new ArrayList<>();
    for (int i = 0; i < 200; i++) {
      inputs.add(bitmapWith(random, keys, 8)); // about 1600 values per key: promoted, then demoted
    }
    // keys the receiver does not have, a run container and a bitmap container
    inputs.add(bitmapWith(random, randomKeys(random, 40), 8));
    RoaringBitmap runs = new RoaringBitmap();
    long base = ((long) keys[3]) << 16;
    runs.add(base, base + 3000L);
    runs.runOptimize();
    inputs.add(runs);
    inputs.add(bitmapWith(random, new char[] {keys[5]}, 6000));
    List<RoaringBitmap> snapshots = clones(inputs);
    RoaringBitmap expected = eagerFold(receiver, inputs);

    for (RoaringBitmap input : inputs) {
      receiver.lazyor(input);
    }
    receiver.repairAfterLazy();

    assertEquals(expected, receiver);
    assertTrue(receiver.validate());
    assertEquals(expected, roundTrip(receiver));
    assertEquals(snapshots, inputs);

    RoaringBitmap[] all = withFirst(original, inputs);
    assertEquals(expected, FastAggregation.or(all));
    assertEquals(expected, FastAggregation.naive_or(all));
    assertEquals(expected, FastAggregation.priorityqueue_or(all));
    assertEquals(expected, ParallelAggregation.or(all));
    assertEquals(snapshots, inputs);
  }

  @Test
  public void aggregationsLeaveSmallArrayInputsUntouched() {
    Random random = new Random(99);
    char[] keys = randomKeys(random, 16);
    List<RoaringBitmap> inputs = new ArrayList<>();
    for (int i = 0; i < 20; i++) {
      inputs.add(bitmapWith(random, keys, 8));
    }
    List<RoaringBitmap> snapshots = clones(inputs);
    RoaringBitmap expected = eagerFold(new RoaringBitmap(), inputs);
    RoaringBitmap[] all = inputs.toArray(new RoaringBitmap[0]);

    assertEquals(expected, FastAggregation.or(all));
    assertEquals(expected, FastAggregation.naive_or(all));
    assertEquals(expected, FastAggregation.priorityqueue_or(all));
    assertEquals(expected, ParallelAggregation.or(all));
    assertTrue(expected.validate());
    assertEquals(snapshots, inputs);
  }
}
