package org.roaringbitmap;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.ByteBuffer;
import java.util.Random;

class TestRoaringBitmapUnion {
  @Test
  void consumesInputsAndDetachesResults() {
    RoaringBitmap input = RoaringBitmap.bitmapOf(1, 3, -1);
    RoaringBitmapUnion union = new RoaringBitmapUnion();
    union.add(input);
    input.clear();
    input.add(5);
    union.add(input);
    RoaringBitmap result = union.take();
    assertEquals(RoaringBitmap.bitmapOf(1, 3, 5, -1), result);
    union.add(7);
    assertEquals(RoaringBitmap.bitmapOf(7), union.take());
    assertTrue(union.take().isEmpty());
    assertEquals(RoaringBitmap.bitmapOf(1, 3, 5, -1), result);
    RoaringBitmap owned = RoaringBitmap.bitmapOf(10);
    assertSame(owned, RoaringBitmapUnion.takeOwnership(owned).take());
  }

  @Test
  void copiesAndMergesReusableLazySources() {
    RoaringBitmap dense = new RoaringBitmap();
    dense.add(0L, 10000L);
    RoaringBitmapUnion source = new RoaringBitmapUnion();
    source.add(dense);
    source.add(RoaringBitmap.bitmapOf(20000));
    RoaringBitmapUnion copy = source.copy();
    RoaringBitmapUnion result = new RoaringBitmapUnion();
    result.add(source);
    result.add(source);
    result.add(result);
    source.add(30000);
    RoaringBitmap expected = dense.clone();
    expected.add(20000);
    assertEquals(expected, copy.take());
    assertEquals(expected, result.take());
    expected.add(30000);
    assertEquals(expected, source.take());
  }

  @Test
  void serializesWithoutConsumingAndBoundsHeaderChanges() throws Exception {
    // An inefficient last run container is normalized on repair. The bitmap header can grow
    // when this removes the last run, even though the container payload shrinks.
    RoaringBitmap bitmap = new RoaringBitmap();
    bitmap.highLowContainer.append((char) 0, new RunContainer(new char[] {1, 0, 3, 0}, 2));
    RoaringBitmapUnion union = RoaringBitmapUnion.takeOwnership(bitmap);
    union.add(RoaringBitmap.bitmapOf(65536));
    int bound = union.serializedSizeUpperBound();
    int exact = union.serializedSizeInBytes();
    assertTrue(bound >= exact);
    ByteBuffer output = ByteBuffer.allocate(exact);
    union.serialize(output);
    assertEquals(exact, output.position());
    output.flip();
    RoaringBitmap read = new RoaringBitmap();
    read.deserialize(output);
    assertEquals(RoaringBitmap.bitmapOf(1, 3, 65536), read);
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    union.serialize(new DataOutputStream(bytes));
    assertArrayEquals(output.array(), bytes.toByteArray());
    union.add(9);
    read.add(9);
    assertEquals(read, union.take());
  }

  @Test
  void boundsManyInefficientRunContainersWithoutOverflow() {
    char[] runs = new char[65536];
    for (int i = 0; i < 32768; i++) {
      runs[2 * i] = (char) (2 * i);
    }
    RunContainer run = new RunContainer(runs, 32768);
    RoaringBitmap bitmap = new RoaringBitmap();
    // Sharing this read-only fixture keeps the test small while its serialized size exceeds 2 GiB.
    int containers = 17000;
    for (int i = 0; i < containers; i++) {
      bitmap.highLowContainer.append((char) i, run);
    }
    RoaringBitmapUnion union = RoaringBitmapUnion.takeOwnership(bitmap);
    union.add(new RoaringBitmap());
    int bound = union.serializedSizeUpperBound();
    assertTrue(bound > 0);
    assertTrue(bound <= 8 + 9 * containers + 8192 * containers);
  }

  @Test
  void preservesSparseContainersAfterBulkInsertion() throws Exception {
    RoaringBitmap first = RoaringBitmap.bitmapOf(1, 2 << 16, 4 << 16);
    RoaringBitmap next = RoaringBitmap.bitmapOf(3, 1 << 16, (2 << 16) + 1, 3 << 16);
    RoaringBitmapUnion union = new RoaringBitmapUnion();
    union.add(first);
    union.add(next);
    java.lang.reflect.Field state = RoaringBitmapUnion.class.getDeclaredField("bitmap");
    state.setAccessible(true);
    RoaringBitmap pending = (RoaringBitmap) state.get(union);
    for (int i = 0; i < pending.highLowContainer.size; i++) {
      assertInstanceOf(ArrayContainer.class, pending.highLowContainer.values[i]);
    }
    RoaringBitmap result = union.take();
    assertEquals(RoaringBitmap.or(first, next), result);
    for (int i = 0; i < result.highLowContainer.size; i++) {
      assertInstanceOf(ArrayContainer.class, result.highLowContainer.values[i]);
    }
  }

  @Test
  void mixedContainerFoldsMatchEagerUnion() {
    Random random = new Random(7821);
    for (int trial = 0; trial < 40; trial++) {
      RoaringBitmapUnion union = new RoaringBitmapUnion();
      RoaringBitmap expected = new RoaringBitmap();
      for (int step = 0; step < 25; step++) {
        RoaringBitmap input = new RoaringBitmap();
        int key = random.nextInt(64) << 16;
        if (step % 3 == 0) {
          input.add((long) key, (long) key + 9000);
          if (step % 2 == 0) input.runOptimize();
        } else {
          for (int i = 0; i < 300; i++) input.add(key | random.nextInt(65536));
        }
        expected.or(input);
        union.add(input);
        int bound = union.serializedSizeUpperBound();
        RoaringBitmap snapshot = union.copy().take();
        assertEquals(expected, snapshot);
        assertTrue(bound >= snapshot.serializedSizeInBytes());
      }
      assertEquals(expected, union.take());
    }
  }
}
