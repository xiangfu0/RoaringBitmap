/*
 * (c) the authors Licensed under the Apache License, Version 2.0.
 */

package org.roaringbitmap;

import java.util.Objects;

/**
 * Incremental union accumulator: folds {@link RoaringBitmap} inputs that arrive over time into a
 * single bitmap, using the library's lazy union internally and normalizing the result only when it
 * is read.
 *
 * <p>Each {@link #add(RoaringBitmap)} merges the input with the internal lazy union ({@code
 * lazyor}): bitmap containers absorb inputs without cardinality bookkeeping, run containers absorb
 * array inputs without being re-normalized, and array containers unioned with array inputs stay
 * arrays until their combined cardinality exceeds 1024 values. The pending state is repaired once
 * ({@code repairAfterLazy}) by the next {@link #get()} or {@link #take()}, so callers only ever
 * observe valid bitmaps.
 *
 * <p>When to use this class rather than the alternatives:
 *
 * <ul>
 *   <li>{@code accumulator.or(input)} in a loop maintains exact cardinalities after every input
 *       (per-value bookkeeping when array inputs land in bitmap containers, a full recount for
 *       bitmap-on-bitmap merges) and re-normalizes run containers after every merge. This class
 *       defers that work to the read, which matters when many small inputs land in the same
 *       containers.
 *   <li>{@link FastAggregation#or(RoaringBitmap...)}, {@link
 *       FastAggregation#naive_or(RoaringBitmap...)} and {@link
 *       FastAggregation#priorityqueue_or(RoaringBitmap...)} need all inputs in one call. This
 *       class serves inputs that arrive across separate calls, possibly with reads in between
 *       (bitmaps deserialized row by row, posting lists, per-group folds), where no single-call
 *       aggregation fits.
 *   <li>{@code naive_or} promotes every overlapping receiver container to an 8 KiB bitmap
 *       container up front, which retains a lot of transient memory when inputs are spread thinly
 *       over many containers. The lazy union keeps array containers as arrays until their combined
 *       cardinality exceeds 1024 values, so sparse folds stay compact until they are dense.
 * </ul>
 *
 * <p>Usage:
 *
 * <pre>{@code
 * RoaringBitmapUnion union = new RoaringBitmapUnion();
 * for (RoaringBitmap posting : postings) {
 *   union.add(posting); // posting is neither modified nor retained
 * }
 * union.add(42);
 * RoaringBitmap result = union.take(); // valid (validate() holds), owned by the caller
 * }</pre>
 *
 * <p>Ownership and aliasing rules, stated precisely on each method:
 *
 * <ul>
 *   <li>{@link #add(RoaringBitmap)} never modifies or retains its argument.
 *   <li>{@link #get()} returns the accumulated bitmap without copying. The union will never modify
 *       that instance again: its next mutating call first copies it (copy on write). The returned
 *       bitmap therefore stays valid indefinitely; callers must treat it as read-only while they
 *       still intend to use the union.
 *   <li>{@link #take()} transfers ownership of the accumulated bitmap and leaves the union empty.
 *   <li>{@link #takeOwnership(RoaringBitmap)} adopts an existing bitmap without copying it.
 * </ul>
 *
 * <p>Instances are not thread-safe. Values never change through normalization; only the container
 * representation may (for instance run containers are re-encoded into their most compact form).
 */
public final class RoaringBitmapUnion {

  /** The accumulated bitmap. Never null. */
  private RoaringBitmap bitmap;

  /** True when {@link #bitmap} holds lazy state that must be repaired before it is read. */
  private boolean dirty;

  /** True when an alias returned by {@link #get()} is outstanding and must not be mutated. */
  private boolean published;

  /** Creates an empty union. */
  public RoaringBitmapUnion() {
    this.bitmap = new RoaringBitmap();
  }

  private RoaringBitmapUnion(RoaringBitmap adopted) {
    this.bitmap = adopted;
    // Adopted state is normalized on the first read so that get()/take() always return canonical
    // container forms, whatever the caller built.
    this.dirty = true;
  }

  /**
   * Creates a union that adopts {@code bitmap} as its initial state without copying it.
   *
   * <p>The caller relinquishes the instance: it must not be used again except through the union,
   * because the union mutates it in place (and lazily, so it may be structurally invalid between
   * calls). The adopted bitmap is returned in canonical container form by {@link #get()} and
   * {@link #take()}: repairing re-normalizes run containers, so the representation may change
   * while the values never do.
   *
   * <p>Only plain {@link RoaringBitmap} instances are accepted. Subclasses such as {@link
   * FastRankRoaringBitmap} (rank cache not invalidated by lazy unions) or copy-on-write bitmaps
   * (containers aliased with a source) are unsafe to adopt and are rejected.
   *
   * @param bitmap the bitmap to adopt
   * @return a union whose current state is {@code bitmap}
   * @throws NullPointerException if {@code bitmap} is null
   * @throws IllegalArgumentException if {@code bitmap} is an instance of a subclass
   */
  public static RoaringBitmapUnion takeOwnership(RoaringBitmap bitmap) {
    Objects.requireNonNull(bitmap, "bitmap");
    if (bitmap.getClass() != RoaringBitmap.class) {
      throw new IllegalArgumentException(
          "Cannot take ownership of a "
              + bitmap.getClass().getName()
              + ": only plain "
              + RoaringBitmap.class.getName()
              + " instances are safe to union lazily");
    }
    return new RoaringBitmapUnion(bitmap);
  }

