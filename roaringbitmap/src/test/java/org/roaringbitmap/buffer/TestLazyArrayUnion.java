package org.roaringbitmap.buffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.roaringbitmap.ArrayContainer;
import org.roaringbitmap.PeekableCharIterator;

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
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;
import java.util.Random;

/**
 * Buffer twin of {@code org.roaringbitmap.TestLazyArrayUnion}: the array arm of {@link
 * MappeableContainer#lazyIOR(MappeableContainer)} merges into a heap-backed receiver in place, a
 * mapped receiver still gets a fresh container, inputs (heap, read-only mapped and direct) are
 * never modified, and whole-bitmap lazy unions agree with the eager ones.
 */
@Execution(ExecutionMode.CONCURRENT)
public class TestLazyArrayUnion {

  // MappeableArrayContainer.ARRAY_LAZY_LOWERBOUND is private; mirror it here.
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

  /** Heap-backed container that owns a copy of {@code values}. */
  private static MappeableArrayContainer arrayOf(char... values) {
    return new MappeableArrayContainer(new ArrayContainer(values.length, values));
  }

  /** Container over a read-only view of {@code values}, as a memory-mapped one would be. */
  private static MappeableArrayContainer readOnlyArrayOf(char... values) {
    return new MappeableArrayContainer(CharBuffer.wrap(values).asReadOnlyBuffer(), values.length);
  }

  /** Container over a direct buffer holding {@code values}. */
  private static MappeableArrayContainer directArrayOf(char... values) {
    CharBuffer direct = ByteBuffer.allocateDirect(2 * values.length).asCharBuffer();
    direct.put(values);
    direct.rewind();
    return new MappeableArrayContainer(direct, values.length);
  }

  private static MappeableArrayContainer randomArray(Random random, int count) {
    return arrayOf(randomValues(random, count));
  }

  private static char[] valuesOf(MappeableArrayContainer container) {
    char[] values = new char[container.getCardinality()];
    for (int k = 0; k < values.length; k++) {
      values[k] = container.content.get(k);
    }
    return values;
  }

  /** {@code count} values, the first {@code overlap} of which are taken from {@code left}. */
  private static char[] overlapping(
      Random random, MappeableArrayContainer left, int count, int overlap) {
    char[] leftValues = valuesOf(left);
    BitSet inLeft = new BitSet(1 << 16);
    for (char v : leftValues) {
      inLeft.set(v);
    }
    BitSet chosen = new BitSet(1 << 16);
    for (int k = 0; k < overlap; k++) {
      chosen.set(leftValues[k]);
    }
    int n = overlap;
    while (n < count) {
      int v = random.nextInt(1 << 16);
      if (!inLeft.get(v) && !chosen.get(v)) {
        chosen.set(v);
        n++;
      }
    }
    return toChars(chosen);
  }

  private static void assertSameValues(MappeableContainer expected, MappeableContainer actual) {
    assertEquals(expected.getCardinality(), actual.getCardinality());
    PeekableCharIterator e = expected.getCharIterator();
    PeekableCharIterator a = actual.getCharIterator();
    while (e.hasNext()) {
      assertTrue(a.hasNext());
      assertEquals(e.next(), a.next());
    }
    assertFalse(a.hasNext());
  }

