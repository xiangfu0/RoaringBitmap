package org.roaringbitmap.buffer;

/**
 * Benchmark-only access to the protected lazy-union primitives of {@link MutableRoaringBitmap}, so
 * that a benchmark in another package can observe the accumulator between the lazy fold and its
 * repair. Not part of the library API.
 */
public final class LazyUnionAccess {

  private LazyUnionAccess() {}

  /** What {@link MutableRoaringBitmapUnion#add(ImmutableRoaringBitmap)} does per input. */
  public static void lazyor(MutableRoaringBitmap accumulator, ImmutableRoaringBitmap input) {
    accumulator.lazyor(input);
  }

  /** What {@link BufferFastAggregation#naive_or(ImmutableRoaringBitmap...)} does per input. */
  public static void naivelazyor(MutableRoaringBitmap accumulator, ImmutableRoaringBitmap input) {
    accumulator.naivelazyor(input);
  }

  public static void repairAfterLazy(MutableRoaringBitmap accumulator) {
    accumulator.repairAfterLazy();
  }
}
