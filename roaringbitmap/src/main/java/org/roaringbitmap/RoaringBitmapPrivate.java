package org.roaringbitmap;

/**
 * This class enables accessing/executing not-public methods.
 * Its usage should be reserved to very specific cases, and
 * given should not be considered as part of the official API.
 */
@Deprecated
public class RoaringBitmapPrivate {

  /**
   * Lazily unions {@code x2} into {@code x1} without eagerly converting every overlapping container
   * into bitmap form.
   * The caller must exclusively own {@code x1} and call {@link #repairAfterLazy(RoaringBitmap)}
   * before reading or serializing it. Further lazy unions are allowed before repair.
   * {@code x2} must not be in a lazy state and is not modified.
   *
   * @param x1 accumulator to modify
   * @param x2 input bitmap, which must be fully repaired
   */
  public static void lazyor(RoaringBitmap x1, RoaringBitmap x2) {
    x1.lazyor(x2);
  }

  public static void naivelazyor(RoaringBitmap x1, RoaringBitmap x2) {
    x1.naivelazyor(x2);
  }

  public static void repairAfterLazy(RoaringBitmap r) {
    r.repairAfterLazy();
  }
}
