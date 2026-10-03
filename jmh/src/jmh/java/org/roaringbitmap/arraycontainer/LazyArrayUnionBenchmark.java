package org.roaringbitmap.arraycontainer;

import org.roaringbitmap.ArrayContainer;
import org.roaringbitmap.Container;
import org.roaringbitmap.buffer.MappeableArrayContainer;
import org.roaringbitmap.buffer.MappeableContainer;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

import java.util.BitSet;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * Folds many small array containers into one array receiver: the per-container shape of a lazy
 * union over hash-spread inputs, where every container stays far below {@code
 * ARRAY_LAZY_LOWERBOUND} (1024).
 *
 * <p>{@code lazyIOR} is the in-place lazy union used by {@code RoaringBitmap.lazyor} and the
 * {@code FastAggregation} unions, {@code ior} is the eager in-place union used by
 * {@code RoaringBitmap.or}, and {@code lazyOR} allocates a new container per input, which is what
 * {@code lazyIOR} did for array pairs before the array arm merged in place. With 128 inputs of 8
 * values the fold crosses the bound on its last input and promotes once.
 *
 * <p>The receiver is cloned in a per-invocation setup so the timed region holds only the unions.
 * JMH therefore timestamps every invocation instead of batching them, and for a body this short
 * (tens of microseconds) that overhead is a visible share of every arm's absolute time: read the
 * arms against each other rather than as absolute costs.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Thread)
public class LazyArrayUnionBenchmark {

  @Param({"64", "128"})
  public int inputs;

  @Param({"1", "8"})
  public int valuesPerInput;

  private ArrayContainer heapTemplate;
  private ArrayContainer[] heapInputs;
  private MappeableArrayContainer bufferTemplate;
  private MappeableArrayContainer[] bufferInputs;

  private Container heapReceiver;
  private MappeableContainer bufferReceiver;

  private static char[] randomValues(Random random, int count) {
    BitSet chosen = new BitSet(1 << 16);
    int n = 0;
    while (n < count) {
      int v = random.nextInt(1 << 16);
      if (!chosen.get(v)) {
        chosen.set(v);
        n++;
      }
    }
    char[] values = new char[count];
    int k = 0;
    for (int v = chosen.nextSetBit(0); v >= 0; v = chosen.nextSetBit(v + 1)) {
      values[k++] = (char) v;
    }
    return values;
  }

  @Setup(Level.Trial)
  public void setupTrial() {
    Random random = new Random(42);
    char[] seed = randomValues(random, valuesPerInput);
    heapTemplate = new ArrayContainer(seed.length, seed);
    bufferTemplate = new MappeableArrayContainer(heapTemplate);
    heapInputs = new ArrayContainer[inputs];
    bufferInputs = new MappeableArrayContainer[inputs];
    for (int i = 0; i < inputs; i++) {
      char[] values = randomValues(random, valuesPerInput);
      heapInputs[i] = new ArrayContainer(values.length, values);
      bufferInputs[i] = new MappeableArrayContainer(heapInputs[i]);
    }
    // Validate once, outside the timed region, that every arm computes the same container.
    setupInvocation();
    Container expected = heapTemplate.clone();
    for (ArrayContainer input : heapInputs) {
      expected = expected.ior(input);
    }
    Container heapLazy = heapReceiver;
    for (ArrayContainer input : heapInputs) {
      heapLazy = heapLazy.lazyIOR(input);
    }
    heapLazy = heapLazy.repairAfterLazy();
    Container heapLazyOr = heapTemplate.clone();
    for (ArrayContainer input : heapInputs) {
      heapLazyOr = heapLazyOr.lazyOR(input);
    }
    heapLazyOr = heapLazyOr.repairAfterLazy();
    MappeableContainer bufferLazy = bufferReceiver;
    for (MappeableArrayContainer input : bufferInputs) {
      bufferLazy = bufferLazy.lazyIOR(input);
    }
    bufferLazy = bufferLazy.repairAfterLazy();
    MappeableContainer bufferEager = bufferTemplate.clone();
    for (MappeableArrayContainer input : bufferInputs) {
      bufferEager = bufferEager.ior(input);
    }
    if (!expected.equals(heapLazy)
        || !expected.equals(heapLazyOr)
        || expected.getCardinality() != bufferLazy.getCardinality()
        || !bufferLazy.equals(bufferEager)) {
      throw new IllegalStateException("lazy and eager unions differ for inputs=" + inputs);
    }
  }

  @Setup(Level.Invocation)
  public void setupInvocation() {
    heapReceiver = heapTemplate.clone();
    bufferReceiver = bufferTemplate.clone();
  }

  @Benchmark
  public int lazyIOR() {
    Container receiver = heapReceiver;
    for (ArrayContainer input : heapInputs) {
      receiver = receiver.lazyIOR(input);
    }
    return receiver.repairAfterLazy().getCardinality();
  }

  @Benchmark
  public int ior() {
    Container receiver = heapReceiver;
    for (ArrayContainer input : heapInputs) {
      receiver = receiver.ior(input);
    }
    return receiver.getCardinality();
  }

  @Benchmark
  public int lazyOR() {
    Container receiver = heapReceiver;
    for (ArrayContainer input : heapInputs) {
      receiver = receiver.lazyOR(input);
    }
    return receiver.repairAfterLazy().getCardinality();
  }

  @Benchmark
  public int lazyIORBuffer() {
    MappeableContainer receiver = bufferReceiver;
    for (MappeableArrayContainer input : bufferInputs) {
      receiver = receiver.lazyIOR(input);
    }
    return receiver.repairAfterLazy().getCardinality();
  }

  @Benchmark
  public int iorBuffer() {
    MappeableContainer receiver = bufferReceiver;
    for (MappeableArrayContainer input : bufferInputs) {
      receiver = receiver.ior(input);
    }
    return receiver.getCardinality();
  }

  @Benchmark
  public int lazyORBuffer() {
    MappeableContainer receiver = bufferReceiver;
    for (MappeableArrayContainer input : bufferInputs) {
      receiver = receiver.lazyOR(input);
    }
    return receiver.repairAfterLazy().getCardinality();
  }
}
