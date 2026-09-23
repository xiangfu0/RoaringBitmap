package org.roaringbitmap.buffer;

import java.util.Objects;

/**
 * An incremental union of buffer-backed bitmaps. Inputs, including read-only mapped inputs, are
 * consumed immediately without modification or retention. Intermediate lazy state is owned by
 * this object; {@link #take()} returns a valid independent mutable bitmap.
 *
 * <p>This class is not thread-safe. Sparse containers are not unconditionally promoted to bitmaps.
 */
public final class MutableRoaringBitmapUnion {
  private MutableRoaringBitmap bitmap;

  /** Creates an empty union. */
  public MutableRoaringBitmapUnion() {
    bitmap = new MutableRoaringBitmap();
  }

  private MutableRoaringBitmapUnion(MutableRoaringBitmap bitmap) {
    this.bitmap = bitmap;
  }

  /**
   * Takes exclusive ownership of a valid mutable bitmap. The caller must relinquish all access to
   * the bitmap and any mutable aliases until it is returned by {@link #take()}.
   *
   * @param bitmap valid, exclusively owned bitmap
   * @return a union owning the supplied bitmap
   */
  public static MutableRoaringBitmapUnion takeOwnership(MutableRoaringBitmap bitmap) {
    return new MutableRoaringBitmapUnion(Objects.requireNonNull(bitmap));
  }

  /**
   * Adds a valid bitmap without modifying or retaining it. It may be reused after this call.
   *
   * @param input valid bitmap to union
   */
  public void add(ImmutableRoaringBitmap input) {
    bitmap.lazyor(Objects.requireNonNull(input));
  }

  /**
   * Returns the valid accumulated bitmap and resets this union to empty. Later operations cannot
   * modify the returned bitmap.
   *
   * @return the accumulated bitmap
   */
  public MutableRoaringBitmap take() {
    bitmap.repairAfterLazy();
    MutableRoaringBitmap result = bitmap;
    bitmap = new MutableRoaringBitmap();
    return result;
  }
}
