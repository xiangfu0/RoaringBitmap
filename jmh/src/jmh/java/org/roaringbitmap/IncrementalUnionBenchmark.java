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
 *   <li>{@code staticOrFold}: {@code acc = RoaringBitmap.or(acc, input)} per input. It copies the
 *       whole accumulator for every input, so its cost is quadratic in the result size: one
 *       invocation copies tens of gigabytes on {@code sparse100M} and on the order of a terabyte
 *       on the full-universe shapes. It therefore runs on {@code dense2M} and {@code
 *       singleton100M} only, through its own {@link SmallInputs} state; {@code -p shape=...}
 *       forces another shape.
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
 *       container over all 65,536 containers. The heap flavour holds about 2.5 GiB of inputs
 *       (20,000 bitmaps of 2,000 single-value array containers each), which is why the fork runs
 *       with {@code -Xmx4g}; a command-line {@code -jvmArgs} replaces that setting.
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
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 1, jvmArgs = "-Xmx4g")
public class IncrementalUnionBenchmark {

  private static final long FULL_UNIVERSE = 1L << 32;

  /**
   * Inputs pre-built once per trial (heap bitmaps, or buffer views over their serialized bytes) and
   * the expected cardinality of their union. The {@code @State} subclasses only differ in the
   * shapes they offer.
   */
  public abstract static class InputSet {
    boolean heap;
    long gaugeDivisor;
    RoaringBitmap[] heapInputs;
    ImmutableRoaringBitmap[] bufferInputs;
    long expectedCardinality;

    void build(String shape, String flavor, BenchmarkParams params) {
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

    long check(long cardinality) {
      if (cardinality != expectedCardinality) {
        throw new IllegalStateException(
            "wrong cardinality " + cardinality + ", expected " + expectedCardinality);
      }
      return cardinality;
    }
  }

  /** Inputs for the arms whose cost is linear in the total input size: every shape. */
  @State(Scope.Benchmark)
  public static class Inputs extends InputSet {
    @Param({"dense2M", "sparse100M", "hashSpread", "hashSpreadDense", "singleton100M"})
    public String shape;

    @Param({"heap", "buffer"})
    public String flavor;

    @Setup(Level.Trial)
    public void setup(BenchmarkParams params) {
      build(shape, flavor, params);
    }
  }

  /**
   * Inputs for {@link #staticOrFold(SmallInputs)}, which is quadratic in the result size: the two
   * shapes whose result stays small.
   */
  @State(Scope.Benchmark)
  public static class SmallInputs extends InputSet {
    @Param({"dense2M", "singleton100M"})
    public String shape;

    @Param({"heap", "buffer"})
    public String flavor;

    @Setup(Level.Trial)
    public void setup(BenchmarkParams params) {
      build(shape, flavor, params);
    }
  }

  /** Retained-size gauges for the {@code *Retained} arms; see the class comment. */
  @State(Scope.Thread)
  @AuxCounters(AuxCounters.Type.EVENTS)
  public static class RetainedBytes {
    /** {@code getLongSizeInBytes()} of the accumulator right before {@code repairAfterLazy}. */
    public long beforeRepair;

    /** {@code getLongSizeInBytes()} of the repaired result. */
    public long afterRepair;
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

  @Benchmark
  public long orLoop(Inputs inputs) {
    if (inputs.heap) {
      RoaringBitmap acc = new RoaringBitmap();
      for (RoaringBitmap input : inputs.heapInputs) {
        acc.or(input);
      }
      return inputs.check(acc.getLongCardinality());
    }
    MutableRoaringBitmap acc = new MutableRoaringBitmap();
    for (ImmutableRoaringBitmap input : inputs.bufferInputs) {
      acc.or(input);
    }
    return inputs.check(acc.getLongCardinality());
  }