  @ParameterizedTest(name = "left={0} right={1} overlap={2} input={3}")
  @CsvSource({
    "0, 0, 0, heap",
    "0, 5, 0, heap",
    "5, 0, 0, direct",
    "1, 1, 0, heap",
    "1, 1, 1, readOnly",
    "8, 8, 0, heap",
    "8, 8, 4, direct",
    "512, 512, 0, heap", // exactly at the lazy bound: stays an array
    "512, 512, 0, readOnly",
    "512, 512, 0, direct",
    "512, 513, 0, heap", // one past the bound: promoted to a lazy bitmap
    "512, 513, 0, direct",
    "1000, 24, 0, readOnly",
    "1000, 25, 0, heap",
    "1024, 0, 0, heap",
    "0, 1024, 0, direct",
    "1024, 1, 0, readOnly",
    "700, 700, 700, heap", // the bound counts duplicates
    "2048, 2048, 0, heap", // union 4096: bitmap while lazy, array after repair
    "2048, 2049, 0, readOnly", // union 4097: bitmap after repair
    "4090, 10, 0, direct",
    "4096, 0, 0, heap",
    "4096, 4096, 4096, heap"
  })
  public void lazyIORMatchesPreviousBehaviour(
      int leftCard, int rightCard, int overlap, String inputKind) {
    Random random = new Random(31L * leftCard + 7L * rightCard + overlap);
    MappeableArrayContainer left = randomArray(random, leftCard);
    char[] rightValues = overlapping(random, left, rightCard, overlap);
    MappeableArrayContainer right;
    if ("readOnly".equals(inputKind)) {
      right = readOnlyArrayOf(rightValues);
    } else if ("direct".equals(inputKind)) {
      right = directArrayOf(rightValues);
    } else {
      right = arrayOf(rightValues);
    }
    MappeableArrayContainer rightSnapshot = arrayOf(rightValues);
    // lazyOR never touches its receiver and, for two arrays, computes what lazyIOR used to
    MappeableContainer expectedLazy = left.clone().lazyOR(right);

    MappeableArrayContainer receiver = left.clone();
    MappeableContainer actualLazy = receiver.lazyIOR(right);

    assertEquals(expectedLazy.getClass(), actualLazy.getClass());
    assertEquals(expectedLazy.getCardinality(), actualLazy.getCardinality());
    assertEquals(rightSnapshot, right);
    if (leftCard + rightCard <= LAZY_BOUND) {
      assertSame(receiver, actualLazy);
      assertInstanceOf(MappeableArrayContainer.class, actualLazy);
    } else {
      assertInstanceOf(MappeableBitmapContainer.class, actualLazy);
      assertEquals(-1, actualLazy.getCardinality());
    }

    MappeableContainer expected = expectedLazy.repairAfterLazy();
    MappeableContainer actual = actualLazy.repairAfterLazy();
    assertEquals(expected.getClass(), actual.getClass());
    assertSameValues(expected, actual);
    assertSameValues(left.clone().or(right), actual);
    int unionCardinality = leftCard + rightCard - overlap;
    assertEquals(unionCardinality, actual.getCardinality());
    assertEquals(
        unionCardinality <= MappeableArrayContainer.DEFAULT_MAX_SIZE
            ? MappeableArrayContainer.class
            : MappeableBitmapContainer.class,
        actual.getClass());
  }

  @Test
  public void mergesIntoSpareCapacityWithoutReallocating() {
    MappeableArrayContainer receiver = new MappeableArrayContainer(64);
    for (int v = 0; v < 16; v += 2) {
      receiver.add((char) v);
    }
    CharBuffer before = receiver.content;
    MappeableContainer result =
        receiver.lazyIOR(
            arrayOf(
                (char) 1, (char) 3, (char) 5, (char) 7, (char) 9, (char) 11, (char) 13, (char) 15));
    assertSame(receiver, result);
    assertSame(before, receiver.content);
    assertSameValues(new MappeableArrayContainer(0, 16), result);
  }

  @Test
  public void singleValueInputIsInsertedInPlace() {
    MappeableArrayContainer receiver = new MappeableArrayContainer(64);
    for (int v = 0; v < 16; v += 2) {
      receiver.add((char) v);
    }
    CharBuffer before = receiver.content;
    // a new value (from a read-only mapped input): inserted with add(), same container and buffer
    MappeableContainer result = receiver.lazyIOR(readOnlyArrayOf((char) 7));
    assertSame(receiver, result);
    assertSame(before, receiver.content);
    assertSameValues(
        arrayOf(
            (char) 0, (char) 2, (char) 4, (char) 6, (char) 7, (char) 8, (char) 10, (char) 12,
            (char) 14),
        result);
    // a present value: nothing changes
    result = receiver.lazyIOR(directArrayOf((char) 8));
    assertSame(receiver, result);
    assertSame(before, receiver.content);
    assertEquals(9, result.getCardinality());
    // a mapped receiver is not merged into, even for a single value
    MappeableArrayContainer mapped = readOnlyArrayOf((char) 1, (char) 3);
    MappeableContainer fresh = mapped.lazyIOR(arrayOf((char) 2));
    assertNotSame(mapped, fresh);
    assertSameValues(arrayOf((char) 1, (char) 2, (char) 3), fresh);
    assertSameValues(arrayOf((char) 1, (char) 3), mapped);
    // the lazy bound still applies: 1023 + 1 stays an array, 1024 + 1 promotes to a lazy bitmap
    MappeableContainer atBound = new MappeableArrayContainer(0, 1023).lazyIOR(arrayOf((char) 5000));
    assertInstanceOf(MappeableArrayContainer.class, atBound);
    assertEquals(1024, atBound.getCardinality());
    MappeableContainer pastBound =
        new MappeableArrayContainer(0, 1024).lazyIOR(arrayOf((char) 5000));
    assertInstanceOf(MappeableBitmapContainer.class, pastBound);
    assertEquals(1025, pastBound.repairAfterLazy().getCardinality());
  }

