package org.roaringbitmap.buffer;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

class TestMutableRoaringBitmapUnion {
  @Test
  void readsReadonlyInputAndDetachesResults() {
    MutableRoaringBitmap input = MutableRoaringBitmap.bitmapOf(1, 3, -1);
    ByteBuffer bytes = ByteBuffer.allocate(input.serializedSizeInBytes());
    input.serialize(bytes);
    bytes.flip();
    ImmutableRoaringBitmap readonly = new ImmutableRoaringBitmap(bytes.asReadOnlyBuffer());
    MutableRoaringBitmapUnion union = new MutableRoaringBitmapUnion();
    union.add(readonly);
    input.clear();
    input.add(9);
    union.add(input);
    input.clear();
    MutableRoaringBitmap result = union.take();
    assertEquals(MutableRoaringBitmap.bitmapOf(1, 3, 9, -1), result);
    assertEquals(MutableRoaringBitmap.bitmapOf(1, 3, -1), readonly);
    union.add(MutableRoaringBitmap.bitmapOf(10));
    assertEquals(MutableRoaringBitmap.bitmapOf(10), union.take());
    assertTrue(union.take().isEmpty());
    assertEquals(MutableRoaringBitmap.bitmapOf(1, 3, 9, -1), result);
    assertSame(input, MutableRoaringBitmapUnion.takeOwnership(input).take());
  }

  @Test
  void preservesSparseContainersThroughBulkInsertion() throws Exception {
    MutableRoaringBitmap first = MutableRoaringBitmap.bitmapOf(1, 2 << 16, 4 << 16);
    MutableRoaringBitmap next = MutableRoaringBitmap.bitmapOf(3, 1 << 16, (2 << 16) + 1, 3 << 16);
    MutableRoaringBitmapUnion union = new MutableRoaringBitmapUnion();
    ByteBuffer bytes = ByteBuffer.allocate(next.serializedSizeInBytes());
    next.serialize(bytes);
    bytes.flip();
    ImmutableRoaringBitmap readonly = new ImmutableRoaringBitmap(bytes.asReadOnlyBuffer());
    union.add(first);
    union.add(readonly);
    java.lang.reflect.Field state = MutableRoaringBitmapUnion.class.getDeclaredField("bitmap");
    state.setAccessible(true);
    MutableRoaringBitmap pending = (MutableRoaringBitmap) state.get(union);
    for (int i = 0; i < pending.highLowContainer.size(); i++) {
      assertInstanceOf(
          MappeableArrayContainer.class, pending.highLowContainer.getContainerAtIndex(i));
    }
    MutableRoaringBitmap result = union.take();
    assertEquals(next, readonly);
    assertEquals(ImmutableRoaringBitmap.or(first, next), result);
    for (int i = 0; i < result.highLowContainer.size(); i++) {
      assertInstanceOf(
          MappeableArrayContainer.class, result.highLowContainer.getContainerAtIndex(i));
    }
  }
}
