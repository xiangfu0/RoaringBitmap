package org.roaringbitmap.buffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Random;

/**
 * Buffer twin of {@code org.roaringbitmap.TestSparseInPlaceOr}: exercises the in-place {@link
 * MutableRoaringBitmap#or(ImmutableRoaringBitmap)} with a heap input, a read-only mapped input
 * and a direct-buffer input, each checked against the static union, validated and round-tripped
 * through serialization.
 */
public class TestSparseInPlaceOr {

  private static final int RECEIVER_KEYS = 512;

  // Receiver keys spread over the whole unsigned key range (step 128 reaches 0xFF80).
  private static final int RECEIVER_STEP = 65536 / RECEIVER_KEYS;

  @ParameterizedTest
  @ValueSource(ints = {1, 2, 4, 8, 64})
  public void ratioSweepAllInputKeysPresent(int ratio) throws IOException {
    for (int kind = 0; kind < 4; kind++) {
      MutableRoaringBitmap receiver = receiver(RECEIVER_KEYS, RECEIVER_STEP);
      MutableRoaringBitmap input = new MutableRoaringBitmap();
      for (int i = 0; i < RECEIVER_KEYS; i += ratio) {
        input
            .getMappeableRoaringArray()
            .append(receiver.highLowContainer.getKeyAtIndex(i), container(kind));
      }
      assertEquals(RECEIVER_KEYS / ratio, input.highLowContainer.size());
      assertUnion(receiver, input);
      assertEquals(RECEIVER_KEYS, receiver.highLowContainer.size());
    }
  }

  @ParameterizedTest
  @ValueSource(ints = {2, 4, 8, 64})
  public void ratioSweepWithMissingKeysBetweenMatches(int ratio) throws IOException {
    for (int kind = 0; kind < 4; kind++) {
      MutableRoaringBitmap receiver = receiver(RECEIVER_KEYS, RECEIVER_STEP);
      MutableRoaringBitmap input = new MutableRoaringBitmap();
      for (int i = 0; i < RECEIVER_KEYS; i += ratio) {
        char key = receiver.highLowContainer.getKeyAtIndex(i);
        input.getMappeableRoaringArray().append(key, container(kind));
        // a key the receiver does not have, right after the match: gallop, then bulk insert
        input.getMappeableRoaringArray().append((char) (key + 1), container((kind + 1) % 4));
      }
      assertUnion(receiver, input);
      assertEquals(RECEIVER_KEYS + RECEIVER_KEYS / ratio, receiver.highLowContainer.size());
    }
  }

  @Test
  public void receiverSmallerThanInput() throws IOException {
    for (int kind = 0; kind < 4; kind++) {
      // receiver: 8 keys, 7 of which the input has, plus one receiver-only key in the middle
      MutableRoaringBitmap receiver = new MutableRoaringBitmap();
      for (int i = 0; i < 8; i++) {
        int key = i * 8 * RECEIVER_STEP + (i == 3 ? 1 : 0);
        receiver.getMappeableRoaringArray().append((char) key, container(i == 3 ? kind : i % 4));
      }
      MutableRoaringBitmap input = receiver(RECEIVER_KEYS, RECEIVER_STEP);
      assertUnion(receiver, input);
      assertEquals(RECEIVER_KEYS + 1, receiver.highLowContainer.size());
    }
  }

  @ParameterizedTest
  @ValueSource(ints = {15, 16, 17, 64})
  public void sparseLayouts(int receiverSize) throws IOException {
    MutableRoaringBitmap receiver = new MutableRoaringBitmap();
    for (int i = 0; i < receiverSize - 3; i++) {
      receiver.getMappeableRoaringArray().append((char) (2 * i + 2), container(i % 3 + 1));
    }
    receiver.getMappeableRoaringArray().append((char) 32767, container(1));
    receiver.getMappeableRoaringArray().append((char) 32768, container(2));
    receiver.getMappeableRoaringArray().append((char) 65535, container(3));

    int[][] inputKeys = {
      {0, 3, 9, 65534}, // missing keys before, between, and near the end of the receiver
      {2, 8, 20, 24}, // matches, with a remaining receiver tail
      {32767, 32768, 65534, 65535}, // unsigned high keys and an interleaved insertion
      {60000, 60001, 60002, 65535} // a missing run followed by a final match
    };
    for (int[] keys : inputKeys) {
      for (int kind = 0; kind < 4; kind++) {
        MutableRoaringBitmap input = new MutableRoaringBitmap();
        for (int key : keys) {
          input.getMappeableRoaringArray().append((char) key, container(kind));
        }
        assertUnion(receiver.clone(), input);
      }
    }

    MutableRoaringBitmap tail = MutableRoaringBitmap.bitmapOf(0xffff0001, 0xffff0002);
    MutableRoaringBitmap lowKeysOnly = new MutableRoaringBitmap();
    for (int i = 0; i < receiverSize; i++) {
      lowKeysOnly.add(i << 16);
    }
    assertUnion(lowKeysOnly, tail);
  }

