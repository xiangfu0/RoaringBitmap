package org.roaringbitmap;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * Benchmarks the in-place union/xor/lazy-union merge paths of {@link RoaringBitmap}.
 *
 * <p>The {@code interleaved} pattern (left holds the even high-keys, right the odd ones) forces a
 * source-only container to be inserted between every pair of receiver containers -- the quadratic
 * case a single bulk merge pass should win. The {@code append} pattern (right's keys all follow
 * left's) is a control: it never inserts in the interior, so both strategies take the tail path.
 * The {@code singleEarlyInsert} pattern (right has exactly one key, missing from the receiver near
 * its start) is the adverse case for the bulk pass: a per-key insert shifts the receiver once,
 * whereas the bulk pass reallocates the receiver's arrays. The {@code random} pattern (left and
 * right are equal-size random key sets over twice as many keys) is the shape a priority-queue
 * aggregation produces: source-only keys, receiver-only keys and shared keys in random order. The
 * {@code sparseInput} pattern (right has eight keys, all present in the receiver and spread evenly)
 * exercises only the receiver-only walk: galloping over the gaps versus stepping key by key.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
public class InPlaceMergeBenchmark {

  @Param({"64", "1024", "4096", "16384"})
  public int containers;

  @Param({"interleaved", "append", "singleEarlyInsert", "random", "sparseInput"})
  public String pattern;

  // Templates: cloned per invocation because or/xor mutate the receiver. Clone cost is identical
  // for every merge strategy, so it does not bias the comparison.
  private RoaringBitmap leftTemplate;
  private RoaringBitmap right;

  private static RoaringBitmap withKeys(int start, int count, int step, int low) {
    RoaringBitmap b = new RoaringBitmap();
    for (int i = 0; i < count; i++) {
      int key = start + i * step;
      b.add((key << 16) | low);
    }
    return b;
  }

  private static RoaringBitmap withKeys(int[] keys, int low) {
    RoaringBitmap b = new RoaringBitmap();
    for (int key : keys) {
      b.add((key << 16) | low);
    }
    return b;
  }

  /** {@code count} distinct keys drawn from {@code [0, bound)}, in any order. */
  private static int[] randomKeys(Random random, int count, int bound) {
    int[] all = new int[bound];
    for (int i = 0; i < bound; i++) {
      all[i] = i;
    }
    // partial Fisher-Yates shuffle: the first count entries are a uniform random subset
    for (int i = 0; i < count; i++) {
      int j = i + random.nextInt(bound - i);
      int tmp = all[i];
      all[i] = all[j];
      all[j] = tmp;
    }
    int[] keys = new int[count];
    System.arraycopy(all, 0, keys, 0, count);
    return keys;
  }

  @Setup
  public void setup() {
    if ("interleaved".equals(pattern)) {
      // left: 0,2,4,...   right: 1,3,5,...  -> one interior insert per source container
      leftTemplate = withKeys(0, containers, 2, 5);
      right = withKeys(1, containers, 2, 7);
    } else if ("append".equals(pattern)) {
      // right's keys all follow left's -> pure tail append, no interior work
      leftTemplate = withKeys(0, containers, 1, 5);
      right = withKeys(containers, containers, 1, 7);
    } else if ("singleEarlyInsert".equals(pattern)) {
      // left: 0,2,3,4,...,containers   right: 1  -> a single insert near the receiver's start
      int[] keys = new int[containers];
      keys[0] = 0;
      for (int i = 1; i < containers; i++) {
        keys[i] = i + 1;
      }
      leftTemplate = withKeys(keys, 5);
      right = withKeys(1, 1, 1, 7);
    } else if ("random".equals(pattern)) {
      // equal-size random key sets over 0..2*containers: about half the keys are shared
      Random random = new Random(42);
      leftTemplate = withKeys(randomKeys(random, containers, 2 * containers), 5);
      right = withKeys(randomKeys(random, containers, 2 * containers), 7);
    } else if ("sparseInput".equals(pattern)) {
      // right has 8 keys that all exist in the receiver, spread evenly: only the receiver-only walk
      // is exercised (galloping versus stepping), no insert and no bulk merge
      leftTemplate = withKeys(0, containers, 1, 5);
      right = withKeys(0, 8, Math.max(1, containers / 8), 7);
    } else {
      throw new IllegalArgumentException("unknown pattern " + pattern);
    }
    // Validate once, outside the timed region, that every lazy arm agrees with the eager union.
    RoaringBitmap expected = leftTemplate.clone();
    expected.or(right);
    RoaringBitmap lazy = leftTemplate.clone();
    lazy.lazyor(right);
    lazy.repairAfterLazy();
    RoaringBitmap naive = leftTemplate.clone();
    naive.naivelazyor(right);
    naive.repairAfterLazy();
    if (!expected.equals(lazy) || !expected.equals(naive)) {
      throw new IllegalStateException(
          "lazy unions differ from the eager union for pattern " + pattern);
    }
  }

  @Benchmark
  public int or() {
    RoaringBitmap receiver = leftTemplate.clone();
    receiver.or(right);
    return receiver.getCardinality();
  }

  @Benchmark
  public int xor() {
    RoaringBitmap receiver = leftTemplate.clone();
    receiver.xor(right);
    return receiver.getCardinality();
  }

  @Benchmark
  public int lazyor() {
    RoaringBitmap receiver = leftTemplate.clone();
    receiver.lazyor(right);
    receiver.repairAfterLazy();
    return receiver.getCardinality();
  }

  @Benchmark
  public int naivelazyor() {
    RoaringBitmap receiver = leftTemplate.clone();
    receiver.naivelazyor(right);
    receiver.repairAfterLazy();
    return receiver.getCardinality();
  }
}
