package org.roaringbitmap;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

public class TestSparseInPlaceOr {

  @ParameterizedTest
  @ValueSource(ints = {15, 16, 17, 64})
  public void sparseLayoutsAtRatioBoundary(int leftSize) throws IOException {
    RoaringBitmap left = new RoaringBitmap();
    for (int i = 0; i < leftSize - 3; i++) {
      left.highLowContainer.append((char) (2 * i + 2), container(i % 3 + 1));
    }
    left.highLowContainer.append((char) 32767, container(1));
    left.highLowContainer.append((char) 32768, container(2));
    left.highLowContainer.append((char) 65535, container(3));

    int[][] rightKeys = {
      {0, 3, 9, 65534}, // missing keys before, between, and near the end of the left side
      {2, 8, 20, 24}, // matches, with a remaining left tail
      {32767, 32768, 65534, 65535}, // unsigned high keys and an interleaved insertion
      {60000, 60001, 60002, 65535} // a missing run followed by a final match
    };
    for (int[] keys : rightKeys) {
      for (int kind = 0; kind < 4; kind++) {
        RoaringBitmap right = new RoaringBitmap();
        for (int key : keys) {
          right.highLowContainer.append((char) key, container(kind));
        }
        assertUnion(left.clone(), right);
      }
    }

    RoaringBitmap tail = RoaringBitmap.bitmapOf(0xffff0001, 0xffff0002);
    RoaringBitmap lowKeysOnly = new RoaringBitmap();
    for (int i = 0; i < leftSize; i++) {
      lowKeysOnly.add(i << 16);
    }
    assertUnion(lowKeysOnly, tail);
  }

  @Test
  public void singletonInsertionAndSourceOwnership() throws IOException {
    RoaringBitmap left = new RoaringBitmap();
    for (int key = 0; key < 32; key += 2) {
      left.highLowContainer.append((char) key, container(1));
    }
    left.highLowContainer.setContainerAtIndex(0, new ArrayContainer(0, 4096));
    left.highLowContainer.setContainerAtIndex(1, new BitmapContainer(0, 5000));
    left.highLowContainer.setContainerAtIndex(2, new RunContainer(0, 5000));
    RoaringBitmap singleton = RoaringBitmap.bitmapOf(4096, (2 << 16) | 4999, (4 << 16) | 5000);
    assertUnion(left, singleton);
    assertTrue(left.highLowContainer.getContainerAtIndex(0) instanceof BitmapContainer);

    // Copying both interleaved and appended containers must retain independent ownership.
    for (int kind = 1; kind < 4; kind++) {
      RoaringBitmap source = new RoaringBitmap();
      for (int key : new int[] {1, 2, 3, 65535}) {
        source.highLowContainer.append((char) key, container(kind));
      }
      RoaringBitmap result = left.clone();
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
    assertTrue(actual.highLowContainer.getContainerAtIndex(0) instanceof BitmapContainer);
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

  private static void assertUnion(RoaringBitmap left, RoaringBitmap right) throws IOException {
    RoaringBitmap sourceBefore = right.clone();
    RoaringBitmap expected = RoaringBitmap.or(left, right);
    left.or(right);
    assertEquals(expected, left);
    assertEquals(expected.getLongCardinality(), left.getLongCardinality());
    assertEquals(sourceBefore, right);
    assertTrue(left.validate());
    assertRoundTrip(left);
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
