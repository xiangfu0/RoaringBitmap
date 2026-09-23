/*
 * (c) the authors Licensed under the Apache License, Version 2.0.
 */

package org.roaringbitmap.buffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

class TestMutableRoaringBitmapPrivate {
  @Test
  void lazyorPreservesSparseContainersWithReadOnlyInput() {
    MutableRoaringBitmap accumulator = new MutableRoaringBitmap();
    MutableRoaringBitmap source = new MutableRoaringBitmap();
    for (int key = 0; key < 64; key++) {
      accumulator.add((key << 16) | 1);
      source.add((key << 16) | 3);
    }
    int inputOnlyValue = (64 << 16) | 3;
    source.add(inputOnlyValue);
    ImmutableRoaringBitmap input = readOnlyCopy(source);
    MutableRoaringBitmap expected = ImmutableRoaringBitmap.or(accumulator, input);
    MutableRoaringBitmap naive = accumulator.clone();

    MutableRoaringBitmapPrivate.lazyor(accumulator, input);
    MutableRoaringBitmapPrivate.naivelazyor(naive, source);
    for (int key = 0; key < 64; key++) {
      assertInstanceOf(
          MappeableArrayContainer.class, accumulator.highLowContainer.getContainerAtIndex(key));
      assertInstanceOf(
          MappeableBitmapContainer.class, naive.highLowContainer.getContainerAtIndex(key));
    }
    MutableRoaringBitmapPrivate.repairAfterLazy(accumulator);
    MutableRoaringBitmapPrivate.repairAfterLazy(naive);
    assertEquals(expected, accumulator);
    assertEquals(expected, naive);
    assertEquals(expected.getCardinality(), accumulator.getCardinality());
    accumulator.remove(inputOnlyValue);
    assertEquals(source, input);
  }

  @Test
  void lazyorRepairsPromotedContainersAfterRepeatedUnions() {
    MutableRoaringBitmap accumulator = new MutableRoaringBitmap();
    MutableRoaringBitmap source = new MutableRoaringBitmap();
    for (int value = 0; value < 800; value++) {
      accumulator.add(2 * value);
      source.add(2 * value + 1);
    }
    ImmutableRoaringBitmap input = readOnlyCopy(source);
    MutableRoaringBitmap expected = ImmutableRoaringBitmap.or(accumulator, input);

    MutableRoaringBitmapPrivate.lazyor(accumulator, input);
    MutableRoaringBitmapPrivate.lazyor(accumulator, input);
    assertInstanceOf(
        MappeableBitmapContainer.class, accumulator.highLowContainer.getContainerAtIndex(0));
    MutableRoaringBitmapPrivate.repairAfterLazy(accumulator);
    assertEquals(expected, accumulator);
    assertEquals(1600, accumulator.getCardinality());
    assertEquals(source, input);
  }

  private static ImmutableRoaringBitmap readOnlyCopy(MutableRoaringBitmap source) {
    ByteBuffer buffer = ByteBuffer.allocate(source.serializedSizeInBytes());
    source.serialize(buffer);
    buffer.flip();
    return new ImmutableRoaringBitmap(buffer.asReadOnlyBuffer());
  }
}
