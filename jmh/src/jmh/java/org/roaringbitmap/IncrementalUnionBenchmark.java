package org.roaringbitmap;

import org.roaringbitmap.buffer.BufferFastAggregation;
import org.roaringbitmap.buffer.ImmutableRoaringBitmap;
import org.roaringbitmap.buffer.LazyUnionAccess;
import org.roaringbitmap.buffer.MutableRoaringBitmap;
import org.roaringbitmap.buffer.MutableRoaringBitmapUnion;

import org.openjdk.jmh.annotations.AuxCounters;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.BenchmarkParams;

import java.nio.ByteBuffer;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * Compares ways of folding many bitmaps that arrive one at a time into a single union, which is
 * what {@link RoaringBitmapUnion} and {@link MutableRoaringBitmapUnion} are for.
 *
 * <p>Arms (each returns the cardinality of the final bitmap, which is checked against the value
 * computed in setup):
 *
 * <ul>
 *   <li>{@code orLoop}: {@code accumulator.or(input)} per input (exact cardinalities maintained
 *       after every input).
 *   <li>{@code union}: {@code RoaringBitmapUnion.add(input)} per input, then {@code take()} (lazy
 *       union, one repair at the end).
 *   <li>{@code naiveOr}: {@code FastAggregation.naive_or} over all inputs (lazy union that promotes
 *       every overlapping receiver container to an 8 KiB bitmap container up front).
 *   <li>{@code priorityQueueOr}: {@code FastAggregation.priorityqueue_or} over all inputs.
 *   <li>{@code staticOrFold}: {@code acc = RoaringBitmap.or(acc, input)} per input (allocates a
 *       new result per input; quadratic in the result size and slow by design on the large shapes).
 * </ul>
 *
 * <p>Shapes (universe, values per input, input count; at most 40M values in total):
 *
 * <ul>
 *   <li>{@code dense2M}: 2,000,000 x 200 x 10,000. Few containers, all of them dense: the lazy
 *       accumulators end up with bitmap containers either way.
 *   <li>{@code sparse100M}: 100,000,000 x 200 x 10,000. About 1,500 containers with roughly 1,300
 *       values each in the final result.
 *   <li>{@code hashSpread}: full 32-bit universe x 200 x 10,000. About 31 values per container in
 *       the final result, so every container stays below ARRAY_LAZY_LOWERBOUND (1024): the regime
 *       where {@code naive_or} retains 8 KiB per container and the lazy union keeps arrays.
 *   <li>{@code hashSpreadDense}: full 32-bit universe x 2,000 x 20,000. About 610 values per
 *       container over all 65,536 containers. The heap flavour needs roughly 3 GiB of heap for the
 *       inputs alone; pass {@code -jvmArgs -Xmx4g} if the default heap is smaller.
 *   <li>{@code singleton100M}: 100,000,000 x 1 x 100,000. Point-like inputs.
 * </ul>
 *
 * <p>{@code flavor} selects the heap classes ({@code RoaringBitmap}) or the buffer classes with
 * {@code ImmutableRoaringBitmap} inputs that are views over serialized bytes, as memory-mapped
 * inputs would be.
 *
 * <p>Retained memory is the headline of the comparison between the lazy union and {@code
 * naive_or}, so the {@code unionRetained} and {@code naiveOrRetained} arms report the accumulator's
 * {@code getLongSizeInBytes()} immediately before the final repair ({@code beforeRepair}) and
 * after it ({@code afterRepair}) as auxiliary counters. They replay the same lazy folds through the
 * package-level primitives. The counters are gauges, not event counts, and JMH sums event counters
 * over measurement iterations and threads, so each arm stores {@code value / (iterations x
 * threads)} per invocation: the summary line then shows the per-fold value exactly once, whatever
 * {@code -i} and {@code -t} are, while the per-iteration lines show that fraction of it.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class IncrementalUnionBenchmark {

  private static final long FULL_UNIVERSE = 1L << 32;

  @Param({"dense2M", "sparse100M", "hashSpread", "hashSpreadDense", "singleton100M"})
  public String shape;

  @Param({"heap", "buffer"})
  public String flavor;

  private boolean heap;
  private long gaugeDivisor;
  private RoaringBitmap[] heapInputs;
  private ImmutableRoaringBitmap[] bufferInputs;
  private long expectedCardinality;

  /** Retained-size gauges for the {@code *Retained} arms; see the class comment. */
  @State(Scope.Thread)
  @AuxCounters(AuxCounters.Type.EVENTS)
  public static class RetainedBytes {
    /** {@code getLongSizeInBytes()} of the accumulator right before {@code repairAfterLazy}. */
    public long beforeRepair;

    /** {@code getLongSizeInBytes()} of the repaired result. */
    public long afterRepair;
  }

  @Setup(Level.Trial)
  public void setup(BenchmarkParams params) {
    heap = "heap".equals(flavor);
    gaugeDivisor =
        (long) Math.max(1, params.getMeasurement().getCount()) * Math.max(1, params.getThreads());
    final long universe;
    final int valuesPerInput;
    final int inputCount;
    switch (shape) {
      case "dense2M":
        universe = 2_000_000L;
        valuesPerInput = 200;
        inputCount = 10_000;
        break;
      case "sparse100M":
        universe = 100_000_000L;
        valuesPerInput = 200;
        inputCount = 10_000;
        break;
      case "hashSpread":
        universe = FULL_UNIVERSE;
        valuesPerInput = 200;
        inputCount = 10_000;
        break;
      case "hashSpreadDense":
        universe = FULL_UNIVERSE;
        valuesPerInput = 2_000;
        inputCount = 20_000;
        break;
      case "singleton100M":
        universe = 100_000_000L;
        valuesPerInput = 1;
        inputCount = 100_000;
        break;
      default:
        throw new IllegalArgumentException("unknown shape " + shape);
    }
    Random random = new Random(shape.hashCode());
    RoaringBitmap reference = new RoaringBitmap();
    if (heap) {
      heapInputs = new RoaringBitmap[inputCount];
    } else {
      bufferInputs = new ImmutableRoaringBitmap[inputCount];
    }
    for (int i = 0; i < inputCount; i++) {
      RoaringBitmap input = new RoaringBitmap();
      for (int j = 0; j < valuesPerInput; j++) {
        input.add(nextValue(random, universe));
      }
      if (!input.validate()) {
        throw new IllegalStateException("invalid input " + i);
      }
      reference.or(input);
      if (heap) {
        heapInputs[i] = input;
      } else {
        bufferInputs[i] = toBufferView(input);
      }
    }
    expectedCardinality = reference.getLongCardinality();
  }

  private static int nextValue(Random random, long universe) {
    if (universe == FULL_UNIVERSE) {
      return random.nextInt(); // negative ints are the high half of the unsigned universe
    }
    return (int) Math.floorMod(random.nextLong(), universe);
  }

  private static ImmutableRoaringBitmap toBufferView(RoaringBitmap bitmap) {
    ByteBuffer buffer = ByteBuffer.allocate(bitmap.serializedSizeInBytes());
    bitmap.serialize(buffer);
    buffer.flip();
    return new ImmutableRoaringBitmap(buffer);
  }

  private long check(long cardinality) {
    if (cardinality != expectedCardinality) {
      throw new IllegalStateException(
          "wrong cardinality " + cardinality + ", expected " + expectedCardinality);
    }
    return cardinality;
  }

  @Benchmark
  public long orLoop() {
    if (heap) {
      RoaringBitmap acc = new RoaringBitmap();
      for (RoaringBitmap input : heapInputs) {
        acc.or(input);
      }
      return check(acc.getLongCardinality());
    }
    MutableRoaringBitmap acc = new MutableRoaringBitmap();
    for (ImmutableRoaringBitmap input : bufferInputs) {
      acc.or(input);
    }
    return check(acc.getLongCardinality());
  }

  @Benchmark
  public long union() {
    if (heap) {
      RoaringBitmapUnion union = new RoaringBitmapUnion();
      for (RoaringBitmap input : heapInputs) {
        union.add(input);
      }
      return check(union.take().getLongCardinality());
    }
    MutableRoaringBitmapUnion union = new MutableRoaringBitmapUnion();
    for (ImmutableRoaringBitmap input : bufferInputs) {
      union.add(input);
    }
    return check(union.take().getLongCardinality());
  }

  @Benchmark
  public long naiveOr() {
    if (heap) {
      return check(FastAggregation.naive_or(heapInputs).getLongCardinality());
    }
    return check(BufferFastAggregation.naive_or(bufferInputs).getLongCardinality());
  }

  @Benchmark
  public long priorityQueueOr() {
    if (heap) {
      return check(FastAggregation.priorityqueue_or(heapInputs).getLongCardinality());
    }
    return check(BufferFastAggregation.priorityqueue_or(bufferInputs).getLongCardinality());
  }

  @Benchmark
  public long staticOrFold() {
    if (heap) {
      RoaringBitmap acc = new RoaringBitmap();
      for (RoaringBitmap input : heapInputs) {
        acc = RoaringBitmap.or(acc, input);
      }
      return check(acc.getLongCardinality());
    }
    MutableRoaringBitmap acc = new MutableRoaringBitmap();
    for (ImmutableRoaringBitmap input : bufferInputs) {
      acc = ImmutableRoaringBitmap.or(acc, input);
    }
    return check(acc.getLongCardinality());
  }

  @Benchmark
  public long unionRetained(RetainedBytes gauge) {
    if (heap) {
      RoaringBitmap acc = new RoaringBitmap();
      for (RoaringBitmap input : heapInputs) {
        acc.lazyor(input); // what RoaringBitmapUnion.add does per input
      }
      gauge.beforeRepair = acc.getLongSizeInBytes() / gaugeDivisor;
      acc.repairAfterLazy();
      gauge.afterRepair = acc.getLongSizeInBytes() / gaugeDivisor;
      return check(acc.getLongCardinality());
    }
    MutableRoaringBitmap acc = new MutableRoaringBitmap();
    for (ImmutableRoaringBitmap input : bufferInputs) {
      LazyUnionAccess.lazyor(acc, input);
    }
    gauge.beforeRepair = acc.getLongSizeInBytes() / gaugeDivisor;
    LazyUnionAccess.repairAfterLazy(acc);
    gauge.afterRepair = acc.getLongSizeInBytes() / gaugeDivisor;
    return check(acc.getLongCardinality());
  }

  @Benchmark
  public long naiveOrRetained(RetainedBytes gauge) {
    if (heap) {
      RoaringBitmap acc = new RoaringBitmap();
      for (RoaringBitmap input : heapInputs) {
        acc.naivelazyor(input); // what FastAggregation.naive_or does per input
      }
      gauge.beforeRepair = acc.getLongSizeInBytes() / gaugeDivisor;
      acc.repairAfterLazy();
      gauge.afterRepair = acc.getLongSizeInBytes() / gaugeDivisor;
      return check(acc.getLongCardinality());
    }
    MutableRoaringBitmap acc = new MutableRoaringBitmap();
    for (ImmutableRoaringBitmap input : bufferInputs) {
      LazyUnionAccess.naivelazyor(acc, input);
    }
    gauge.beforeRepair = acc.getLongSizeInBytes() / gaugeDivisor;
    LazyUnionAccess.repairAfterLazy(acc);
    gauge.afterRepair = acc.getLongSizeInBytes() / gaugeDivisor;
    return check(acc.getLongCardinality());
  }
}