  /**
   * Unions {@code input} into the accumulated state.
   *
   * <p>The input is neither modified nor retained: source-only containers are cloned and shared
   * containers are merged into the union's own containers, so the input may be reused or mutated
   * afterwards. Subclass instances are accepted (they are only read). Adding this union's own
   * {@link #get()} result, or an empty bitmap, is a no-op. The input must be a valid bitmap, which
   * is always the case for bitmaps obtained through the public API.
   *
   * @param input the bitmap to union in
   * @throws NullPointerException if {@code input} is null
   */
  public void add(RoaringBitmap input) {
    Objects.requireNonNull(input, "input");
    if (input == bitmap || input.isEmpty()) {
      return;
    }
    beforeMutation();
    dirty = true;
    bitmap.lazyor(input);
  }

  /**
   * Unions the single value {@code value} into the accumulated state.
   *
   * <p>This is a lazy-aware point insert: it never forces a repair of the pending state (a full
   * repair per value would cost a pass over every container). A container that is currently lazy
   * stays lazy and has the bit set directly; its cardinality is recomputed by the next repair.
   * Valid containers are updated through the regular container insert, so an array container
   * still converts to a bitmap container past 4096 values. A run container that receives a new
   * value is re-normalized by the next read: the plain insert never re-encodes it, so a stream of
   * isolated values would otherwise leave it in a form that is no longer the most compact one.
   *
   * @param value the value to add, treated as unsigned
   */
  public void add(int value) {
    beforeMutation();
    final char hb = Util.highbits(value);
    final char lb = Util.lowbits(value);
    final RoaringArray array = bitmap.highLowContainer;
    final int i = array.getIndex(hb);
    if (i >= 0) {
      final Container c = array.getContainerAtIndex(i);
      if (c instanceof BitmapContainer && c.getCardinality() < 0) {
        // Lazy bitmap container: set the bit and leave the cardinality pending. Calling add()
        // would bump the -1 marker towards 0 and the repair would then skip this container.
        ((BitmapContainer) c).bitmap[lb >>> 6] |= 1L << lb;
      } else {
        if (c instanceof RunContainer && !c.contains(lb)) {
          // RunContainer.add never re-encodes, so isolated inserts can leave a run container that
          // is no longer its most compact form; the next read re-normalizes it, as after lazyor.
          dirty = true;
        }
        array.setContainerAtIndex(i, c.add(lb));
      }
    } else {
      array.insertNewKeyValueAt(-i - 1, hb, new ArrayContainer().add(lb));
    }
    // dirty is otherwise unchanged: array and bitmap containers stay valid through add(), and a
    // lazy container stays lazy (dirty already).
  }

  /**
   * Returns the accumulated bitmap with all pending lazy state repaired, so that {@link
   * RoaringBitmap#validate()} holds: no lazy cardinalities remain, bitmap containers at array size
   * are demoted, and run containers are in their most compact encoding.
   *
   * <p>The returned instance is the union's current state, not a copy. The union will never modify
   * it again: its next mutating call ({@link #add(RoaringBitmap)} or {@link #add(int)}) first
   * copies the state, so the returned bitmap stays valid indefinitely. Callers must treat it as
   * read-only while they still intend to use the union, because modifications made before the
   * union's next mutation would be absorbed by that copy. Repeated calls without an intervening
   * mutation return the same instance.
   *
   * <p>This contract is deliberately stronger than {@code RoaringBitmapWriter.get()}: a lazily
   * unioned alias would otherwise become structurally invalid (negative cardinalities), not merely
   * stale.
   *
   * @return the accumulated bitmap
   */
  public RoaringBitmap get() {
    repairIfDirty();
    published = true;
    return bitmap;
  }

  /**
   * Returns the accumulated bitmap, repaired exactly as by {@link #get()}, and transfers its
   * ownership to the caller. The union is empty afterwards and can be reused; later additions never
   * touch the returned bitmap. {@link #get()} followed by {@code take()} returns the same instance
   * without copying.
   *
   * @return the accumulated bitmap, now owned by the caller
   */
  public RoaringBitmap take() {
    repairIfDirty();
    final RoaringBitmap result = bitmap;
    bitmap = new RoaringBitmap();
    dirty = false;
    published = false;
    return result;
  }

  private void repairIfDirty() {
    if (dirty) {
      bitmap.repairAfterLazy();
      dirty = false;
    }
  }

  private void beforeMutation() {
    if (published) {
      bitmap = bitmap.clone();
      published = false;
    }
  }

  /** For tests: whether lazy state is pending repair. */
  boolean isDirty() {
    return dirty;
  }

  /** For tests: whether an alias handed out by {@link #get()} is outstanding. */
  boolean isPublished() {
    return published;
  }
}
