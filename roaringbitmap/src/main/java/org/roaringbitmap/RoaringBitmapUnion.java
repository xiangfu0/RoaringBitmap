package org.roaringbitmap;

import java.io.DataOutput;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Objects;

/**
 * An incremental bitmap union that owns its intermediate state. Cardinality maintenance is deferred
 * until a result or serialized form is requested. Inputs are consumed immediately, without retaining
 * them, and sparse containers are not unconditionally promoted to bitmaps.
 *
 * <p>This class is not thread-safe. Neither this object nor another union passed to {@link
 * #add(RoaringBitmapUnion)} may be accessed concurrently. No unfinished bitmap is exposed. A bitmap
 * returned by {@link #take()} is independent of subsequent operations on this union.
 */
public final class RoaringBitmapUnion {
  private RoaringBitmap bitmap;
  private boolean dirty;

  /** Creates an empty union. */
  public RoaringBitmapUnion() {
    bitmap = new RoaringBitmap();
  }

  private RoaringBitmapUnion(RoaringBitmap bitmap, boolean dirty) {
    this.bitmap = bitmap;
    this.dirty = dirty;
  }

  /**
   * Takes exclusive ownership of a valid bitmap, avoiding a copy. The caller must relinquish all
   * access to the bitmap and any mutable aliases until it is returned by {@link #take()}.
   *
   * @param bitmap valid, exclusively owned bitmap
   * @return a union owning the supplied bitmap
   */
  public static RoaringBitmapUnion takeOwnership(RoaringBitmap bitmap) {
    return new RoaringBitmapUnion(Objects.requireNonNull(bitmap), false);
  }

  /**
   * Unions a valid bitmap into this object. The input is neither modified nor retained; it may be
   * reused or changed after this call returns.
   *
   * @param input valid bitmap to union
   */
  public void add(RoaringBitmap input) {
    Objects.requireNonNull(input);
    dirty = true;
    bitmap.lazyor(input);
  }

  /**
   * Unions another accumulator without consuming it. Its representation may be normalized
   * internally, but its values are unchanged. Adding this object to itself is a no-op.
   *
   * @param input union to read
   */
  public void add(RoaringBitmapUnion input) {
    Objects.requireNonNull(input);
    if (input != this) {
      input.repair();
      add(input.bitmap);
    }
  }

  /**
   * Adds one unsigned integer value. Mixing individual additions with bitmap unions is supported,
   * but an individual addition first normalizes any pending bitmap union.
   *
   * @param value unsigned integer, represented as an int
   */
  public void add(int value) {
    repair();
    bitmap.add(value);
  }

  /** @return an independent copy, without forcing pending cardinality maintenance */
  public RoaringBitmapUnion copy() {
    return new RoaringBitmapUnion(bitmap.clone(), dirty);
  }

  /**
   * Returns the valid accumulated bitmap and resets this union to empty. The returned bitmap is
   * owned by the caller; subsequent calls cannot modify it. Calling this again returns an empty
   * bitmap unless more inputs have been added.
   *
   * @return the accumulated bitmap
   */
  public RoaringBitmap take() {
    repair();
    RoaringBitmap result = bitmap;
    bitmap = new RoaringBitmap();
    return result;
  }

  /**
   * Returns an upper bound on the current serialized size without normalizing pending unions.
   * This is a serialized-size bound, not a bound on heap usage or on future additions.
   *
   * @return upper bound in bytes
   */
  public int serializedSizeUpperBound() {
    if (!dirty) {
      return bitmap.serializedSizeInBytes();
    }
    RoaringArray array = bitmap.highLowContainer;
    int size = array.size;
    // Repair never increases container payload size, but changing the final run container can
    // change the header format. Bound both header formats, including their offset tables.
    int bytes = Math.max(8 + 8 * size, 4 + (size + 7) / 8 + 8 * size);
    for (int i = 0; i < size; i++) {
      // Inefficient run containers can exceed a bitmap's fixed payload size before repair.
      bytes +=
          Math.min(
              array.values[i].serializedSizeInBytes(), BitmapContainer.serializedSizeInBytes(0));
    }
    return bytes;
  }

  /** @return the exact serialized size, after normalizing pending unions */
  public int serializedSizeInBytes() {
    repair();
    return bitmap.serializedSizeInBytes();
  }

  /**
   * Serializes a valid bitmap without consuming this union. The output uses the standard Roaring
   * format and can be read by existing bitmap readers.
   *
   * @param output destination buffer with enough remaining space
   */
  public void serialize(ByteBuffer output) {
    repair();
    bitmap.serialize(output);
  }

  /**
   * Serializes a valid bitmap without consuming this union.
   *
   * @param output destination
   * @throws IOException if writing fails
   */
  public void serialize(DataOutput output) throws IOException {
    repair();
    bitmap.serialize(output);
  }

  private void repair() {
    if (dirty) {
      bitmap.repairAfterLazy();
      dirty = false;
    }
  }
}
