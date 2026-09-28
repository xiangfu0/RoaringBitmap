package org.roaringbitmap;

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
import java.util.Random;

/**
 * Tests for the in-place {@link RoaringBitmap#or(RoaringBitmap)} when the input is much smaller
 * than the receiver: receiver-only keys are skipped by galloping and a singleton array input is
 * inserted into an array receiver in place. Every union is checked against the static {@link
 * RoaringBitmap#or(RoaringBitmap, RoaringBitmap)}, validated and round-tripped through
 * serialization.
 */
public class TestSparseInPlaceOr {

  private static final int RECEIVER_KEYS = 512;

  // Receiver keys spread over the whole unsigned key range (step 128 reaches 0xFF80).
  private static final int RECEIVER_STEP = 65536 / RECEIVER_KEYS;

  @ParameterizedTest
  @ValueSource(ints = {1, 2, 4, 8, 64})
  public void ratioSweepAllInputKeysPresent(int ratio) throws IOException {
    for (int kind = 0; kind < 4; kind++) {
      RoaringBitmap receiver = receiver(RECEIVER_KEYS, RECEIVER_STEP);
      RoaringBitmap input = new RoaringBitmap();
      for (int i = 0; i < RECEIVER_KEYS; i += ratio) {
        input.highLowContainer.append(receiver.highLowContainer.getKeyAtIndex(i), container(kind));
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
      RoaringBitmap receiver = receiver(RECEIVER_KEYS, RECEIVER_STEP);
      RoaringBitmap input = new RoaringBitmap();
      for (int i = 0; i < RECEIVER_KEYS; i += ratio) {
        char key = receiver.highLowContainer.getKeyAtIndex(i);
        input.highLowContainer.append(key, container(kind));
        // a key the receiver does not have, right after the match: gallop, then bulk insert
        input.highLowContainer.append((char) (key + 1), container((kind + 1) % 4));
      }
      assertUnion(receiver, input);
      assertEquals(RECEIVER_KEYS + RECEIVER_KEYS / ratio, receiver.highLowContainer.size());
    }
  }

  @Test
  public void receiverSmallerThanInput() throws IOException {
    for (int kind = 0; kind < 4; kind++) {
      // receiver: 8 keys, 7 of which the input has, plus one receiver-only key in the middle
      RoaringBitmap receiver = new RoaringBitmap();
      for (int i = 0; i < 8; i++) {
        int key = i * 8 * RECEIVER_STEP + (i == 3 ? 1 : 0);
        receiver.highLowContainer.append((char) key, container(i == 3 ? kind : i % 4));
      }
      RoaringBitmap input = receiver(RECEIVER_KEYS, RECEIVER_STEP);
      assertUnion(receiver, input);
      assertEquals(RECEIVER_KEYS + 1, receiver.highLowContainer.size());
    }
  }

  @ParameterizedTest
  @ValueSource(ints = {15, 16, 17, 64})
  public void sparseLayouts(int receiverSize) throws IOException {
    RoaringBitmap receiver = new RoaringBitmap();
    for (int i = 0; i < receiverSize - 3; i++) {
      receiver.highLowContainer.append((char) (2 * i + 2), container(i % 3 + 1));
    }
    receiver.highLowContainer.append((char) 32767, container(1));
    receiver.highLowContainer.append((char) 32768, container(2));
    receiver.highLowContainer.append((char) 65535, container(3));

    int[][] inputKeys = {
      {0, 3, 9, 65534}, // missing keys before, between, and near the end of the receiver
      {2, 8, 20, 24}, // matches, with a remaining receiver tail
      {32767, 32768, 65534, 65535}, // unsigned high keys and an interleaved insertion
      {60000, 60001, 60002, 65535} // a missing run followed by a final match
    };
    for (int[] keys : inputKeys) {
      for (int kind = 0; kind < 4; kind++) {
        RoaringBitmap input = new RoaringBitmap();
        for (int key : keys) {
          input.highLowContainer.append((char) key, container(kind));
        }
        assertUnion(receiver.clone(), input);
      }
    }

    RoaringBitmap tail = RoaringBitmap.bitmapOf(0xffff0001, 0xffff0002);
    RoaringBitmap lowKeysOnly = new RoaringBitmap();
    for (int i = 0; i < receiverSize; i++) {
      lowKeysOnly.add(i << 16);
    }
    assertUnion(lowKeysOnly, tail);
  }

  @Test
  public void containerKindMatrix() throws IOException {
    for (int receiverKind = 0; receiverKind < 4; receiverKind++) {
      for (int inputKind = 0; inputKind < 4; inputKind++) {
        RoaringBitmap receiver = new RoaringBitmap();
        for (int key = 0; key < 64; key += 2) {
          receiver.highLowContainer.append((char) key, container(receiverKind));
        }
        RoaringBitmap input = new RoaringBitmap();
        input.highLowContainer.append((char) 30, container(inputKind)); // present key
        input.highLowContainer.append((char) 31, container(inputKind)); // missing key
        input.highLowContainer.append((char) 62, container(inputKind)); // last receiver key
        assertUnion(receiver, input);
      }
    }
    // a singleton that is already present in an array receiver leaves the container untouched
    RoaringBitmap receiver = new RoaringBitmap();
    receiver.highLowContainer.append((char) 5, container(1));
    receiver.highLowContainer.append((char) 6, container(1));
    Container before = receiver.highLowContainer.getContainerAtIndex(0);
    assertUnion(receiver, RoaringBitmap.bitmapOf((5 << 16) | 3));
    assertSame(before, receiver.highLowContainer.getContainerAtIndex(0));
    assertEquals(3, receiver.highLowContainer.getContainerAtIndex(0).getCardinality());
  }

  @Test
  public void singletonInsertionAndSourceOwnership() throws IOException {
    RoaringBitmap receiver = new RoaringBitmap();
    for (int key = 0; key < 32; key += 2) {
      receiver.highLowContainer.append((char) key, container(1));
    }
    receiver.highLowContainer.setContainerAtIndex(0, new ArrayContainer(0, 4096));
    receiver.highLowContainer.setContainerAtIndex(1, new BitmapContainer(0, 5000));
    receiver.highLowContainer.setContainerAtIndex(2, new RunContainer(0, 5000));
    RoaringBitmap singleton = RoaringBitmap.bitmapOf(4096, (2 << 16) | 4999, (4 << 16) | 5000);
    assertUnion(receiver, singleton);
    assertInstanceOf(BitmapContainer.class, receiver.highLowContainer.getContainerAtIndex(0));

    // Copying both interleaved and appended containers must retain independent ownership.
    for (int kind = 1; kind < 4; kind++) {
      RoaringBitmap source = new RoaringBitmap();
      for (int key : new int[] {1, 2, 3, 65535}) {
        source.highLowContainer.append((char) key, container(kind));
      }
      RoaringBitmap result = receiver.clone();
      assertUnion(result, source);
      RoaringBitmap resultBeforeMutation = result.clone();
      for (int key : new int[] {1, 2, 3, 65535}) {
        source.add((key << 16) | 60000);
      }
      assertEquals(resultBeforeMutation, result);
      RoaringBitmap sourceBeforeMutation = source.clone();
      for (int key : new int[] {1, 2, 3, 65535}) {
        result.add((key << 16) | 60001);
      }
      assertEquals(sourceBeforeMutation, source);
    }
  }

  @Test
  public void arrayReceiverAtConversionBoundary() throws IOException {
    for (int cardinality : new int[] {4095, 4096}) {
      for (boolean present : new boolean[] {false, true}) {
        RoaringBitmap receiver = new RoaringBitmap();
        receiver.highLowContainer.append((char) 7, new ArrayContainer(0, cardinality));
        receiver.highLowContainer.append((char) 9, container(1));
        receiver.highLowContainer.append((char) 11, container(2));
        Container before = receiver.highLowContainer.getContainerAtIndex(0);
        int low = present ? 10 : 60000;
        assertUnion(receiver, RoaringBitmap.bitmapOf((7 << 16) | low));
        Container result = receiver.highLowContainer.getContainerAtIndex(0);
        int expectedCardinality = present ? cardinality : cardinality + 1;
        assertEquals(expectedCardinality, result.getCardinality());
        if (expectedCardinality > ArrayContainer.DEFAULT_MAX_SIZE) {
          assertInstanceOf(BitmapContainer.class, result);
        } else {
          // the singleton is inserted into the receiver's own array; a present value into a full
          // 4096-element array is the case where ior() would instead round-trip through a bitmap
          // container and hand back a new instance
          assertInstanceOf(ArrayContainer.class, result);
          assertSame(before, result);
        }
      }
    }
  }

  @Test
  public void repeatedSingletonsConvertAnInefficientRunContainer() throws IOException {
    RoaringBitmap actual = new RoaringBitmap();
    actual.highLowContainer.append((char) 0, new RunContainer(0, 4096));
    for (int key = 1; key < 4; key++) {
      actual.add(key << 16);
    }
    RoaringBitmap expected = actual.clone();
    for (int i = 0; i < 2100; i++) {
      RoaringBitmap singleton = RoaringBitmap.bitmapOf(8192 + 2 * i);
      expected = RoaringBitmap.or(expected, singleton);
      actual.or(singleton);
      assertEquals(expected, actual, "singleton " + i);
      assertEquals(expected.serializedSizeInBytes(), actual.serializedSizeInBytes());
      assertEquals(
          expected.highLowContainer.getContainerAtIndex(0).getClass(),
          actual.highLowContainer.getContainerAtIndex(0).getClass());
    }
    assertInstanceOf(BitmapContainer.class, actual.highLowContainer.getContainerAtIndex(0));
    assertTrue(actual.validate());
    assertRoundTrip(actual);
  }

  @Test
  public void emptyAndSelfUnion() throws IOException {
    RoaringBitmap bitmap = RoaringBitmap.bitmapOf(0, 65536, Integer.MIN_VALUE, -1);
    assertUnion(bitmap.clone(), new RoaringBitmap());
    assertUnion(new RoaringBitmap(), bitmap);
    assertUnion(new RoaringBitmap(), new RoaringBitmap());
    assertUnion(bitmap, bitmap);
  }

  @Test
  public void seededRepeatedFolds() throws IOException {
    Random random = new Random(0x5a17c0deL);
    RoaringBitmap actual = new RoaringBitmap();
    RoaringBitmap expected = new RoaringBitmap();
    for (int fold = 0; fold < 100; fold++) {
      RoaringBitmap right = new RoaringBitmap();
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
      RoaringBitmap sourceBefore = right.clone();
      expected = RoaringBitmap.or(expected, right);
      actual.or(right);
      assertEquals(expected, actual, "fold " + fold);
      assertEquals(expected.getLongCardinality(), actual.getLongCardinality());
      assertEquals(sourceBefore, right);
      assertTrue(actual.validate());
    }
    assertRoundTrip(actual);
  }

  @Test
  public void seededSparseFoldsIntoLargeReceiver() throws IOException {
    Random random = new Random(0x0badcafeL);
    RoaringBitmap actual = receiver(2048, 32);
    RoaringBitmap expected = actual.clone();
    for (int fold = 0; fold < 500; fold++) {
      RoaringBitmap right = new RoaringBitmap();
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
      RoaringBitmap sourceBefore = right.clone();
      expected = RoaringBitmap.or(expected, right);
      actual.or(right);
      assertEquals(expected, actual, "fold " + fold);
      assertEquals(sourceBefore, right);
      assertTrue(actual.validate());
    }
    assertRoundTrip(actual);
  }

  /** A receiver with {@code keys} keys at multiples of {@code step}, cycling container kinds. */
  private static RoaringBitmap receiver(int keys, int step) {
    RoaringBitmap bitmap = new RoaringBitmap();
    for (int i = 0; i < keys; i++) {
      bitmap.highLowContainer.append((char) (i * step), container(i % 4));
    }
    return bitmap;
  }

  private static Container container(int kind) {
    switch (kind) {
      case 0:
        return new ArrayContainer(new char[] {5001});
      case 1:
        return new ArrayContainer(new char[] {1, 3, 5});
      case 2:
        return new BitmapContainer(0, 5000);
      case 3:
        return new RunContainer(4000, 6000);
      default:
        throw new IllegalArgumentException();
    }
  }

  private static void assertUnion(RoaringBitmap receiver, RoaringBitmap input) throws IOException {
    RoaringBitmap inputBefore = input.clone();
    RoaringBitmap expected = RoaringBitmap.or(receiver, input);
    receiver.or(input);
    assertEquals(expected, receiver);
    assertEquals(expected.getLongCardinality(), receiver.getLongCardinality());
    assertEquals(inputBefore, input);
    assertTrue(receiver.validate());
    assertRoundTrip(receiver);
  }

  private static void assertRoundTrip(RoaringBitmap bitmap) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    bitmap.serialize(new DataOutputStream(bytes));
    RoaringBitmap restored = new RoaringBitmap();
    restored.deserialize(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())));
    assertTrue(restored.validate());
    assertEquals(bitmap, restored);
  }
}
