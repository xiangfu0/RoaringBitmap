package org.roaringbitmap;

import org.roaringbitmap.buffer.ImmutableRoaringBitmap;
import org.roaringbitmap.buffer.MutableRoaringBitmap;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

import java.nio.ByteBuffer;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * Measures folding inputs into a large receiver with the in-place {@code or}. The receiver is
 * cloned from a template in a per-invocation setup, so only the unions are timed.
 *
 * <p>The receiver holds {@code containers} keys (the even keys {@code 0, 2, 4, ...}, two values
 * each) unless stated otherwise. Patterns:
 *
 * <ul>
 *   <li>{@code singleton}: 256 inputs of one value in a random existing receiver key. Isolates
 *       the singleton insert into an array receiver (plus galloping to the key).
 *   <li>{@code array}: 256 inputs of three values in a random existing key. Isolates galloping;
 *       the container union itself is the regular array {@code ior}.
 *   <li>{@code interleaved}: 256 inputs of 16 odd keys, no odd key shared between inputs. For
 *       every input the receiver-only branch runs once (to the first receiver key past the
 *       input's first key) and the rest is one bulk merge; the receiver grows by 16 keys per
 *       input.
 *   <li>{@code mostlyMissing}: inputs of 200 keys, 100 random even keys (present) and 100 odd
 *       keys no other input uses (absent), so half or more of every input's keys are missing
 *       throughout the fold: galloping between the matches, then one bulk merge per input. The
 *       fold has {@code min(256, containers / 100)} inputs so that the odd keys stay disjoint.
 *   <li>{@code receiverSmaller}: one input with 64 times more keys than the receiver, whose keys
 *       are a subset of the input's. Dominated by bulk insertion into a small receiver.
 *   <li>{@code ratio1}, {@code ratio2}, {@code ratio4}, {@code ratio8}, {@code ratio64}: one
 *       input whose keys are every k-th receiver key, all present, three values each. The
 *       receiver-only branch runs at gap k-1 between matches; {@code ratio1} is the
 *       identical-key control where that branch never runs.
 * </ul>
 *
 * <p>{@code receiverSmaller} and the {@code ratio} patterns time a single union per invocation.
 * The per-invocation setup makes JMH timestamp every call, so those numbers carry a small fixed
 * overhead that matters most at 4096 containers, where one union is short; compare them within
 * one {@code containers} setting.
 *
 * <p>{@code flavor} selects the heap {@link RoaringBitmap} or the buffer {@link
 * MutableRoaringBitmap} receiver; buffer inputs are {@link ImmutableRoaringBitmap} views over
 * serialized bytes, as a memory-mapped source would be.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Thread)
public class SparseInPlaceOrBenchmark {

  private static final int FOLD_INPUTS = 256;

  @Param({"heap", "buffer"})
  public String flavor;

  @Param({"4096", "32768"})
  public int containers;

  @Param({
    "singleton",
    "array",
    "interleaved",
    "mostlyMissing",
    "receiverSmaller",
    "ratio1",
    "ratio2",
    "ratio4",
    "ratio8",
    "ratio64"
  })
  public String pattern;

  private boolean heap;

  private RoaringBitmap heapTemplate;
  private RoaringBitmap[] heapInputs;
  private RoaringBitmap heapAccumulator;

  private MutableRoaringBitmap bufferTemplate;
  private ImmutableRoaringBitmap[] bufferInputs;
  private MutableRoaringBitmap bufferAccumulator;

