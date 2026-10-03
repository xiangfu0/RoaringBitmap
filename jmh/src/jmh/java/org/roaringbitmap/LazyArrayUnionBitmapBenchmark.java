package org.roaringbitmap;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * Folds many bitmaps made of small array containers into one receiver: through the lazy union
 * ({@code lazyor} per input, one {@code repairAfterLazy} at the end), through the eager {@code or},
 * and through the promoting {@code naivelazyor} that backs {@code FastAggregation.naive_or}.
 *
 * <p>With 8 values per container per input and 64 inputs every container stays an array below
 * {@code ARRAY_LAZY_LOWERBOUND}, so the lazy arm measures the in-place array merge of {@code
 * lazyIOR}; before it, every input allocated a new array container for each touched key. The
 * receiver is cloned per invocation so the timed region holds only the unions. This lives in
 * {@code org.roaringbitmap} because the lazy methods are protected.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Thread)
public class LazyArrayUnionBitmapBenchmark {

  @Param({"1000"})
  public int containers;

  @Param({"64"})
  public int inputs;

  @Param({"8"})
  public int valuesPerContainer;

  private RoaringBitmap template;
  private RoaringBitmap[] sources;
  private RoaringBitmap receiver;

  private RoaringBitmap randomBitmap(Random random) {
    RoaringBitmap bitmap = new RoaringBitmap();
    for (int key = 0; key < containers; key++) {
      for (int i = 0; i < valuesPerContainer; i++) {
        bitmap.add((key << 16) | random.nextInt(1 << 16));
      }
    }
    return bitmap;
  }

  @Setup(Level.Trial)
  public void setupTrial() {
    Random random = new Random(42);
    template = randomBitmap(random);
    sources = new RoaringBitmap[inputs];
    for (int i = 0; i < inputs; i++) {
      sources[i] = randomBitmap(random);
    }
    // Validate once, outside the timed region, that every arm computes the same bitmap.
    RoaringBitmap expected = template.clone();
    RoaringBitmap lazy = template.clone();
    RoaringBitmap naive = template.clone();
    for (RoaringBitmap source : sources) {
      expected.or(source);
      lazy.lazyor(source);
      naive.naivelazyor(source);
    }
    lazy.repairAfterLazy();
    naive.repairAfterLazy();
    if (!expected.equals(lazy) || !expected.equals(naive)) {
      throw new IllegalStateException("lazy unions differ from the eager union");
    }
  }

  @Setup(Level.Invocation)
  public void setupInvocation() {
    receiver = template.clone();
  }

  @Benchmark
  public int lazyor() {
    for (RoaringBitmap source : sources) {
      receiver.lazyor(source);
    }
    receiver.repairAfterLazy();
    return receiver.getCardinality();
  }

  @Benchmark
  public int or() {
    for (RoaringBitmap source : sources) {
      receiver.or(source);
    }
    return receiver.getCardinality();
  }

  @Benchmark
  public int naivelazyor() {
    for (RoaringBitmap source : sources) {
      receiver.naivelazyor(source);
    }
    receiver.repairAfterLazy();
    return receiver.getCardinality();
  }
}