  @Test
  public void containerKindMatrix() throws IOException {
    for (int receiverKind = 0; receiverKind < 4; receiverKind++) {
      for (int inputKind = 0; inputKind < 4; inputKind++) {
        MutableRoaringBitmap receiver = new MutableRoaringBitmap();
        for (int key = 0; key < 64; key += 2) {
          receiver.getMappeableRoaringArray().append((char) key, container(receiverKind));
        }
        MutableRoaringBitmap input = new MutableRoaringBitmap();
        input.getMappeableRoaringArray().append((char) 30, container(inputKind)); // present key
        input.getMappeableRoaringArray().append((char) 31, container(inputKind)); // missing key
        input.getMappeableRoaringArray().append((char) 62, container(inputKind)); // last key
        assertUnion(receiver, input);
      }
    }
    // a singleton that is already present in an array receiver leaves the container untouched
    MutableRoaringBitmap receiver = new MutableRoaringBitmap();
    receiver.getMappeableRoaringArray().append((char) 5, container(1));
    receiver.getMappeableRoaringArray().append((char) 6, container(1));
    MappeableContainer before = receiver.highLowContainer.getContainerAtIndex(0);
    assertUnion(receiver, MutableRoaringBitmap.bitmapOf((5 << 16) | 3));
    assertSame(before, receiver.highLowContainer.getContainerAtIndex(0));
    assertEquals(3, receiver.highLowContainer.getContainerAtIndex(0).getCardinality());
  }

  @Test
  public void singletonInsertionAndSourceOwnership() throws IOException {
    MutableRoaringBitmap receiver = new MutableRoaringBitmap();
    for (int key = 0; key < 32; key += 2) {
      receiver.getMappeableRoaringArray().append((char) key, container(1));
    }
    receiver
        .getMappeableRoaringArray()
        .setContainerAtIndex(0, new MappeableArrayContainer(0, 4096));
    receiver
        .getMappeableRoaringArray()
        .setContainerAtIndex(1, new MappeableBitmapContainer(0, 5000));
    receiver.getMappeableRoaringArray().setContainerAtIndex(2, new MappeableRunContainer(0, 5000));
    MutableRoaringBitmap singleton =
        MutableRoaringBitmap.bitmapOf(4096, (2 << 16) | 4999, (4 << 16) | 5000);
    assertUnion(receiver, singleton);
    assertInstanceOf(
        MappeableBitmapContainer.class, receiver.highLowContainer.getContainerAtIndex(0));

    // Copying both interleaved and appended containers must retain independent ownership.
    for (int kind = 1; kind < 4; kind++) {
      MutableRoaringBitmap source = new MutableRoaringBitmap();
      for (int key : new int[] {1, 2, 3, 65535}) {
        source.getMappeableRoaringArray().append((char) key, container(kind));
      }
      MutableRoaringBitmap result = receiver.clone();
      assertUnion(result, source);
      MutableRoaringBitmap resultBeforeMutation = result.clone();
      for (int key : new int[] {1, 2, 3, 65535}) {
        source.add((key << 16) | 60000);
      }
      assertEquals(resultBeforeMutation, result);
      MutableRoaringBitmap sourceBeforeMutation = source.clone();
      for (int key : new int[] {1, 2, 3, 65535}) {
        result.add((key << 16) | 60001);
      }
      assertEquals(sourceBeforeMutation, source);
    }
  }

  @Test
  public void receiverFromMappedBitmapWithMappedInputs() throws IOException {
    MutableRoaringBitmap origin = receiver(RECEIVER_KEYS, RECEIVER_STEP);
    ImmutableRoaringBitmap mappedOrigin = toReadOnlyMapped(origin);
    MutableRoaringBitmap receiver = mappedOrigin.toMutableRoaringBitmap();
    assertEquals(origin, receiver);

    int[] keys = {0, 1, 3 * RECEIVER_STEP, 3 * RECEIVER_STEP + 1, 40000, 65535};
    for (int kind = 0; kind < 4; kind++) {
      MutableRoaringBitmap input = new MutableRoaringBitmap();
      for (int key : keys) {
        input.getMappeableRoaringArray().append((char) key, container(kind));
      }
      ImmutableRoaringBitmap mappedInput = toReadOnlyMapped(input);
      MutableRoaringBitmap expected = MutableRoaringBitmap.or(receiver, mappedInput);
      receiver.or(mappedInput);
      assertResult(expected, receiver);
      // the result owns its containers: mutating it neither fails on the read-only source nor
      // changes it
      for (int key : keys) {
        receiver.add((key << 16) | (60000 + kind));
      }
      assertTrue(receiver.validate());
      assertEquals(input, mappedInput);
    }
    assertEquals(origin, mappedOrigin);
  }