  @Test
  public void growsTheBackingBufferButKeepsTheContainer() {
    // capacity == cardinality, so the merge has to grow the buffer
    MappeableArrayContainer receiver = arrayOf((char) 0, (char) 2, (char) 4, (char) 6);
    CharBuffer before = receiver.content;
    MappeableContainer result = receiver.lazyIOR(arrayOf((char) 1, (char) 3, (char) 5, (char) 7));
    assertSame(receiver, result);
    assertNotSame(before, receiver.content);
    assertSameValues(new MappeableArrayContainer(0, 8), result);
  }

  @Test
  public void mappedReceiverStillGetsAFreshContainer() {
    char[] even = {0, 2, 4, 6, 8, 10, 12, 14};
    char[] odd = {1, 3, 5, 7, 9, 11, 13, 15};
    MappeableArrayContainer[] mappedReceivers = {readOnlyArrayOf(even), directArrayOf(even)};
    for (MappeableArrayContainer receiver : mappedReceivers) {
      CharBuffer before = receiver.content;
      MappeableContainer result = receiver.lazyIOR(arrayOf(odd));
      assertNotSame(receiver, result);
      assertInstanceOf(MappeableArrayContainer.class, result);
      assertSameValues(new MappeableArrayContainer(0, 16), result);
      // the mapped receiver is left exactly as it was
      assertSame(before, receiver.content);
      assertSameValues(arrayOf(even), receiver);
    }
  }

  @Test
  public void promotesOnlyPastTheLazyBound() {
    Random random = new Random(42);
    MappeableArrayContainer atBound = randomArray(random, LAZY_BOUND / 2);
    MappeableArrayContainer half = arrayOf(overlapping(random, atBound, LAZY_BOUND / 2, 0));
    MappeableContainer stillArray = atBound.lazyIOR(half);
    assertSame(atBound, stillArray);
    assertEquals(LAZY_BOUND, stillArray.getCardinality());

    MappeableArrayContainer pastBound = randomArray(random, LAZY_BOUND / 2);
    MappeableArrayContainer halfPlusOne =
        arrayOf(overlapping(random, pastBound, LAZY_BOUND / 2 + 1, 0));
    MappeableContainer lazyBitmap = pastBound.lazyIOR(halfPlusOne);
    assertInstanceOf(MappeableBitmapContainer.class, lazyBitmap);
    assertEquals(-1, lazyBitmap.getCardinality());
    MappeableContainer repaired = lazyBitmap.repairAfterLazy();
    assertInstanceOf(MappeableArrayContainer.class, repaired);
    assertEquals(LAZY_BOUND + 1, repaired.getCardinality());
  }

  @Test
  public void lazyORLeavesTheReceiverUntouched() {
    Random random = new Random(7);
    MappeableArrayContainer left = randomArray(random, 100);
    MappeableArrayContainer snapshot = left.clone();
    CharBuffer before = left.content;
    MappeableArrayContainer right = randomArray(random, 100);
    MappeableContainer result = left.lazyOR(right);
    assertNotSame(left, result);
    assertSame(before, left.content);
    assertEquals(snapshot, left);
    assertSameValues(left.clone().or(right), result.repairAfterLazy());
  }

  @Test
  public void selfUnionIsSafe() {
    for (int cardinality : new int[] {0, 1, 8, 512, 513, 600}) {
      MappeableArrayContainer container = randomArray(new Random(cardinality), cardinality);
      MappeableArrayContainer snapshot = container.clone();
      assertSameValues(snapshot, container.lazyIOR(container).repairAfterLazy());
    }
    // with spare capacity the merge happens inside the shared backing buffer
    MappeableArrayContainer spacious = new MappeableArrayContainer(64);
    for (int v = 0; v < 16; v += 2) {
      spacious.add((char) v);
    }
    MappeableArrayContainer snapshot = spacious.clone();
    MappeableContainer result = spacious.lazyIOR(spacious);
    assertSame(spacious, result);
    assertSameValues(snapshot, result);
  }

