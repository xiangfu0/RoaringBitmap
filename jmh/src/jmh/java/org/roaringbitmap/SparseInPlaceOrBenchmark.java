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
 * Measures a fold of small inputs into a large accumulator, including one initial clone.
 * Interleaved keys exercise bulk insertion; a similarly sized input controls the linear path.
 * Compare revisions within each pattern: the similar-size control performs only one union.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Thread)
public class SparseInPlaceOrBenchmark {

  @Param({"4096", "32768"})
  public int containers;

  @Param({"singleton", "array", "interleaved", "similarSize"})
  public String pattern;

  private RoaringBitmap template;
  private RoaringBitmap[] inputs;

  @Setup
  public void setup() {
    template = new RoaringBitmap();
    for (int i = 0; i < containers; i++) {
      template.add((i * 2 << 16) | 1);
      template.add((i * 2 << 16) | 2);
    }
    boolean similarSize = "similarSize".equals(pattern);
    inputs = new RoaringBitmap[similarSize ? 1 : 32];
    Random random = new Random(73);
    for (int i = 0; i < inputs.length; i++) {
      inputs[i] = new RoaringBitmap();
      for (int j = 0; j < (similarSize ? containers : 16); j++) {
        int key = 2 * (similarSize ? j : random.nextInt(containers));
        if ("interleaved".equals(pattern)) {
          key++;
        }
        inputs[i].add((key << 16) | (3 + i * 4));
        if ("array".equals(pattern)) {
          inputs[i].add((key << 16) | (4 + i * 4));
          inputs[i].add((key << 16) | (5 + i * 4));
        }
      }
    }
    // Validate the fold outside the measured region using the independent static union.
    RoaringBitmap expected = template.clone();
    RoaringBitmap actual = template.clone();
    for (RoaringBitmap input : inputs) {
      expected = RoaringBitmap.or(expected, input);
      actual.or(input);
    }
    if (!expected.equals(actual)) {
      throw new IllegalStateException("In-place union differs from static union");
    }
  }

  @Benchmark
  public RoaringBitmap fold() {
    RoaringBitmap accumulator = template.clone();
    for (RoaringBitmap input : inputs) {
      accumulator.or(input);
    }
    return accumulator;
  }
}