  @Test
  public void arrayReceiverAtConversionBoundary() throws IOException {
    for (int cardinality : new int[] {4095, 4096}) {
      for (boolean present : new boolean[] {false, true}) {
        for (int flavor = 0; flavor < 3; flavor++) {
          MutableRoaringBitmap receiver = new MutableRoaringBitmap();
          receiver
              .getMappeableRoaringArray()
              .append((char) 7, new MappeableArrayContainer(0, cardinality));
          receiver.getMappeableRoaringArray().append((char) 9, container(1));
          receiver.getMappeableRoaringArray().append((char) 11, container(2));
          MappeableContainer before = receiver.highLowContainer.getContainerAtIndex(0);
          int low = present ? 10 : 60000;
          ImmutableRoaringBitmap input =
              inputFlavor(MutableRoaringBitmap.bitmapOf((7 << 16) | low), flavor);
          MutableRoaringBitmap expected = MutableRoaringBitmap.or(receiver, input);
          receiver.or(input);
          assertResult(expected, receiver);
          MappeableContainer result = receiver.highLowContainer.getContainerAtIndex(0);
          int expectedCardinality = present ? cardinality : cardinality + 1;
          assertEquals(expectedCardinality, result.getCardinality());
          if (expectedCardinality > MappeableArrayContainer.DEFAULT_MAX_SIZE) {
            assertInstanceOf(MappeableBitmapContainer.class, result);
          } else {
            // the singleton is inserted into the receiver's own array whatever the input's
            // backing; a present value into a full 4096-element array is the case where ior()
            // would instead round-trip through a bitmap container and hand back a new instance
            assertInstanceOf(MappeableArrayContainer.class, result);
            assertSame(before, result);
          }
        }
      }
    }
  }

  @Test
  public void repeatedSingletonsConvertAnInefficientRunContainer() throws IOException {
    MutableRoaringBitmap actual = new MutableRoaringBitmap();
    actual.getMappeableRoaringArray().append((char) 0, new MappeableRunContainer(0, 4096));
    for (int key = 1; key < 4; key++) {
      actual.add(key << 16);
    }
    MutableRoaringBitmap expected = actual.clone();
    for (int i = 0; i < 2100; i++) {
      MutableRoaringBitmap singleton = MutableRoaringBitmap.bitmapOf(8192 + 2 * i);
      ImmutableRoaringBitmap input = (i & 1) == 0 ? toReadOnlyMapped(singleton) : singleton;
      expected = MutableRoaringBitmap.or(expected, input);
      actual.or(input);
      assertEquals(expected, actual, "singleton " + i);
      assertEquals(expected.serializedSizeInBytes(), actual.serializedSizeInBytes());
      assertEquals(
          expected.highLowContainer.getContainerAtIndex(0).getClass(),
          actual.highLowContainer.getContainerAtIndex(0).getClass());
    }
    assertInstanceOf(
        MappeableBitmapContainer.class, actual.highLowContainer.getContainerAtIndex(0));
    assertTrue(actual.validate());
    assertRoundTrip(actual);
  }

  @Test
  public void emptyAndSelfUnion() throws IOException {
    MutableRoaringBitmap bitmap = MutableRoaringBitmap.bitmapOf(0, 65536, Integer.MIN_VALUE, -1);
    assertUnion(bitmap.clone(), new MutableRoaringBitmap());
    assertUnion(new MutableRoaringBitmap(), bitmap);
    assertUnion(new MutableRoaringBitmap(), new MutableRoaringBitmap());
    MutableRoaringBitmap self = bitmap.clone();
    self.or(self);
    assertResult(bitmap, self);
  }

  @Test
  public void seededRepeatedFolds() throws IOException {
    Random random = new Random(0x5a17c0deL);
    MutableRoaringBitmap actual = new MutableRoaringBitmap();
    MutableRoaringBitmap expected = new MutableRoaringBitmap();
    for (int fold = 0; fold < 100; fold++) {
      MutableRoaringBitmap right = new MutableRoaringBitmap();
      for (int i = 0, count = 1 + random.nextInt(20); i < count; i++) {
        int key = random.nextInt(256) * 257;
        int low = random.nextInt(65536);
        right.add((key << 16) | low);
        if ((fold & 3) == 0) {
          long start = ((long) key << 16) | low;
          right.add(start, Math.min(start + 10, ((long) key + 1) << 16));
        }
      }
      if ((fold & 1) == 0) {
        right.runOptimize();
      }
      ImmutableRoaringBitmap input = inputFlavor(right, fold);
      expected = MutableRoaringBitmap.or(expected, input);
      actual.or(input);
      assertEquals(expected, actual, "fold " + fold);
      assertEquals(expected.getLongCardinality(), actual.getLongCardinality());
      assertEquals(right, input);
      assertTrue(actual.validate());
    }
    assertRoundTrip(actual);
  }