  // ---- whole-bitmap folds through MutableRoaringBitmap.lazyor ----

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

  private static MutableRoaringBitmap bitmapWith(Random random, char[] keys, int valuesPerKey) {
    MutableRoaringBitmap bitmap = new MutableRoaringBitmap();
    for (char key : keys) {
      for (int i = 0; i < valuesPerKey; i++) {
        bitmap.add((key << 16) | random.nextInt(1 << 16));
      }
    }
    return bitmap;
  }

  /** A view over the serialized form, read-only on the heap or in a direct buffer. */
  private static ImmutableRoaringBitmap mapped(ImmutableRoaringBitmap bitmap, boolean direct) {
    int size = bitmap.serializedSizeInBytes();
    ByteBuffer buffer = direct ? ByteBuffer.allocateDirect(size) : ByteBuffer.allocate(size);
    bitmap.serialize(buffer);
    buffer.flip();
    return new ImmutableRoaringBitmap(direct ? buffer : buffer.asReadOnlyBuffer());
  }

  /** Alternates heap, read-only mapped and direct mapped inputs. */
  private static ImmutableRoaringBitmap flavoured(MutableRoaringBitmap bitmap, int index) {
    switch (index % 3) {
      case 0:
        return bitmap;
      case 1:
        return mapped(bitmap, false);
      default:
        return mapped(bitmap, true);
    }
  }

  private static List<MutableRoaringBitmap> snapshots(List<ImmutableRoaringBitmap> bitmaps) {
    List<MutableRoaringBitmap> copies = new ArrayList<>(bitmaps.size());
    for (ImmutableRoaringBitmap bitmap : bitmaps) {
      copies.add(bitmap.toMutableRoaringBitmap());
    }
    return copies;
  }

  private static MutableRoaringBitmap eagerFold(
      ImmutableRoaringBitmap first, List<ImmutableRoaringBitmap> inputs) {
    MutableRoaringBitmap accumulator = first.toMutableRoaringBitmap();
    for (ImmutableRoaringBitmap input : inputs) {
      accumulator.or(input);
    }
    return accumulator;
  }

