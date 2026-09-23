/*
 * (c) the authors Licensed under the Apache License, Version 2.0.
 */

package org.roaringbitmap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import org.junit.jupiter.api.Test;

class TestRoaringBitmapPrivate {
  @Test
  void lazyorPreservesSparseContainers() {
    RoaringBitmap accumulator = new RoaringBitmap();
    RoaringBitmap input = new RoaringBitmap();
    for (int key = 0; key < 64; key++) {
      accumulator.add((key << 16) | 1);
      input.add((key << 16) | 3);
    }
    int inputOnlyValue = (64 << 16) | 3;
    input.add(inputOnlyValue);
    RoaringBitmap originalInput = input.clone();
    RoaringBitmap expected = RoaringBitmap.or(accumulator, input);
    RoaringBitmap naive = accumulator.clone();

    RoaringBitmapPrivate.lazyor(accumulator, input);
    RoaringBitmapPrivate.naivelazyor(naive, input);
    for (int key = 0; key < 64; key++) {
      assertInstanceOf(ArrayContainer.class, accumulator.highLowContainer.getContainerAtIndex(key));
      assertInstanceOf(BitmapContainer.class, naive.highLowContainer.getContainerAtIndex(key));
    }
    RoaringBitmapPrivate.repairAfterLazy(accumulator);
    RoaringBitmapPrivate.repairAfterLazy(naive);
    assertEquals(expected, accumulator);
    assertEquals(expected, naive);
    assertEquals(expected.getCardinality(), accumulator.getCardinality());
    accumulator.remove(inputOnlyValue);
    assertEquals(originalInput, input);
  }

  @Test
  void lazyorRepairsPromotedContainersAfterRepeatedUnions() {
    RoaringBitmap accumulator = new RoaringBitmap();
    RoaringBitmap input = new RoaringBitmap();
    for (int value = 0; value < 800; value++) {
      accumulator.add(2 * value);
      input.add(2 * value + 1);
    }
    RoaringBitmap originalInput = input.clone();
    RoaringBitmap expected = RoaringBitmap.or(accumulator, input);

    RoaringBitmapPrivate.lazyor(accumulator, input);
    RoaringBitmapPrivate.lazyor(accumulator, input);
    assertInstanceOf(BitmapContainer.class, accumulator.highLowContainer.getContainerAtIndex(0));
    RoaringBitmapPrivate.repairAfterLazy(accumulator);
    assertEquals(expected, accumulator);
    assertEquals(1600, accumulator.getCardinality());
    assertEquals(originalInput, input);
  }
}