  @Test
  public void seededSparseFoldsIntoLargeReceiver() throws IOException {
    Random random = new Random(0x0badcafeL);
    MutableRoaringBitmap actual = receiver(2048, 32);
    MutableRoaringBitmap expected = actual.clone();
    for (int fold = 0; fold < 500; fold++) {
      MutableRoaringBitmap right = new MutableRoaringBitmap();
      for (int i = 0, count = 1 + random.nextInt(4); i < count; i++) {
        // mostly existing keys (multiples of 32), sometimes a missing one
        int key = random.nextInt(2048) * 32 + (random.nextInt(8) == 0 ? 1 : 0);
        int values = random.nextInt(3) == 0 ? 3 : 1;
        for (int v = 0; v < values; v++) {
          right.add((key << 16) | random.nextInt(65536));
        }
      }
      if (fold % 7 == 0) {
        right.runOptimize();
      }
      ImmutableRoaringBitmap input = inputFlavor(right, fold);
      expected = MutableRoaringBitmap.or(expected, input);
      actual.or(input);
      assertEquals(expected, actual, "fold " + fold);
      assertEquals(right, input);
      assertTrue(actual.validate());
    }
    assertRoundTrip(actual);
  }

  /** Cycles heap, read-only mapped and direct inputs so every fold mixes all three. */
  private static ImmutableRoaringBitmap inputFlavor(MutableRoaringBitmap bitmap, int index) {
    switch (index % 3) {
      case 0:
        return bitmap;
      case 1:
        return toReadOnlyMapped(bitmap);
      default:
        return toDirect(bitmap);
    }
  }

  /** A receiver with {@code keys} keys at multiples of {@code step}, cycling container kinds. */
  private static MutableRoaringBitmap receiver(int keys, int step) {
    MutableRoaringBitmap bitmap = new MutableRoaringBitmap();
    for (int i = 0; i < keys; i++) {
      bitmap.getMappeableRoaringArray().append((char) (i * step), container(i % 4));
    }
    return bitmap;
  }

  private static MappeableContainer container(int kind) {
    switch (kind) {
      case 0:
        return new MappeableArrayContainer(5001, 5002);
      case 1:
        return new MappeableArrayContainer(1, 2).add((char) 3).add((char) 5);
      case 2:
        return new MappeableBitmapContainer(0, 5000);
      case 3:
        return new MappeableRunContainer(4000, 6000);
      default:
        throw new IllegalArgumentException();
    }
  }

  private static ImmutableRoaringBitmap toReadOnlyMapped(MutableRoaringBitmap bitmap) {
    ByteBuffer buffer = ByteBuffer.allocate(bitmap.serializedSizeInBytes());
    bitmap.serialize(buffer);
    buffer.flip();
    return new ImmutableRoaringBitmap(buffer.asReadOnlyBuffer());
  }

  private static ImmutableRoaringBitmap toDirect(MutableRoaringBitmap bitmap) {
    ByteBuffer buffer = ByteBuffer.allocateDirect(bitmap.serializedSizeInBytes());
    bitmap.serialize(buffer);
    buffer.flip();
    return new ImmutableRoaringBitmap(buffer);
  }

  /** Unions {@code input} as a heap, a read-only mapped and a direct-buffer input. */
  private static void assertUnion(MutableRoaringBitmap receiver, MutableRoaringBitmap input)
      throws IOException {
    MutableRoaringBitmap inputBefore = input.clone();
    MutableRoaringBitmap expected = MutableRoaringBitmap.or(receiver, input);

    MutableRoaringBitmap viaMapped = receiver.clone();
    viaMapped.or(toReadOnlyMapped(input));
    assertResult(expected, viaMapped);

    MutableRoaringBitmap viaDirect = receiver.clone();
    viaDirect.or(toDirect(input));
    assertResult(expected, viaDirect);

    receiver.or(input);
    assertResult(expected, receiver);
    assertEquals(inputBefore, input);
  }

  private static void assertResult(MutableRoaringBitmap expected, MutableRoaringBitmap actual)
      throws IOException {
    assertEquals(expected, actual);
    assertEquals(expected.getLongCardinality(), actual.getLongCardinality());
    assertTrue(actual.validate());
    assertRoundTrip(actual);
  }

  private static void assertRoundTrip(MutableRoaringBitmap bitmap) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    bitmap.serialize(new DataOutputStream(bytes));
    MutableRoaringBitmap restored = new MutableRoaringBitmap();
    restored.deserialize(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())));
    assertTrue(restored.validate());
    assertEquals(bitmap, restored);
  }
}