  @Benchmark
  public long union(Inputs inputs) {
    if (inputs.heap) {
      RoaringBitmapUnion union = new RoaringBitmapUnion();
      for (RoaringBitmap input : inputs.heapInputs) {
        union.add(input);
      }
      return inputs.check(union.take().getLongCardinality());
    }
    MutableRoaringBitmapUnion union = new MutableRoaringBitmapUnion();
    for (ImmutableRoaringBitmap input : inputs.bufferInputs) {
      union.add(input);
    }
    return inputs.check(union.take().getLongCardinality());
  }

  @Benchmark
  public long naiveOr(Inputs inputs) {
    if (inputs.heap) {
      return inputs.check(FastAggregation.naive_or(inputs.heapInputs).getLongCardinality());
    }
    return inputs.check(BufferFastAggregation.naive_or(inputs.bufferInputs).getLongCardinality());
  }

  @Benchmark
  public long priorityQueueOr(Inputs inputs) {
    if (inputs.heap) {
      return inputs.check(FastAggregation.priorityqueue_or(inputs.heapInputs).getLongCardinality());
    }
    return inputs.check(
        BufferFastAggregation.priorityqueue_or(inputs.bufferInputs).getLongCardinality());
  }

  @Benchmark
  public long staticOrFold(SmallInputs inputs) {
    if (inputs.heap) {
      RoaringBitmap acc = new RoaringBitmap();
      for (RoaringBitmap input : inputs.heapInputs) {
        acc = RoaringBitmap.or(acc, input);
      }
      return inputs.check(acc.getLongCardinality());
    }
    MutableRoaringBitmap acc = new MutableRoaringBitmap();
    for (ImmutableRoaringBitmap input : inputs.bufferInputs) {
      acc = ImmutableRoaringBitmap.or(acc, input);
    }
    return inputs.check(acc.getLongCardinality());
  }

  @Benchmark
  public long unionRetained(Inputs inputs, RetainedBytes gauge) {
    if (inputs.heap) {
      RoaringBitmap acc = new RoaringBitmap();
      for (RoaringBitmap input : inputs.heapInputs) {
        acc.lazyor(input); // what RoaringBitmapUnion.add does per input
      }
      gauge.beforeRepair = acc.getLongSizeInBytes() / inputs.gaugeDivisor;
      acc.repairAfterLazy();
      gauge.afterRepair = acc.getLongSizeInBytes() / inputs.gaugeDivisor;
      return inputs.check(acc.getLongCardinality());
    }
    MutableRoaringBitmap acc = new MutableRoaringBitmap();
    for (ImmutableRoaringBitmap input : inputs.bufferInputs) {
      LazyUnionAccess.lazyor(acc, input);
    }
    gauge.beforeRepair = acc.getLongSizeInBytes() / inputs.gaugeDivisor;
    LazyUnionAccess.repairAfterLazy(acc);
    gauge.afterRepair = acc.getLongSizeInBytes() / inputs.gaugeDivisor;
    return inputs.check(acc.getLongCardinality());
  }

  @Benchmark
  public long naiveOrRetained(Inputs inputs, RetainedBytes gauge) {
    if (inputs.heap) {
      RoaringBitmap acc = new RoaringBitmap();
      for (RoaringBitmap input : inputs.heapInputs) {
        acc.naivelazyor(input); // what FastAggregation.naive_or does per input
      }
      gauge.beforeRepair = acc.getLongSizeInBytes() / inputs.gaugeDivisor;
      acc.repairAfterLazy();
      gauge.afterRepair = acc.getLongSizeInBytes() / inputs.gaugeDivisor;
      return inputs.check(acc.getLongCardinality());
    }
    MutableRoaringBitmap acc = new MutableRoaringBitmap();
    for (ImmutableRoaringBitmap input : inputs.bufferInputs) {
      LazyUnionAccess.naivelazyor(acc, input);
    }
    gauge.beforeRepair = acc.getLongSizeInBytes() / inputs.gaugeDivisor;
    LazyUnionAccess.repairAfterLazy(acc);
    gauge.afterRepair = acc.getLongSizeInBytes() / inputs.gaugeDivisor;
    return inputs.check(acc.getLongCardinality());
  }
}