  private static MutableRoaringBitmap roundTrip(MutableRoaringBitmap bitmap) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    bitmap.serialize(new DataOutputStream(bytes));
    MutableRoaringBitmap result = new MutableRoaringBitmap();
    result.deserialize(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())));
    return result;
  }

  private static ImmutableRoaringBitmap[] withFirst(
      ImmutableRoaringBitmap first, List<ImmutableRoaringBitmap> rest) {
    ImmutableRoaringBitmap[] all = new ImmutableRoaringBitmap[rest.size() + 1];
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
    // the receiver is adopted from a mapped bitmap, as a reader would do
    MutableRoaringBitmap receiver =
        mapped(bitmapWith(random, keys, 8), false).toMutableRoaringBitmap();
    List<ImmutableRoaringBitmap> inputs = new ArrayList<>();
    for (int i = 0; i < 60; i++) {
      inputs.add(flavoured(bitmapWith(random, keys, 8), i)); // at most 488 values per key
    }
    List<MutableRoaringBitmap> snapshots = snapshots(inputs);
    MutableRoaringBitmap expected = eagerFold(receiver, inputs);

    MutableRoaringArray array = receiver.getMappeableRoaringArray();
    int size = array.size();
    MappeableContainer[] before = new MappeableContainer[size];
    for (int i = 0; i < size; i++) {
      before[i] = array.getContainerAtIndex(i);
    }

    for (ImmutableRoaringBitmap input : inputs) {
      receiver.lazyor(input);
    }

    // every container was merged into, none was replaced or promoted
    assertEquals(size, array.size());
    for (int i = 0; i < size; i++) {
      MappeableContainer container = array.getContainerAtIndex(i);
      assertSame(before[i], container);
      assertInstanceOf(MappeableArrayContainer.class, container);
    }
    receiver.repairAfterLazy();
    assertEquals(expected, receiver);
    assertTrue(receiver.validate());
    assertEquals(expected, roundTrip(receiver));
    assertEquals(snapshots, snapshots(inputs));
  }

  @Test
  public void lazyorFoldPromotesAndRepairsAcrossTheBound() throws IOException {
    Random random = new Random(321);
    char[] keys = randomKeys(random, 24);
    MutableRoaringBitmap receiver = bitmapWith(random, keys, 8);
    MutableRoaringBitmap original = receiver.clone();
    List<ImmutableRoaringBitmap> inputs = new ArrayList<>();
    for (int i = 0; i < 200; i++) {
      inputs.add(flavoured(bitmapWith(random, keys, 8), i)); // about 1600 values per key
    }
    // keys the receiver does not have, a run container and a bitmap container
    inputs.add(mapped(bitmapWith(random, randomKeys(random, 40), 8), true));
    MutableRoaringBitmap runs = new MutableRoaringBitmap();
    long base = ((long) keys[3]) << 16;
    runs.add(base, base + 3000L);
    runs.runOptimize();
    inputs.add(mapped(runs, false));
    inputs.add(bitmapWith(random, new char[] {keys[5]}, 6000));
    List<MutableRoaringBitmap> snapshots = snapshots(inputs);
    MutableRoaringBitmap expected = eagerFold(receiver, inputs);

    for (ImmutableRoaringBitmap input : inputs) {
      receiver.lazyor(input);
    }
    receiver.repairAfterLazy();

    assertEquals(expected, receiver);
    assertTrue(receiver.validate());
    assertEquals(expected, roundTrip(receiver));
    assertEquals(snapshots, snapshots(inputs));

    ImmutableRoaringBitmap[] all = withFirst(original, inputs);
    assertEquals(expected, BufferFastAggregation.or(all));
    assertEquals(expected, BufferFastAggregation.naive_or(all));
    assertEquals(expected, BufferFastAggregation.priorityqueue_or(all));
    // the MutableRoaringBitmap overloads are the ones backed by lazyor, whose array arm changed
    MutableRoaringBitmap[] mutable = new MutableRoaringBitmap[all.length];
    for (int i = 0; i < all.length; i++) {
      mutable[i] = all[i].toMutableRoaringBitmap();
    }
    List<MutableRoaringBitmap> mutableSnapshots = new ArrayList<>();
    for (MutableRoaringBitmap bitmap : mutable) {
      mutableSnapshots.add(bitmap.clone());
    }
    assertEquals(expected, BufferFastAggregation.or(mutable));
    assertEquals(expected, BufferFastAggregation.naive_or(mutable));
    assertEquals(mutableSnapshots, Arrays.asList(mutable));
    assertEquals(expected, BufferParallelAggregation.or(all));
    assertEquals(snapshots, snapshots(inputs));
  }

  @Test
  public void aggregationsLeaveSmallArrayInputsUntouched() {
    Random random = new Random(99);
    char[] keys = randomKeys(random, 16);
    List<ImmutableRoaringBitmap> inputs = new ArrayList<>();
    for (int i = 0; i < 20; i++) {
      inputs.add(flavoured(bitmapWith(random, keys, 8), i));
    }
    List<MutableRoaringBitmap> snapshots = snapshots(inputs);
    MutableRoaringBitmap expected = eagerFold(new MutableRoaringBitmap(), inputs);
    ImmutableRoaringBitmap[] all = inputs.toArray(new ImmutableRoaringBitmap[0]);

    assertEquals(expected, BufferFastAggregation.or(all));
    assertEquals(expected, BufferFastAggregation.naive_or(all));
    assertEquals(expected, BufferFastAggregation.priorityqueue_or(all));
    // the MutableRoaringBitmap overloads are the ones backed by lazyor, whose array arm changed
    MutableRoaringBitmap[] mutable = new MutableRoaringBitmap[all.length];
    for (int i = 0; i < all.length; i++) {
      mutable[i] = all[i].toMutableRoaringBitmap();
    }
    List<MutableRoaringBitmap> mutableSnapshots = new ArrayList<>();
    for (MutableRoaringBitmap bitmap : mutable) {
      mutableSnapshots.add(bitmap.clone());
    }
    assertEquals(expected, BufferFastAggregation.or(mutable));
    assertEquals(expected, BufferFastAggregation.naive_or(mutable));
    assertEquals(mutableSnapshots, Arrays.asList(mutable));
    assertEquals(expected, BufferParallelAggregation.or(all));
    assertTrue(expected.validate());
    assertEquals(snapshots, snapshots(inputs));
  }
}