  @Setup(Level.Trial)
  public void setup() {
    heap = "heap".equals(flavor);
    heapTemplate = new RoaringBitmap();
    boolean receiverSmaller = "receiverSmaller".equals(pattern);
    int receiverKeys = receiverSmaller ? containers / 64 : containers;
    int receiverStep = receiverSmaller ? 128 : 2;
    for (int i = 0; i < receiverKeys; i++) {
      int key = i * receiverStep;
      heapTemplate.add((key << 16) | 1);
      heapTemplate.add((key << 16) | 2);
    }

    Random random = new Random(73);
    if (pattern.startsWith("ratio")) {
      int ratio = Integer.parseInt(pattern.substring("ratio".length()));
      heapInputs = new RoaringBitmap[] {new RoaringBitmap()};
      for (int j = 0; j < containers; j += ratio) {
        addValues(heapInputs[0], 2 * j, 3, 3);
      }
    } else if (receiverSmaller) {
      heapInputs = new RoaringBitmap[] {new RoaringBitmap()};
      for (int j = 0; j < containers; j++) {
        addValues(heapInputs[0], 2 * j, 3, 3);
      }
    } else {
      // The odd keys are absent from the receiver. interleaved and mostlyMissing hand each input
      // its own slice of a shuffled permutation of them, so a key is inserted at most once during
      // the fold and every input still misses the receiver as the javadoc describes.
      boolean interleaved = "interleaved".equals(pattern);
      boolean mostlyMissing = "mostlyMissing".equals(pattern);
      int oddKeysPerInput = interleaved ? 16 : mostlyMissing ? 100 : 0;
      int[] oddKeys = oddKeysPerInput == 0 ? null : shuffledOddKeys(containers, random);
      int inputs =
          oddKeysPerInput == 0 ? FOLD_INPUTS : Math.min(FOLD_INPUTS, containers / oddKeysPerInput);
      heapInputs = new RoaringBitmap[inputs];
      for (int i = 0; i < inputs; i++) {
        RoaringBitmap input = new RoaringBitmap();
        if ("singleton".equals(pattern)) {
          addValues(input, 2 * random.nextInt(containers), 3 + i, 1);
        } else if ("array".equals(pattern)) {
          addValues(input, 2 * random.nextInt(containers), 3 + 3 * i, 3);
        } else if (interleaved) {
          for (int j = 0; j < oddKeysPerInput; j++) {
            addValues(input, oddKeys[oddKeysPerInput * i + j], 3 + i, 1);
          }
        } else if (mostlyMissing) {
          for (int j = 0; j < oddKeysPerInput; j++) {
            addValues(input, 2 * random.nextInt(containers), 3 + i, 1);
            addValues(input, oddKeys[oddKeysPerInput * i + j], 3 + i, 1);
          }
        } else {
          throw new IllegalArgumentException("unknown pattern " + pattern);
        }
        heapInputs[i] = input;
      }
    }

    if (heap) {
      // Validate the fold outside the measured region using the independent static union.
      RoaringBitmap expected = heapTemplate.clone();
      RoaringBitmap actual = heapTemplate.clone();
      for (RoaringBitmap input : heapInputs) {
        expected = RoaringBitmap.or(expected, input);
        actual.or(input);
      }
      if (!expected.equals(actual)) {
        throw new IllegalStateException("in-place union differs from static union");
      }
    } else {
      bufferTemplate = toBufferView(heapTemplate).toMutableRoaringBitmap();
      bufferInputs = new ImmutableRoaringBitmap[heapInputs.length];
      for (int i = 0; i < heapInputs.length; i++) {
        bufferInputs[i] = toBufferView(heapInputs[i]);
      }
      MutableRoaringBitmap expected = bufferTemplate.clone();
      MutableRoaringBitmap actual = bufferTemplate.clone();
      for (ImmutableRoaringBitmap input : bufferInputs) {
        expected = MutableRoaringBitmap.or(expected, input);
        actual.or(input);
      }
      if (!expected.equals(actual)) {
        throw new IllegalStateException("in-place buffer union differs from static union");
      }
      heapTemplate = null;
      heapInputs = null;
    }
  }

  @Setup(Level.Invocation)
  public void cloneReceiver() {
    if (heap) {
      heapAccumulator = heapTemplate.clone();
    } else {
      bufferAccumulator = bufferTemplate.clone();
    }
  }

  @Benchmark
  public Object fold() {
    if (heap) {
      RoaringBitmap accumulator = heapAccumulator;
      for (RoaringBitmap input : heapInputs) {
        accumulator.or(input);
      }
      return accumulator;
    }
    MutableRoaringBitmap accumulator = bufferAccumulator;
    for (ImmutableRoaringBitmap input : bufferInputs) {
      accumulator.or(input);
    }
    return accumulator;
  }

  /** The odd keys below {@code 2 * containers}, none of them in the receiver, in random order. */
  private static int[] shuffledOddKeys(int containers, Random random) {
    int[] keys = new int[containers];
    for (int i = 0; i < containers; i++) {
      keys[i] = 2 * i + 1;
    }
    for (int i = containers - 1; i > 0; i--) {
      int j = random.nextInt(i + 1);
      int swap = keys[i];
      keys[i] = keys[j];
      keys[j] = swap;
    }
    return keys;
  }

  private static void addValues(RoaringBitmap bitmap, int key, int firstLow, int count) {
    for (int v = 0; v < count; v++) {
      bitmap.add((key << 16) | (firstLow + v));
    }
  }

  private static ImmutableRoaringBitmap toBufferView(RoaringBitmap bitmap) {
    ByteBuffer buffer = ByteBuffer.allocate(bitmap.serializedSizeInBytes());
    bitmap.serialize(buffer);
    buffer.flip();
    return new ImmutableRoaringBitmap(buffer);
  }
}
