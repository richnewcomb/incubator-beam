/*
 * Copyright (C) 2015 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.google.cloud.dataflow.sdk.transforms;

import com.google.cloud.dataflow.sdk.coders.CannotProvideCoderException;
import com.google.cloud.dataflow.sdk.coders.Coder;
import com.google.cloud.dataflow.sdk.coders.CoderException;
import com.google.cloud.dataflow.sdk.coders.CoderRegistry;
import com.google.cloud.dataflow.sdk.coders.CustomCoder;
import com.google.cloud.dataflow.sdk.coders.DelegateCoder;
import com.google.cloud.dataflow.sdk.coders.IterableCoder;
import com.google.cloud.dataflow.sdk.coders.KvCoder;
import com.google.cloud.dataflow.sdk.coders.StandardCoder;
import com.google.cloud.dataflow.sdk.coders.VarIntCoder;
import com.google.cloud.dataflow.sdk.coders.VoidCoder;
import com.google.cloud.dataflow.sdk.transforms.CombineFnBase.AbstractGlobalCombineFn;
import com.google.cloud.dataflow.sdk.transforms.CombineFnBase.AbstractPerKeyCombineFn;
import com.google.cloud.dataflow.sdk.transforms.CombineFnBase.GlobalCombineFn;
import com.google.cloud.dataflow.sdk.transforms.CombineFnBase.PerKeyCombineFn;
import com.google.cloud.dataflow.sdk.transforms.CombineWithContext.CombineFnWithContext;
import com.google.cloud.dataflow.sdk.transforms.CombineWithContext.Context;
import com.google.cloud.dataflow.sdk.transforms.CombineWithContext.KeyedCombineFnWithContext;
import com.google.cloud.dataflow.sdk.transforms.CombineWithContext.RequiresContextInternal;
import com.google.cloud.dataflow.sdk.transforms.windowing.GlobalWindow;
import com.google.cloud.dataflow.sdk.transforms.windowing.GlobalWindows;
import com.google.cloud.dataflow.sdk.transforms.windowing.Window;
import com.google.cloud.dataflow.sdk.util.AppliedCombineFn;
import com.google.cloud.dataflow.sdk.util.PerKeyCombineFnRunner;
import com.google.cloud.dataflow.sdk.util.PerKeyCombineFnRunners;
import com.google.cloud.dataflow.sdk.util.PropertyNames;
import com.google.cloud.dataflow.sdk.util.SerializableUtils;
import com.google.cloud.dataflow.sdk.util.WindowingStrategy;
import com.google.cloud.dataflow.sdk.util.common.Counter;
import com.google.cloud.dataflow.sdk.values.KV;
import com.google.cloud.dataflow.sdk.values.PCollection;
import com.google.cloud.dataflow.sdk.values.PCollectionList;
import com.google.cloud.dataflow.sdk.values.PCollectionTuple;
import com.google.cloud.dataflow.sdk.values.PCollectionView;
import com.google.cloud.dataflow.sdk.values.TupleTag;
import com.google.cloud.dataflow.sdk.values.TupleTagList;
import com.google.cloud.dataflow.sdk.values.TypeDescriptor;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * {@code PTransform}s for combining {@code PCollection} elements
 * globally and per-key.
 *
 * <p>See the <a href="https://cloud.google.com/dataflow/model/combine">documentation</a>
 * for how to use the operations in this class.
 */
public class Combine {
  private Combine() {
    // do not instantiate
  }

  /**
   * Returns a {@link Globally Combine.Globally} {@code PTransform}
   * that uses the given {@code SerializableFunction} to combine all
   * the elements in each window of the input {@code PCollection} into a
   * single value in the output {@code PCollection}.  The types of the input
   * elements and the output elements must be the same.
   *
   * <p>If the input {@code PCollection} is windowed into {@link GlobalWindows},
   * a default value in the {@link GlobalWindow} will be output if the input
   * {@code PCollection} is empty.  To use this with inputs with other windowing,
   * either {@link Globally#withoutDefaults} or {@link Globally#asSingletonView}
   * must be called.
   *
   * <p>See {@link Globally Combine.Globally} for more information.
   */
  public static <V> Globally<V, V> globally(
      SerializableFunction<Iterable<V>, V> combiner) {
    return globally(IterableCombineFn.of(combiner));
  }

  /**
   * Returns a {@link Globally Combine.Globally} {@code PTransform}
   * that uses the given {@code GloballyCombineFn} to combine all
   * the elements in each window of the input {@code PCollection} into a
   * single value in the output {@code PCollection}.  The types of the input
   * elements and the output elements can differ.
   *
   * <p>If the input {@code PCollection} is windowed into {@link GlobalWindows},
   * a default value in the {@link GlobalWindow} will be output if the input
   * {@code PCollection} is empty.  To use this with inputs with other windowing,
   * either {@link Globally#withoutDefaults} or {@link Globally#asSingletonView}
   * must be called.
   *
   * <p>See {@link Globally Combine.Globally} for more information.
   */
  public static <InputT, OutputT> Globally<InputT, OutputT> globally(
      GlobalCombineFn<? super InputT, ?, OutputT> fn) {
    return new Globally<>(fn, true, 0);
  }

  /**
   * Returns a {@link PerKey Combine.PerKey} {@code PTransform} that
   * first groups its input {@code PCollection} of {@code KV}s by keys and
   * windows, then invokes the given function on each of the values lists to
   * produce a combined value, and then returns a {@code PCollection}
   * of {@code KV}s mapping each distinct key to its combined value for each
   * window.
   *
   * <p>Each output element is in the window by which its corresponding input
   * was grouped, and has the timestamp of the end of that window.  The output
   * {@code PCollection} has the same
   * {@link com.google.cloud.dataflow.sdk.transforms.windowing.WindowFn}
   * as the input.
   *
   * <p>See {@link PerKey Combine.PerKey} for more information.
   */
  public static <K, V> PerKey<K, V, V> perKey(
      SerializableFunction<Iterable<V>, V> fn) {
    return perKey(Combine.IterableCombineFn.of(fn));
  }

  /**
   * Returns a {@link PerKey Combine.PerKey} {@code PTransform} that
   * first groups its input {@code PCollection} of {@code KV}s by keys and
   * windows, then invokes the given function on each of the values lists to
   * produce a combined value, and then returns a {@code PCollection}
   * of {@code KV}s mapping each distinct key to its combined value for each
   * window.
   *
   * <p>Each output element is in the window by which its corresponding input
   * was grouped, and has the timestamp of the end of that window.  The output
   * {@code PCollection} has the same
   * {@link com.google.cloud.dataflow.sdk.transforms.windowing.WindowFn}
   * as the input.
   *
   * <p>See {@link PerKey Combine.PerKey} for more information.
   */
  public static <K, InputT, OutputT> PerKey<K, InputT, OutputT> perKey(
      GlobalCombineFn<? super InputT, ?, OutputT> fn) {
    return perKey(fn.<K>asKeyedFn());
  }

  /**
   * Returns a {@link PerKey Combine.PerKey} {@code PTransform} that
   * first groups its input {@code PCollection} of {@code KV}s by keys and
   * windows, then invokes the given function on each of the key/values-lists
   * pairs to produce a combined value, and then returns a
   * {@code PCollection} of {@code KV}s mapping each distinct key to
   * its combined value for each window.
   *
   * <p>Each output element is in the window by which its corresponding input
   * was grouped, and has the timestamp of the end of that window.  The output
   * {@code PCollection} has the same
   * {@link com.google.cloud.dataflow.sdk.transforms.windowing.WindowFn}
   * as the input.
   *
   * <p>See {@link PerKey Combine.PerKey} for more information.
   */
  public static <K, InputT, OutputT> PerKey<K, InputT, OutputT> perKey(
      PerKeyCombineFn<? super K, ? super InputT, ?, OutputT> fn) {
    return new PerKey<>(fn, false /*fewKeys*/);
  }

  /**
   * Returns a {@link PerKey Combine.PerKey}, and set fewKeys
   * in {@link GroupByKey}.
   */
  private static <K, InputT, OutputT> PerKey<K, InputT, OutputT> fewKeys(
      PerKeyCombineFn<? super K, ? super InputT, ?, OutputT> fn) {
    return new PerKey<>(fn, true /*fewKeys*/);
  }

  /**
   * Returns a {@link GroupedValues Combine.GroupedValues}
   * {@code PTransform} that takes a {@code PCollection} of
   * {@code KV}s where a key maps to an {@code Iterable} of values, e.g.,
   * the result of a {@code GroupByKey}, then uses the given
   * {@code SerializableFunction} to combine all the values associated
   * with a key, ignoring the key.  The type of the input and
   * output values must be the same.
   *
   * <p>Each output element has the same timestamp and is in the same window
   * as its corresponding input element, and the output
   * {@code PCollection} has the same
   * {@link com.google.cloud.dataflow.sdk.transforms.windowing.WindowFn}
   * associated with it as the input.
   *
   * <p>See {@link GroupedValues Combine.GroupedValues} for more information.
   *
   * <p>Note that {@link #perKey(SerializableFunction)} is typically
   * more convenient to use than {@link GroupByKey} followed by
   * {@code groupedValues(...)}.
   */
  public static <K, V> GroupedValues<K, V, V> groupedValues(
      SerializableFunction<Iterable<V>, V> fn) {
    return groupedValues(IterableCombineFn.of(fn));
  }

  /**
   * Returns a {@link GroupedValues Combine.GroupedValues}
   * {@code PTransform} that takes a {@code PCollection} of
   * {@code KV}s where a key maps to an {@code Iterable} of values, e.g.,
   * the result of a {@code GroupByKey}, then uses the given
   * {@code CombineFn} to combine all the values associated with a
   * key, ignoring the key.  The types of the input and output values
   * can differ.
   *
   * <p>Each output element has the same timestamp and is in the same window
   * as its corresponding input element, and the output
   * {@code PCollection} has the same
   * {@link com.google.cloud.dataflow.sdk.transforms.windowing.WindowFn}
   * associated with it as the input.
   *
   * <p>See {@link GroupedValues Combine.GroupedValues} for more information.
   *
   * <p>Note that {@link #perKey(CombineFnBase.GlobalCombineFn)} is typically
   * more convenient to use than {@link GroupByKey} followed by
   * {@code groupedValues(...)}.
   */
  public static <K, InputT, OutputT> GroupedValues<K, InputT, OutputT> groupedValues(
      GlobalCombineFn<? super InputT, ?, OutputT> fn) {
    return groupedValues(fn.<K>asKeyedFn());
  }

  /**
   * Returns a {@link GroupedValues Combine.GroupedValues}
   * {@code PTransform} that takes a {@code PCollection} of
   * {@code KV}s where a key maps to an {@code Iterable} of values, e.g.,
   * the result of a {@code GroupByKey}, then uses the given
   * {@code KeyedCombineFn} to combine all the values associated with
   * each key.  The combining function is provided the key.  The types
   * of the input and output values can differ.
   *
   * <p>Each output element has the same timestamp and is in the same window
   * as its corresponding input element, and the output
   * {@code PCollection} has the same
   * {@link com.google.cloud.dataflow.sdk.transforms.windowing.WindowFn}
   * associated with it as the input.
   *
   * <p>See {@link GroupedValues Combine.GroupedValues} for more information.
   *
   * <p>Note that {@link #perKey(CombineFnBase.PerKeyCombineFn)} is typically
   * more convenient to use than {@link GroupByKey} followed by
   * {@code groupedValues(...)}.
   */
  public static <K, InputT, OutputT> GroupedValues<K, InputT, OutputT> groupedValues(
      PerKeyCombineFn<? super K, ? super InputT, ?, OutputT> fn) {
    return new GroupedValues<>(fn);
  }


  /////////////////////////////////////////////////////////////////////////////

  /**
   * A {@code CombineFn<InputT, AccumT, OutputT>} specifies how to combine a
   * collection of input values of type {@code InputT} into a single
   * output value of type {@code OutputT}.  It does this via one or more
   * intermediate mutable accumulator values of type {@code AccumT}.
   *
   * <p>The overall process to combine a collection of input
   * {@code InputT} values into a single output {@code OutputT} value is as
   * follows:
   *
   * <ol>
   *
   * <li> The input {@code InputT} values are partitioned into one or more
   * batches.
   *
   * <li> For each batch, the {@link #createAccumulator} operation is
   * invoked to create a fresh mutable accumulator value of type
   * {@code AccumT}, initialized to represent the combination of zero
   * values.
   *
   * <li> For each input {@code InputT} value in a batch, the
   * {@link #addInput} operation is invoked to add the value to that
   * batch's accumulator {@code AccumT} value.  The accumulator may just
   * record the new value (e.g., if {@code AccumT == List<InputT>}, or may do
   * work to represent the combination more compactly.
   *
   * <li> The {@link #mergeAccumulators} operation is invoked to
   * combine a collection of accumulator {@code AccumT} values into a
   * single combined output accumulator {@code AccumT} value, once the
   * merging accumulators have had all all the input values in their
   * batches added to them.  This operation is invoked repeatedly,
   * until there is only one accumulator value left.
   *
   * <li> The {@link #extractOutput} operation is invoked on the final
   * accumulator {@code AccumT} value to get the output {@code OutputT} value.
   *
   * </ol>
   *
   * <p>For example:
   * <pre> {@code
   * public class AverageFn extends CombineFn<Integer, AverageFn.Accum, Double> {
   *   public static class Accum {
   *     int sum = 0;
   *     int count = 0;
   *   }
   *   public Accum createAccumulator() {
   *     return new Accum();
   *   }
   *   public Accum addInput(Accum accum, Integer input) {
   *       accum.sum += input;
   *       accum.count++;
   *       return accum;
   *   }
   *   public Accum mergeAccumulators(Iterable<Accum> accums) {
   *     Accum merged = createAccumulator();
   *     for (Accum accum : accums) {
   *       merged.sum += accum.sum;
   *       merged.count += accum.count;
   *     }
   *     return merged;
   *   }
   *   public Double extractOutput(Accum accum) {
   *     return ((double) accum.sum) / accum.count;
   *   }
   * }
   * PCollection<Integer> pc = ...;
   * PCollection<Double> average = pc.apply(Combine.globally(new AverageFn()));
   * } </pre>
   *
   * <p>Combining functions used by {@link Combine.Globally},
   * {@link Combine.PerKey}, {@link Combine.GroupedValues}, and
   * {@code PTransforms} derived from them should be
   * <i>associative</i> and <i>commutative</i>.  Associativity is
   * required because input values are first broken up into subgroups
   * before being combined, and their intermediate results further
   * combined, in an arbitrary tree structure.  Commutativity is
   * required because any order of the input values is ignored when
   * breaking up input values into groups.
   *
   * @param <InputT> type of input values
   * @param <AccumT> type of mutable accumulator values
   * @param <OutputT> type of output values
   */
  public abstract static class CombineFn<InputT, AccumT, OutputT>
      extends AbstractGlobalCombineFn<InputT, AccumT, OutputT> {

    /**
     * Returns a new, mutable accumulator value, representing the accumulation of zero input values.
     */
    public abstract AccumT createAccumulator();

    /**
     * Adds the given input value to the given accumulator, returning the
     * new accumulator value.
     *
     * <p>For efficiency, the input accumulator may be modified and returned.
     */
    public abstract AccumT addInput(AccumT accumulator, InputT input);

    /**
     * Returns an accumulator representing the accumulation of all the
     * input values accumulated in the merging accumulators.
     *
     * <p>May modify any of the argument accumulators.  May return a
     * fresh accumulator, or may return one of the (modified) argument
     * accumulators.
     */
    public abstract AccumT mergeAccumulators(Iterable<AccumT> accumulators);

    /**
     * Returns the output value that is the result of combining all
     * the input values represented by the given accumulator.
     */
    public abstract OutputT extractOutput(AccumT accumulator);

    /**
     * Returns an accumulator that represents the same logical value as the
     * input accumulator, but may have a more compact representation.
     *
     * <p>For most CombineFns this would be a no-op, but should be overridden
     * by CombineFns that (for example) buffer up elements and combine
     * them in batches.
     *
     * <p>For efficiency, the input accumulator may be modified and returned.
     *
     * <p>By default returns the original accumulator.
     */
    public AccumT compact(AccumT accumulator) {
      return accumulator;
    }

    /**
     * Applies this {@code CombineFn} to a collection of input values
     * to produce a combined output value.
     *
     * <p>Useful when using a {@code CombineFn}  separately from a
     * {@code Combine} transform.  Does not invoke the
     * {@link mergeAccumulators} operation.
     */
    public OutputT apply(Iterable<? extends InputT> inputs) {
      AccumT accum = createAccumulator();
      for (InputT input : inputs) {
        accum = addInput(accum, input);
      }
      return extractOutput(accum);
    }

    /**
     * {@inheritDoc}
     *
     * <p>By default returns the extract output of an empty accumulator.
     */
    @Override
    public OutputT defaultValue() {
      return extractOutput(createAccumulator());
    }

    /**
     * Returns a {@link TypeDescriptor} capturing what is known statically
     * about the output type of this {@code CombineFn} instance's
     * most-derived class.
     *
     * <p>In the normal case of a concrete {@code CombineFn} subclass with
     * no generic type parameters of its own, this will be a complete
     * non-generic type.
     */
    public TypeDescriptor<OutputT> getOutputType() {
      return new TypeDescriptor<OutputT>(getClass()) {};
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Override
    public <K> KeyedCombineFn<K, InputT, AccumT, OutputT> asKeyedFn() {
      // The key, an object, is never even looked at.
      return new KeyedCombineFn<K, InputT, AccumT, OutputT>() {
        @Override
        public AccumT createAccumulator(K key) {
          return CombineFn.this.createAccumulator();
        }

        @Override
        public AccumT addInput(K key, AccumT accumulator, InputT input) {
          return CombineFn.this.addInput(accumulator, input);
        }

        @Override
        public AccumT mergeAccumulators(K key, Iterable<AccumT> accumulators) {
          return CombineFn.this.mergeAccumulators(accumulators);
        }

        @Override
        public OutputT extractOutput(K key, AccumT accumulator) {
          return CombineFn.this.extractOutput(accumulator);
        }

        @Override
        public AccumT compact(K key, AccumT accumulator) {
          return CombineFn.this.compact(accumulator);
        }

        @Override
        public Coder<AccumT> getAccumulatorCoder(
            CoderRegistry registry, Coder<K> keyCoder, Coder<InputT> inputCoder)
            throws CannotProvideCoderException {
          return CombineFn.this.getAccumulatorCoder(registry, inputCoder);
        }

        @Override
        public Coder<OutputT> getDefaultOutputCoder(
            CoderRegistry registry, Coder<K> keyCoder, Coder<InputT> inputCoder)
            throws CannotProvideCoderException {
          return CombineFn.this.getDefaultOutputCoder(registry, inputCoder);
        }

        @Override
        public CombineFn<InputT, AccumT, OutputT> forKey(K key, Coder<K> keyCoder) {
          return CombineFn.this;
        }
      };
    }
  }


  /////////////////////////////////////////////////////////////////////////////

  /**
   * An abstract subclass of {@link CombineFn} for implementing combiners that are more
   * easily expressed as binary operations.
   */
  public abstract static class BinaryCombineFn<V> extends
      CombineFn<V, Holder<V>, V> {

    /**
     * Applies the binary operation to the two operands, returning the result.
     */
    public abstract V apply(V left, V right);

    /**
     * Returns the value that should be used for the combine of the empty set.
     */
    public V identity() {
      return null;
    }

    @Override
    public Holder<V> createAccumulator() {
      return new Holder<>();
    }

    @Override
    public Holder<V> addInput(Holder<V> accumulator, V input) {
      if (accumulator.present) {
        accumulator.set(apply(accumulator.value, input));
      } else {
        accumulator.set(input);
      }
      return accumulator;
    }

    @Override
    public Holder<V> mergeAccumulators(Iterable<Holder<V>> accumulators) {
      Iterator<Holder<V>> iter = accumulators.iterator();
      if (!iter.hasNext()) {
        return createAccumulator();
      } else {
        Holder<V> running = iter.next();
        while (iter.hasNext()) {
          Holder<V> accum = iter.next();
          if (accum.present) {
            if (running.present) {
              running.set(apply(running.value, accum.value));
            } else {
              running.set(accum.value);
            }
          }
        }
        return running;
      }
    }

    @Override
    public V extractOutput(Holder<V> accumulator) {
      if (accumulator.present) {
        return accumulator.value;
      } else {
        return identity();
      }
    }

    @Override
    public Coder<Holder<V>> getAccumulatorCoder(CoderRegistry registry, Coder<V> inputCoder) {
      return new HolderCoder<>(inputCoder);
    }

    @Override
    public Coder<V> getDefaultOutputCoder(CoderRegistry registry, Coder<V> inputCoder) {
      return inputCoder;
    }

  }

  /**
   * Holds a single value value of type {@code V} which may or may not be present.
   *
   * <p>Used only as a private accumulator class.
   */
  public static class Holder<V> {
    private V value;
    private boolean present;
    private Holder() { }
    private Holder(V value) {
      set(value);
    }

    private void set(V value) {
      this.present = true;
      this.value = value;
    }
  }

  /**
   * A {@link Coder} for a {@link Holder}.
   */
  private static class HolderCoder<V> extends CustomCoder<Holder<V>> {

    private Coder<V> valueCoder;

    public HolderCoder(Coder<V> valueCoder) {
      this.valueCoder = valueCoder;
    }

    @Override
    public List<Coder<?>> getCoderArguments() {
      return Arrays.<Coder<?>>asList(valueCoder);
    }

    @Override
    public void encode(Holder<V> accumulator, OutputStream outStream, Context context)
        throws CoderException, IOException {
      if (accumulator.present) {
        outStream.write(1);
        valueCoder.encode(accumulator.value, outStream, context);
      } else {
        outStream.write(0);
      }
    }

    @Override
    public Holder<V> decode(InputStream inStream, Context context)
        throws CoderException, IOException {
      if (inStream.read() == 1) {
        return new Holder<>(valueCoder.decode(inStream, context));
      } else {
        return new Holder<>();
      }
    }

    @Override
    public void verifyDeterministic() throws NonDeterministicException {
      valueCoder.verifyDeterministic();
    }
  }

  /**
   * An abstract subclass of {@link CombineFn} for implementing combiners that are more
   * easily and efficiently expressed as binary operations on <code>int</code>s
   *
   * <p> It uses {@code int[0]} as the mutable accumulator.
   */
  public abstract static class BinaryCombineIntegerFn extends CombineFn<Integer, int[], Integer> {

    /**
     * Applies the binary operation to the two operands, returning the result.
     */
    public abstract int apply(int left, int right);

    /**
     * Returns the identity element of this operation, i.e. an element {@code e}
     * such that {@code apply(e, x) == apply(x, e) == x} for all values of {@code x}.
     */
    public abstract int identity();

    @Override
    public int[] createAccumulator() {
      return wrap(identity());
    }

    @Override
    public int[] addInput(int[] accumulator, Integer input) {
      accumulator[0] = apply(accumulator[0], input);
      return accumulator;
    }

    @Override
    public int[] mergeAccumulators(Iterable<int[]> accumulators) {
      Iterator<int[]> iter = accumulators.iterator();
      if (!iter.hasNext()) {
        return createAccumulator();
      } else {
        int[] running = iter.next();
        while (iter.hasNext()) {
          running[0] = apply(running[0], iter.next()[0]);
        }
        return running;
      }
    }

    @Override
    public Integer extractOutput(int[] accumulator) {
      return accumulator[0];
    }

    @Override
    public Coder<int[]> getAccumulatorCoder(CoderRegistry registry, Coder<Integer> inputCoder) {
      return DelegateCoder.of(
          inputCoder,
          new DelegateCoder.CodingFunction<int[], Integer>() {
            @Override
            public Integer apply(int[] accumulator) {
              return accumulator[0];
            }
          },
          new DelegateCoder.CodingFunction<Integer, int[]>() {
            @Override
            public int[] apply(Integer value) {
              return wrap(value);
            }
          });
    }

    @Override
    public Coder<Integer> getDefaultOutputCoder(CoderRegistry registry,
                                                Coder<Integer> inputCoder) {
      return inputCoder;
    }

    private int[] wrap(int value) {
      return new int[] { value };
    }

    public Counter<Integer> getCounter(String name) {
      throw new UnsupportedOperationException("BinaryCombineDoubleFn does not support getCounter");
    }
  }

  /**
   * An abstract subclass of {@link CombineFn} for implementing combiners that are more
   * easily and efficiently expressed as binary operations on <code>long</code>s.
   *
   * <p> It uses {@code long[0]} as the mutable accumulator.
   */
  public abstract static class BinaryCombineLongFn extends CombineFn<Long, long[], Long> {
    /**
     * Applies the binary operation to the two operands, returning the result.
     */
    public abstract long apply(long left, long right);

    /**
     * Returns the identity element of this operation, i.e. an element {@code e}
     * such that {@code apply(e, x) == apply(x, e) == x} for all values of {@code x}.
     */
    public abstract long identity();

    @Override
    public long[] createAccumulator() {
      return wrap(identity());
    }

    @Override
    public long[] addInput(long[] accumulator, Long input) {
      accumulator[0] = apply(accumulator[0], input);
      return accumulator;
    }

    @Override
    public long[] mergeAccumulators(Iterable<long[]> accumulators) {
      Iterator<long[]> iter = accumulators.iterator();
      if (!iter.hasNext()) {
        return createAccumulator();
      } else {
        long[] running = iter.next();
        while (iter.hasNext()) {
          running[0] = apply(running[0], iter.next()[0]);
        }
        return running;
      }
    }

    @Override
    public Long extractOutput(long[] accumulator) {
      return accumulator[0];
    }

    @Override
    public Coder<long[]> getAccumulatorCoder(CoderRegistry registry, Coder<Long> inputCoder) {
      return DelegateCoder.of(
          inputCoder,
          new DelegateCoder.CodingFunction<long[], Long>() {
            @Override
            public Long apply(long[] accumulator) {
              return accumulator[0];
            }
          },
          new DelegateCoder.CodingFunction<Long, long[]>() {
            @Override
            public long[] apply(Long value) {
              return wrap(value);
            }
          });
    }

    @Override
    public Coder<Long> getDefaultOutputCoder(CoderRegistry registry, Coder<Long> inputCoder) {
      return inputCoder;
    }

    private long[] wrap(long value) {
      return new long[] { value };
    }

    public Counter<Long> getCounter(String name) {
      throw new UnsupportedOperationException("BinaryCombineDoubleFn does not support getCounter");
    }
  }

  /**
   * An abstract subclass of {@link CombineFn} for implementing combiners that are more
   * easily and efficiently expressed as binary operations on <code>double</code>s.
   *
   * <p> It uses {@code double[0]} as the mutable accumulator.
   */
  public abstract static class BinaryCombineDoubleFn extends CombineFn<Double, double[], Double> {

    /**
     * Applies the binary operation to the two operands, returning the result.
     */
    public abstract double apply(double left, double right);

    /**
     * Returns the identity element of this operation, i.e. an element {@code e}
     * such that {@code apply(e, x) == apply(x, e) == x} for all values of {@code x}.
     */
    public abstract double identity();

    @Override
    public double[] createAccumulator() {
      return wrap(identity());
    }

    @Override
    public double[] addInput(double[] accumulator, Double input) {
      accumulator[0] = apply(accumulator[0], input);
      return accumulator;
    }

    @Override
    public double[] mergeAccumulators(Iterable<double[]> accumulators) {
      Iterator<double[]> iter = accumulators.iterator();
      if (!iter.hasNext()) {
        return createAccumulator();
      } else {
        double[] running = iter.next();
        while (iter.hasNext()) {
          running[0] = apply(running[0], iter.next()[0]);
        }
        return running;
      }
    }

    @Override
    public Double extractOutput(double[] accumulator) {
      return accumulator[0];
    }

    @Override
    public Coder<double[]> getAccumulatorCoder(CoderRegistry registry, Coder<Double> inputCoder) {
      return DelegateCoder.of(
          inputCoder,
          new DelegateCoder.CodingFunction<double[], Double>() {
            @Override
            public Double apply(double[] accumulator) {
              return accumulator[0];
            }
          },
          new DelegateCoder.CodingFunction<Double, double[]>() {
            @Override
            public double[] apply(Double value) {
              return wrap(value);
            }
          });
    }

    @Override
    public Coder<Double> getDefaultOutputCoder(CoderRegistry registry, Coder<Double> inputCoder) {
      return inputCoder;
    }

    private double[] wrap(double value) {
      return new double[] { value };
    }

    public Counter<Double> getCounter(String name) {
      throw new UnsupportedOperationException("BinaryCombineDoubleFn does not support getCounter");
    }
  }

  /////////////////////////////////////////////////////////////////////////////

  /**
   * A {@code CombineFn} that uses a subclass of
   * {@link AccumulatingCombineFn.Accumulator} as its accumulator
   * type.  By defining the operations of the {@code Accumulator}
   * helper class, the operations of the enclosing {@code CombineFn}
   * are automatically provided.  This can reduce the code required to
   * implement a {@code CombineFn}.
   *
   * <p>For example, the example from {@link CombineFn} above can be
   * expressed using {@code AccumulatingCombineFn} more concisely as
   * follows:
   *
   * <pre> {@code
   * public class AverageFn
   *     extends AccumulatingCombineFn<Integer, AverageFn.Accum, Double> {
   *   public Accum createAccumulator() {
   *     return new Accum();
   *   }
   *   public class Accum
   *       extends AccumulatingCombineFn<Integer, AverageFn.Accum, Double>
   *               .Accumulator {
   *     private int sum = 0;
   *     private int count = 0;
   *     public void addInput(Integer input) {
   *       sum += input;
   *       count++;
   *     }
   *     public void mergeAccumulator(Accum other) {
   *       sum += other.sum;
   *       count += other.count;
   *     }
   *     public Double extractOutput() {
   *       return ((double) sum) / count;
   *     }
   *   }
   * }
   * PCollection<Integer> pc = ...;
   * PCollection<Double> average = pc.apply(Combine.globally(new AverageFn()));
   * } </pre>
   *
   * @param <InputT> type of input values
   * @param <AccumT> type of mutable accumulator values
   * @param <OutputT> type of output values
   */
  public abstract static class AccumulatingCombineFn<
      InputT,
      AccumT extends AccumulatingCombineFn.Accumulator<InputT, AccumT, OutputT>,
      OutputT>
      extends CombineFn<InputT, AccumT, OutputT> {

    /**
     * The type of mutable accumulator values used by this
     * {@code AccumulatingCombineFn}.
     */
    public abstract static interface Accumulator<InputT, AccumT, OutputT> {
      /**
       * Adds the given input value to this accumulator, modifying
       * this accumulator.
       */
      public abstract void addInput(InputT input);

      /**
       * Adds the input values represented by the given accumulator
       * into this accumulator.
       */
      public abstract void mergeAccumulator(AccumT other);

      /**
       * Returns the output value that is the result of combining all
       * the input values represented by this accumulator.
       */
      public abstract OutputT extractOutput();
    }

    @Override
    public final AccumT addInput(AccumT accumulator, InputT input) {
      accumulator.addInput(input);
      return accumulator;
    }

    @Override
    public final AccumT mergeAccumulators(Iterable<AccumT> accumulators) {
      AccumT accumulator = createAccumulator();
      for (AccumT partial : accumulators) {
        accumulator.mergeAccumulator(partial);
      }
      return accumulator;
    }

    @Override
    public final OutputT extractOutput(AccumT accumulator) {
      return accumulator.extractOutput();
    }
  }


  /////////////////////////////////////////////////////////////////////////////


  /**
   * A {@code KeyedCombineFn<K, InputT, AccumT, OutputT>} specifies how to combine
   * a collection of input values of type {@code InputT}, associated with
   * a key of type {@code K}, into a single output value of type
   * {@code OutputT}.  It does this via one or more intermediate mutable
   * accumulator values of type {@code AccumT}.
   *
   * <p>The overall process to combine a collection of input
   * {@code InputT} values associated with an input {@code K} key into a
   * single output {@code OutputT} value is as follows:
   *
   * <ol>
   *
   * <li> The input {@code InputT} values are partitioned into one or more
   * batches.
   *
   * <li> For each batch, the {@link #createAccumulator} operation is
   * invoked to create a fresh mutable accumulator value of type
   * {@code AccumT}, initialized to represent the combination of zero
   * values.
   *
   * <li> For each input {@code InputT} value in a batch, the
   * {@link #addInput} operation is invoked to add the value to that
   * batch's accumulator {@code AccumT} value.  The accumulator may just
   * record the new value (e.g., if {@code AccumT == List<InputT>}, or may do
   * work to represent the combination more compactly.
   *
   * <li> The {@link #mergeAccumulators} operation is invoked to
   * combine a collection of accumulator {@code AccumT} values into a
   * single combined output accumulator {@code AccumT} value, once the
   * merging accumulators have had all all the input values in their
   * batches added to them.  This operation is invoked repeatedly,
   * until there is only one accumulator value left.
   *
   * <li> The {@link #extractOutput} operation is invoked on the final
   * accumulator {@code AccumT} value to get the output {@code OutputT} value.
   *
   * </ol>
   *
   * <p>All of these operations are passed the {@code K} key that the
   * values being combined are associated with.
   *
   * <p>For example:
   * <pre> {@code
   * public class ConcatFn
   *     extends KeyedCombineFn<String, Integer, ConcatFn.Accum, String> {
   *   public static class Accum {
   *     String s = "";
   *   }
   *   public Accum createAccumulator(String key) {
   *     return new Accum();
   *   }
   *   public Accum addInput(String key, Accum accum, Integer input) {
   *       accum.s += "+" + input;
   *       return accum;
   *   }
   *   public Accum mergeAccumulators(String key, Iterable<Accum> accums) {
   *     Accum merged = new Accum();
   *     for (Accum accum : accums) {
   *       merged.s += accum.s;
   *     }
   *     return merged;
   *   }
   *   public String extractOutput(String key, Accum accum) {
   *     return key + accum.s;
   *   }
   * }
   * PCollection<KV<String, Integer>> pc = ...;
   * PCollection<KV<String, String>> pc2 = pc.apply(
   *     Combine.perKey(new ConcatFn()));
   * } </pre>
   *
   * <p>Keyed combining functions used by {@link Combine.PerKey},
   * {@link Combine.GroupedValues}, and {@code PTransforms} derived
   * from them should be <i>associative</i> and <i>commutative</i>.
   * Associativity is required because input values are first broken
   * up into subgroups before being combined, and their intermediate
   * results further combined, in an arbitrary tree structure.
   * Commutativity is required because any order of the input values
   * is ignored when breaking up input values into groups.
   *
   * @param <K> type of keys
   * @param <InputT> type of input values
   * @param <AccumT> type of mutable accumulator values
   * @param <OutputT> type of output values
   */
  public abstract static class KeyedCombineFn<K, InputT, AccumT, OutputT>
      extends AbstractPerKeyCombineFn<K, InputT, AccumT, OutputT> {
    /**
     * Returns a new, mutable accumulator value representing the accumulation of zero input values.
     *
     * @param key the key that all the accumulated values using the
     * accumulator are associated with
     */
    public abstract AccumT createAccumulator(K key);

    /**
     * Adds the given input value to the given accumulator, returning the new accumulator value.
     *
     * <p>For efficiency, the input accumulator may be modified and returned.
     *
     * @param key the key that all the accumulated values using the
     * accumulator are associated with
     */
    public abstract AccumT addInput(K key, AccumT accumulator, InputT value);

    /**
     * Returns an accumulator representing the accumulation of all the
     * input values accumulated in the merging accumulators.
     *
     * <p>May modify any of the argument accumulators.  May return a
     * fresh accumulator, or may return one of the (modified) argument
     * accumulators.
     *
     * @param key the key that all the accumulators are associated
     * with
     */
    public abstract AccumT mergeAccumulators(K key, Iterable<AccumT> accumulators);

    /**
     * Returns the output value that is the result of combining all
     * the input values represented by the given accumulator.
     *
     * @param key the key that all the accumulated values using the
     * accumulator are associated with
     */
    public abstract OutputT extractOutput(K key, AccumT accumulator);

    /**
     * Returns an accumulator that represents the same logical value as the
     * input accumulator, but may have a more compact representation.
     *
     * <p>For most CombineFns this would be a no-op, but should be overridden
     * by CombineFns that (for example) buffer up elements and combine
     * them in batches.
     *
     * <p>For efficiency, the input accumulator may be modified and returned.
     *
     * <p>By default returns the original accumulator.
     */
    public AccumT compact(K key, AccumT accumulator) {
      return accumulator;
    }

    @Override
    public CombineFn<InputT, AccumT, OutputT> forKey(final K key, final Coder<K> keyCoder) {
      return new CombineFn<InputT, AccumT, OutputT>() {

        @Override
        public AccumT createAccumulator() {
          return KeyedCombineFn.this.createAccumulator(key);
        }

        @Override
        public AccumT addInput(AccumT accumulator, InputT input) {
          return KeyedCombineFn.this.addInput(key, accumulator, input);
        }

        @Override
        public AccumT mergeAccumulators(Iterable<AccumT> accumulators) {
          return KeyedCombineFn.this.mergeAccumulators(key, accumulators);
        }

        @Override
        public OutputT extractOutput(AccumT accumulator) {
          return KeyedCombineFn.this.extractOutput(key, accumulator);
        }

        @Override
        public AccumT compact(AccumT accumulator) {
          return KeyedCombineFn.this.compact(key, accumulator);
        }

        @Override
        public Coder<AccumT> getAccumulatorCoder(CoderRegistry registry, Coder<InputT> inputCoder)
            throws CannotProvideCoderException {
          return KeyedCombineFn.this.getAccumulatorCoder(registry, keyCoder, inputCoder);
        }

        @Override
        public Coder<OutputT> getDefaultOutputCoder(
            CoderRegistry registry, Coder<InputT> inputCoder) throws CannotProvideCoderException {
          return KeyedCombineFn.this.getDefaultOutputCoder(registry, keyCoder, inputCoder);
        }
      };
    }

    /**
     * Applies this {@code KeyedCombineFn} to a key and a collection
     * of input values to produce a combined output value.
     *
     * <p>Useful when testing the behavior of a {@code KeyedCombineFn}
     * separately from a {@code Combine} transform.
     */
    public OutputT apply(K key, Iterable<? extends InputT> inputs) {
      AccumT accum = createAccumulator(key);
      for (InputT input : inputs) {
        accum = addInput(key, accum, input);
      }
      return extractOutput(key, accum);
    }
  }

  ////////////////////////////////////////////////////////////////////////////

  /**
   * {@code Combine.Globally<InputT, OutputT>} takes a {@code PCollection<InputT>}
   * and returns a {@code PCollection<OutputT>} whose elements are the result of
   * combining all the elements in each window of the input {@code PCollection},
   * using a specified {@link CombineFn CombineFn&lt;InputT, AccumT, OutputT&gt;}.
   * It is common for {@code InputT == OutputT}, but not required.  Common combining
   * functions include sums, mins, maxes, and averages of numbers,
   * conjunctions and disjunctions of booleans, statistical
   * aggregations, etc.
   *
   * <p>Example of use:
   * <pre> {@code
   * PCollection<Integer> pc = ...;
   * PCollection<Integer> sum = pc.apply(
   *     Combine.globally(new Sum.SumIntegerFn()));
   * } </pre>
   *
   * <p>Combining can happen in parallel, with different subsets of the
   * input {@code PCollection} being combined separately, and their
   * intermediate results combined further, in an arbitrary tree
   * reduction pattern, until a single result value is produced.
   *
   * <p>If the input {@code PCollection} is windowed into {@link GlobalWindows},
   * a default value in the {@link GlobalWindow} will be output if the input
   * {@code PCollection} is empty.  To use this with inputs with other windowing,
   * either {@link #withoutDefaults} or {@link #asSingletonView} must be called,
   * as the default value cannot be automatically assigned to any single window.
   *
   * <p>By default, the {@code Coder} of the output {@code PValue<OutputT>}
   * is inferred from the concrete type of the
   * {@code CombineFn<InputT, AccumT, OutputT>}'s output type {@code OutputT}.
   *
   * <p>See also {@link #perKey}/{@link PerKey Combine.PerKey} and
   * {@link #groupedValues}/{@link GroupedValues Combine.GroupedValues}, which
   * are useful for combining values associated with each key in
   * a {@code PCollection} of {@code KV}s.
   *
   * @param <InputT> type of input values
   * @param <OutputT> type of output values
   */
  public static class Globally<InputT, OutputT>
      extends PTransform<PCollection<InputT>, PCollection<OutputT>> {

    private final GlobalCombineFn<? super InputT, ?, OutputT> fn;
    private final boolean insertDefault;
    private final int fanout;
    private final List<PCollectionView<?>> sideInputs;

    private Globally(GlobalCombineFn<? super InputT, ?, OutputT> fn,
        boolean insertDefault, int fanout) {
      this.fn = fn;
      this.insertDefault = insertDefault;
      this.fanout = fanout;
      this.sideInputs = ImmutableList.<PCollectionView<?>>of();
    }

    private Globally(String name, GlobalCombineFn<? super InputT, ?, OutputT> fn,
        boolean insertDefault, int fanout) {
      super(name);
      this.fn = fn;
      this.insertDefault = insertDefault;
      this.fanout = fanout;
      this.sideInputs = ImmutableList.<PCollectionView<?>>of();
    }

    private Globally(String name, GlobalCombineFn<? super InputT, ?, OutputT> fn,
        boolean insertDefault, int fanout, List<PCollectionView<?>> sideInputs) {
      super(name);
      this.fn = fn;
      this.insertDefault = insertDefault;
      this.fanout = fanout;
      this.sideInputs = sideInputs;
    }

    /**
     * Return a new {@code Globally} transform that's like this transform but with the
     * specified name. Does not modify this transform.
     */
    public Globally<InputT, OutputT> named(String name) {
      return new Globally<>(name, fn, insertDefault, fanout);
    }

    /**
     * Returns a {@link PTransform} that produces a {@code PCollectionView}
     * whose elements are the result of combining elements per-window in
     * the input {@code PCollection}.  If a value is requested from the view
     * for a window that is not present, the result of applying the {@code CombineFn}
     * to an empty input set will be returned.
     */
    public GloballyAsSingletonView<InputT, OutputT> asSingletonView() {
      return new GloballyAsSingletonView<>(fn, insertDefault, fanout);
    }

    /**
     * Returns a {@link PTransform} identical to this, but that does not attempt to
     * provide a default value in the case of empty input.  Required when the input
     * is not globally windowed and the output is not being used as a side input.
     */
    public Globally<InputT, OutputT> withoutDefaults() {
      return new Globally<>(name, fn, false, fanout);
    }

    /**
     * Returns a {@link PTransform} identical to this, but that uses an intermediate node
     * to combine parts of the data to reduce load on the final global combine step.
     *
     * <p>The {@code fanout} parameter determines the number of intermediate keys
     * that will be used.
     */
    public Globally<InputT, OutputT> withFanout(int fanout) {
      return new Globally<>(name, fn, insertDefault, fanout);
    }

    /**
     * Returns a {@link PTransform} identical to this, but with the specified side inputs to use
     * in {@link CombineFnWithContext}.
     */
    public Globally<InputT, OutputT> withSideInputs(
        Iterable<? extends PCollectionView<?>> sideInputs) {
      Preconditions.checkState(fn instanceof RequiresContextInternal);
      return new Globally<InputT, OutputT>(name, fn, insertDefault, fanout,
          ImmutableList.<PCollectionView<?>>copyOf(sideInputs));
    }

    @Override
    public PCollection<OutputT> apply(PCollection<InputT> input) {
      PCollection<KV<Void, InputT>> withKeys = input
          .apply(WithKeys.<Void, InputT>of((Void) null))
          .setCoder(KvCoder.of(VoidCoder.of(), input.getCoder()));

      Combine.PerKey<Void, InputT, OutputT> combine =
          Combine.<Void, InputT, OutputT>fewKeys(fn.asKeyedFn());
      if (!sideInputs.isEmpty()) {
        combine = combine.withSideInputs(sideInputs);
      }

      PCollection<KV<Void, OutputT>> combined;
      if (fanout >= 2) {
        combined = withKeys.apply(combine.withHotKeyFanout(fanout));
      } else {
        combined = withKeys.apply(combine);
      }

      PCollection<OutputT> output = combined.apply(Values.<OutputT>create());

      if (insertDefault) {
        if (!output.getWindowingStrategy().getWindowFn().isCompatible(new GlobalWindows())) {
          throw new IllegalStateException(fn.getIncompatibleGlobalWindowErrorMessage());
        }
        return insertDefaultValueIfEmpty(output);
      } else {
        return output;
      }
    }

    private PCollection<OutputT> insertDefaultValueIfEmpty(PCollection<OutputT> maybeEmpty) {
      final PCollectionView<Iterable<OutputT>> maybeEmptyView = maybeEmpty.apply(
          View.<OutputT>asIterable());


      final OutputT defaultValue = fn.defaultValue();
      PCollection<OutputT> defaultIfEmpty = maybeEmpty.getPipeline()
          .apply("CreateVoid", Create.of((Void) null).withCoder(VoidCoder.of()))
          .apply(ParDo.named("ProduceDefault").withSideInputs(maybeEmptyView).of(
              new DoFn<Void, OutputT>() {
                @Override
                public void processElement(DoFn<Void, OutputT>.ProcessContext c) {
                  Iterator<OutputT> combined = c.sideInput(maybeEmptyView).iterator();
                  if (!combined.hasNext()) {
                    c.output(defaultValue);
                  }
                }
              }))
          .setCoder(maybeEmpty.getCoder())
          .setWindowingStrategyInternal(maybeEmpty.getWindowingStrategy());

      return PCollectionList.of(maybeEmpty).and(defaultIfEmpty)
          .apply(Flatten.<OutputT>pCollections());
    }
  }

  /**
   * {@code Combine.GloballyAsSingletonView<InputT, OutputT>} takes a {@code PCollection<InputT>}
   * and returns a {@code PCollectionView<OutputT>} whose elements are the result of
   * combining all the elements in each window of the input {@code PCollection},
   * using a specified {@link CombineFn CombineFn&lt;InputT, AccumT, OutputT&gt;}.
   * It is common for {@code InputT == OutputT}, but not required. Common combining
   * functions include sums, mins, maxes, and averages of numbers,
   * conjunctions and disjunctions of booleans, statistical
   * aggregations, etc.
   *
   * <p>Example of use:
   * <pre> {@code
   * PCollection<Integer> pc = ...;
   * PCollection<Integer> sum = pc.apply(
   *     Combine.globally(new Sum.SumIntegerFn()));
   * } </pre>
   *
   * <p>Combining can happen in parallel, with different subsets of the
   * input {@code PCollection} being combined separately, and their
   * intermediate results combined further, in an arbitrary tree
   * reduction pattern, until a single result value is produced.
   *
   * <p>If a value is requested from the view for a window that is not present
   * and {@code insertDefault} is true, the result of calling the {@code CombineFn}
   * on empty input will returned. If {@code insertDefault} is false, an
   * exception will be thrown instead.
   *
   * <p>By default, the {@code Coder} of the output {@code PValue<OutputT>}
   * is inferred from the concrete type of the
   * {@code CombineFn<InputT, AccumT, OutputT>}'s output type {@code OutputT}.
   *
   * <p>See also {@link #perKey}/{@link PerKey Combine.PerKey} and
   * {@link #groupedValues}/{@link GroupedValues Combine.GroupedValues}, which
   * are useful for combining values associated with each key in
   * a {@code PCollection} of {@code KV}s.
   *
   * @param <InputT> type of input values
   * @param <OutputT> type of output values
   */
  public static class GloballyAsSingletonView<InputT, OutputT>
      extends PTransform<PCollection<InputT>, PCollectionView<OutputT>> {

    private final GlobalCombineFn<? super InputT, ?, OutputT> fn;
    private final boolean insertDefault;
    private final int fanout;

    private GloballyAsSingletonView(
        GlobalCombineFn<? super InputT, ?, OutputT> fn, boolean insertDefault, int fanout) {
      this.fn = fn;
      this.insertDefault = insertDefault;
      this.fanout = fanout;
    }

    @Override
    public PCollectionView<OutputT> apply(PCollection<InputT> input) {
      Globally<InputT, OutputT> combineGlobally =
          Combine.<InputT, OutputT>globally(fn).withoutDefaults().withFanout(fanout);
      if (insertDefault) {
        return input
            .apply(combineGlobally)
            .apply(View.<OutputT>asSingleton().withDefaultValue(fn.defaultValue()));
      } else {
        return input
            .apply(combineGlobally)
            .apply(View.<OutputT>asSingleton());
      }
    }

    public int getFanout() {
      return fanout;
    }

    public boolean getInsertDefault() {
      return insertDefault;
    }

    public GlobalCombineFn<? super InputT, ?, OutputT> getCombineFn() {
      return fn;
    }
  }

  /**
   * Converts a {@link SerializableFunction} from {@code Iterable<V>}s
   * to {@code V}s into a simple {@link CombineFn} over {@code V}s.
   *
   * <p>Used in the implementation of convenience methods like
   * {@link #globally(SerializableFunction)},
   * {@link #perKey(SerializableFunction)}, and
   * {@link #groupedValues(SerializableFunction)}.
   */
  public static class IterableCombineFn<V> extends CombineFn<V, List<V>, V> {
    /**
     * Returns a {@code CombineFn} that uses the given
     * {@code SerializableFunction} to combine values.
     */
    public static <V> IterableCombineFn<V> of(
        SerializableFunction<Iterable<V>, V> combiner) {
      return of(combiner, DEFAULT_BUFFER_SIZE);
    }

    /**
     * Returns a {@code CombineFn} that uses the given
     * {@code SerializableFunction} to combine values,
     * attempting to buffer at least {@code bufferSize}
     * values between invocations.
     */
    public static <V> IterableCombineFn<V> of(
        SerializableFunction<Iterable<V>, V> combiner, int bufferSize) {
      return new IterableCombineFn<>(combiner, bufferSize);
    }

    private static final int DEFAULT_BUFFER_SIZE = 20;

    /** The combiner function. */
    private final SerializableFunction<Iterable<V>, V> combiner;

    /**
     * The number of values to accumulate before invoking the combiner
     * function to combine them.
     */
    private final int bufferSize;

    private IterableCombineFn(
        SerializableFunction<Iterable<V>, V> combiner, int bufferSize) {
      this.combiner = combiner;
      this.bufferSize = bufferSize;
    }

    @Override
    public List<V> createAccumulator() {
      return new ArrayList<>();
    }

    @Override
    public List<V> addInput(List<V> accumulator, V input) {
      accumulator.add(input);
      if (accumulator.size() > bufferSize) {
        return mergeToSingleton(accumulator);
      } else {
        return accumulator;
      }
    }

    @Override
    public List<V> mergeAccumulators(Iterable<List<V>> accumulators) {
      return mergeToSingleton(Iterables.concat(accumulators));
    }

    @Override
    public V extractOutput(List<V> accumulator) {
      return combiner.apply(accumulator);
    }

    @Override
    public List<V> compact(List<V> accumulator) {
      return accumulator.size() > 1 ? mergeToSingleton(accumulator) : accumulator;
    }

    private List<V> mergeToSingleton(Iterable<V> values) {
      List<V> singleton = new ArrayList<>();
      singleton.add(combiner.apply(values));
      return singleton;
    }
  }

  /**
   * Converts a {@link SerializableFunction} from {@code Iterable<V>}s
   * to {@code V}s into a simple {@link CombineFn} over {@code V}s.
   *
   * <p>@deprecated Use {@link IterableCombineFn} or the more space efficient
   * {@link BinaryCombineFn} instead (which avoids buffering values).
   */
  @Deprecated
  public static class SimpleCombineFn<V> extends IterableCombineFn<V> {

    /**
     * Returns a {@code CombineFn} that uses the given
     * {@code SerializableFunction} to combine values.
     */
    @Deprecated
    public static <V> SimpleCombineFn<V> of(
        SerializableFunction<Iterable<V>, V> combiner) {
      return new SimpleCombineFn<>(combiner);
    }

    protected SimpleCombineFn(SerializableFunction<Iterable<V>, V> combiner) {
      super(combiner, IterableCombineFn.DEFAULT_BUFFER_SIZE);
    }
  }


  /////////////////////////////////////////////////////////////////////////////

  /**
   * {@code PerKey<K, InputT, OutputT>} takes a
   * {@code PCollection<KV<K, InputT>>}, groups it by key, applies a
   * combining function to the {@code InputT} values associated with each
   * key to produce a combined {@code OutputT} value, and returns a
   * {@code PCollection<KV<K, OutputT>>} representing a map from each
   * distinct key of the input {@code PCollection} to the corresponding
   * combined value.  {@code InputT} and {@code OutputT} are often the same.
   *
   * <p>This is a concise shorthand for an application of
   * {@link GroupByKey} followed by an application of
   * {@link GroupedValues Combine.GroupedValues}.  See those
   * operations for more details on how keys are compared for equality
   * and on the default {@code Coder} for the output.
   *
   * <p>Example of use:
   * <pre> {@code
   * PCollection<KV<String, Double>> salesRecords = ...;
   * PCollection<KV<String, Double>> totalSalesPerPerson =
   *     salesRecords.apply(Combine.<String, Double>perKey(
   *         new Sum.SumDoubleFn()));
   * } </pre>
   *
   * <p>Each output element is in the window by which its corresponding input
   * was grouped, and has the timestamp of the end of that window.  The output
   * {@code PCollection} has the same
   * {@link com.google.cloud.dataflow.sdk.transforms.windowing.WindowFn}
   * as the input.
   *
   * @param <K> the type of the keys of the input and output
   * {@code PCollection}s
   * @param <InputT> the type of the values of the input {@code PCollection}
   * @param <OutputT> the type of the values of the output {@code PCollection}
   */
  public static class PerKey<K, InputT, OutputT>
    extends PTransform<PCollection<KV<K, InputT>>, PCollection<KV<K, OutputT>>> {

    private final transient PerKeyCombineFn<? super K, ? super InputT, ?, OutputT> fn;
    private final boolean fewKeys;
    private final List<PCollectionView<?>> sideInputs;

    private PerKey(
        PerKeyCombineFn<? super K, ? super InputT, ?, OutputT> fn, boolean fewKeys) {
      this.fn = fn;
      this.fewKeys = fewKeys;
      this.sideInputs = ImmutableList.of();
    }

    private PerKey(String name,
        PerKeyCombineFn<? super K, ? super InputT, ?, OutputT> fn,
        boolean fewKeys, List<PCollectionView<?>> sideInputs) {
      super(name);
      this.fn = fn;
      this.fewKeys = fewKeys;
      this.sideInputs = sideInputs;
    }

    private PerKey(
        String name, PerKeyCombineFn<? super K, ? super InputT, ?, OutputT> fn,
        boolean fewKeys) {
      super(name);
      this.fn = fn;
      this.fewKeys = fewKeys;
      this.sideInputs = ImmutableList.of();
    }

    /**
     * Return a new {@code Globally} transform that's like this transform but with the
     * specified name. Does not modify this transform.
     */
    public PerKey<K, InputT, OutputT> named(String name) {
      return new PerKey<K, InputT, OutputT>(name, fn, fewKeys);
    }

    /**
     * Returns a {@link PTransform} identical to this, but with the specified side inputs to use
     * in {@link KeyedCombineFnWithContext}.
     */
    public PerKey<K, InputT, OutputT> withSideInputs(
        Iterable<? extends PCollectionView<?>> sideInputs) {
      Preconditions.checkState(fn instanceof RequiresContextInternal);
      return new PerKey<K, InputT, OutputT>(name, fn, fewKeys,
          ImmutableList.<PCollectionView<?>>copyOf(sideInputs));
    }

    /**
     * If a single key has disproportionately many values, it may become a
     * bottleneck, especially in streaming mode.  This returns a new per-key
     * combining transform that inserts an intermediate node to combine "hot"
     * keys partially before performing the full combine.
     *
     * @param hotKeyFanout a function from keys to an integer N, where the key
     * will be spread among N intermediate nodes for partial combining.
     * If N is less than or equal to 1, this key will not be sent through an
     * intermediate node.
     */
    public PerKeyWithHotKeyFanout<K, InputT, OutputT> withHotKeyFanout(
        SerializableFunction<? super K, Integer> hotKeyFanout) {
      return new PerKeyWithHotKeyFanout<K, InputT, OutputT>(name, fn, hotKeyFanout);
    }

    /**
     * Like {@link #withHotKeyFanout(SerializableFunction)}, but returning the given
     * constant value for every key.
     */
    public PerKeyWithHotKeyFanout<K, InputT, OutputT> withHotKeyFanout(final int hotKeyFanout) {
      return new PerKeyWithHotKeyFanout<K, InputT, OutputT>(name, fn,
          new SerializableFunction<K, Integer>(){
            @Override
            public Integer apply(K unused) {
              return hotKeyFanout;
            }
          });
    }

    /**
     * Returns the {@link PerKeyCombineFn} used by this Combine operation.
     */
    public PerKeyCombineFn<? super K, ? super InputT, ?, OutputT> getFn() {
      return fn;
    }

    /**
     * Returns the side inputs used by this Combine operation.
     */
    public List<PCollectionView<?>> getSideInputs() {
      return sideInputs;
    }

    @Override
    public PCollection<KV<K, OutputT>> apply(PCollection<KV<K, InputT>> input) {
      return input
          .apply(GroupByKey.<K, InputT>create(fewKeys))
          .apply(Combine.<K, InputT, OutputT>groupedValues(fn).withSideInputs(sideInputs));
    }
  }

  /**
   * Like {@link PerKey}, but sharding the combining of hot keys.
   */
  public static class PerKeyWithHotKeyFanout<K, InputT, OutputT>
      extends PTransform<PCollection<KV<K, InputT>>, PCollection<KV<K, OutputT>>> {

    private final transient PerKeyCombineFn<? super K, ? super InputT, ?, OutputT> fn;
    private final SerializableFunction<? super K, Integer> hotKeyFanout;

    private PerKeyWithHotKeyFanout(String name,
        PerKeyCombineFn<? super K, ? super InputT, ?, OutputT> fn,
        SerializableFunction<? super K, Integer> hotKeyFanout) {
      super(name);
      this.fn = fn;
      this.hotKeyFanout = hotKeyFanout;
    }

    @Override
    public PCollection<KV<K, OutputT>> apply(PCollection<KV<K, InputT>> input) {
      return applyHelper(input);
    }

    private <AccumT> PCollection<KV<K, OutputT>> applyHelper(PCollection<KV<K, InputT>> input) {

      // Name the accumulator type.
      @SuppressWarnings("unchecked")
      final PerKeyCombineFn<K, InputT, AccumT, OutputT> typedFn =
          (PerKeyCombineFn<K, InputT, AccumT, OutputT>) this.fn;

      if (!(input.getCoder() instanceof KvCoder)) {
        throw new IllegalStateException(
            "Expected input coder to be KvCoder, but was " + input.getCoder());
      }

      @SuppressWarnings("unchecked")
      final KvCoder<K, InputT> inputCoder = (KvCoder<K, InputT>) input.getCoder();
      final Coder<AccumT> accumCoder;

      try {
        accumCoder = typedFn.getAccumulatorCoder(
            input.getPipeline().getCoderRegistry(),
            inputCoder.getKeyCoder(), inputCoder.getValueCoder());
      } catch (CannotProvideCoderException e) {
        throw new IllegalStateException("Unable to determine accumulator coder.", e);
      }
      Coder<InputOrAccum<InputT, AccumT>> inputOrAccumCoder =
          new InputOrAccum.InputOrAccumCoder<InputT, AccumT>(
              inputCoder.getValueCoder(), accumCoder);

      // A CombineFn's mergeAccumulator can be applied in a tree-like fashon.
      // Here we shard the key using an integer nonce, combine on that partial
      // set of values, then drop the nonce and do a final combine of the
      // aggregates.  We do this by splitting the original CombineFn into two,
      // on that does addInput + merge and another that does merge + extract.
      PerKeyCombineFn<KV<K, Integer>, InputT, AccumT, AccumT> hotPreCombine;
      PerKeyCombineFn<K, InputOrAccum<InputT, AccumT>, AccumT, OutputT> postCombine;
      if (!(typedFn instanceof RequiresContextInternal)) {
        final KeyedCombineFn<K, InputT, AccumT, OutputT> keyedFn =
            (KeyedCombineFn<K, InputT, AccumT, OutputT>) typedFn;
        hotPreCombine =
            new KeyedCombineFn<KV<K, Integer>, InputT, AccumT, AccumT>() {
              @Override
              public AccumT createAccumulator(KV<K, Integer> key) {
                return keyedFn.createAccumulator(key.getKey());
              }
              @Override
              public AccumT addInput(KV<K, Integer> key, AccumT accumulator, InputT value) {
                return keyedFn.addInput(key.getKey(), accumulator, value);
              }
              @Override
              public AccumT mergeAccumulators(
                  KV<K, Integer> key, Iterable<AccumT> accumulators) {
                return keyedFn.mergeAccumulators(key.getKey(), accumulators);
              }
              @Override
              public AccumT compact(KV<K, Integer> key, AccumT accumulator) {
                return keyedFn.compact(key.getKey(), accumulator);
              }
              @Override
              public AccumT extractOutput(KV<K, Integer> key, AccumT accumulator) {
                return accumulator;
              }
              @Override
              @SuppressWarnings("unchecked")
              public Coder<AccumT> getAccumulatorCoder(
                  CoderRegistry registry, Coder<KV<K, Integer>> keyCoder, Coder<InputT> inputCoder)
                  throws CannotProvideCoderException {
                return accumCoder;
              }
            };
        postCombine =
            new KeyedCombineFn<K, InputOrAccum<InputT, AccumT>, AccumT, OutputT>() {
              @Override
              public AccumT createAccumulator(K key) {
                return keyedFn.createAccumulator(key);
              }
              @Override
              public AccumT addInput(
                  K key, AccumT accumulator, InputOrAccum<InputT, AccumT> value) {
                if (value.accum == null) {
                  return keyedFn.addInput(key, accumulator, value.input);
                } else {
                  return keyedFn.mergeAccumulators(key, ImmutableList.of(accumulator, value.accum));
                }
              }
              @Override
              public AccumT mergeAccumulators(K key, Iterable<AccumT> accumulators) {
                return keyedFn.mergeAccumulators(key, accumulators);
              }
              @Override
              public AccumT compact(K key, AccumT accumulator) {
                return keyedFn.compact(key, accumulator);
              }
              @Override
              public OutputT extractOutput(K key, AccumT accumulator) {
                return keyedFn.extractOutput(key, accumulator);
              }
              @Override
              public Coder<OutputT> getDefaultOutputCoder(
                  CoderRegistry registry,
                  Coder<K> keyCoder,
                  Coder<InputOrAccum<InputT, AccumT>> accumulatorCoder)
                  throws CannotProvideCoderException {
                return keyedFn.getDefaultOutputCoder(
                    registry, keyCoder, inputCoder.getValueCoder());
              }

              @Override
              public Coder<AccumT> getAccumulatorCoder(CoderRegistry registry, Coder<K> keyCoder,
                  Coder<InputOrAccum<InputT, AccumT>> inputCoder)
                      throws CannotProvideCoderException {
                return accumCoder;
              }
            };
      } else {
        final KeyedCombineFnWithContext<K, InputT, AccumT, OutputT> keyedFnWithContext =
            (KeyedCombineFnWithContext<K, InputT, AccumT, OutputT>) typedFn;
        hotPreCombine =
            new KeyedCombineFnWithContext<KV<K, Integer>, InputT, AccumT, AccumT>() {
              @Override
              public AccumT createAccumulator(KV<K, Integer> key, Context c) {
                return keyedFnWithContext.createAccumulator(key.getKey(), c);
              }

              @Override
              public AccumT addInput(
                  KV<K, Integer> key, AccumT accumulator, InputT value, Context c) {
                return keyedFnWithContext.addInput(key.getKey(), accumulator, value, c);
              }

              @Override
              public AccumT mergeAccumulators(
                  KV<K, Integer> key, Iterable<AccumT> accumulators, Context c) {
                return keyedFnWithContext.mergeAccumulators(key.getKey(), accumulators, c);
              }

              @Override
              public AccumT compact(KV<K, Integer> key, AccumT accumulator, Context c) {
                return keyedFnWithContext.compact(key.getKey(), accumulator, c);
              }

              @Override
              public AccumT extractOutput(KV<K, Integer> key, AccumT accumulator, Context c) {
                return accumulator;
              }

              @Override
              @SuppressWarnings("unchecked")
              public Coder<AccumT> getAccumulatorCoder(
                  CoderRegistry registry, Coder<KV<K, Integer>> keyCoder, Coder<InputT> inputCoder)
                  throws CannotProvideCoderException {
                return accumCoder;
              }
            };
        postCombine =
            new KeyedCombineFnWithContext<K, InputOrAccum<InputT, AccumT>, AccumT, OutputT>() {
              @Override
              public AccumT createAccumulator(K key, Context c) {
                return keyedFnWithContext.createAccumulator(key, c);
              }
              @Override
              public AccumT addInput(
                  K key, AccumT accumulator, InputOrAccum<InputT, AccumT> value, Context c) {
                if (value.accum == null) {
                  return keyedFnWithContext.addInput(key, accumulator, value.input, c);
                } else {
                  return keyedFnWithContext.mergeAccumulators(
                      key, ImmutableList.of(accumulator, value.accum), c);
                }
              }
              @Override
              public AccumT mergeAccumulators(K key, Iterable<AccumT> accumulators, Context c) {
                return keyedFnWithContext.mergeAccumulators(key, accumulators, c);
              }
              @Override
              public AccumT compact(K key, AccumT accumulator, Context c) {
                return keyedFnWithContext.compact(key, accumulator, c);
              }
              @Override
              public OutputT extractOutput(K key, AccumT accumulator, Context c) {
                return keyedFnWithContext.extractOutput(key, accumulator, c);
              }
              @Override
              public Coder<OutputT> getDefaultOutputCoder(
                  CoderRegistry registry,
                  Coder<K> keyCoder,
                  Coder<InputOrAccum<InputT, AccumT>> accumulatorCoder)
                  throws CannotProvideCoderException {
                return keyedFnWithContext.getDefaultOutputCoder(
                    registry, keyCoder, inputCoder.getValueCoder());
              }

              @Override
              public Coder<AccumT> getAccumulatorCoder(CoderRegistry registry, Coder<K> keyCoder,
                  Coder<InputOrAccum<InputT, AccumT>> inputCoder)
                  throws CannotProvideCoderException {
                return accumCoder;
              }
            };
      }

      // Use the provided hotKeyFanout fn to split into "hot" and "cold" keys,
      // augmenting the hot keys with a nonce.
      final TupleTag<KV<KV<K, Integer>, InputT>> hot = new TupleTag<>();
      final TupleTag<KV<K, InputT>> cold = new TupleTag<>();
      PCollectionTuple split = input.apply(
          ParDo.named("AddNonce").of(
              new DoFn<KV<K, InputT>, KV<K, InputT>>() {
                transient int counter;
                @Override
                public void startBundle(Context c) {
                  counter = ThreadLocalRandom.current().nextInt(
                      Integer.MAX_VALUE);
                }

                @Override
                public void processElement(ProcessContext c) {
                  KV<K, InputT> kv = c.element();
                  int spread = Math.max(1, hotKeyFanout.apply(kv.getKey()));
                  if (spread <= 1) {
                    c.output(kv);
                  } else {
                    int nonce = counter++ % spread;
                    c.sideOutput(hot, KV.of(KV.of(kv.getKey(), nonce), kv.getValue()));
                  }
                }
              })
          .withOutputTags(cold, TupleTagList.of(hot)));

      // The first level of combine should never use accumulating mode.
      WindowingStrategy<?, ?> preCombineStrategy = input.getWindowingStrategy();
      if (preCombineStrategy.getMode()
          == WindowingStrategy.AccumulationMode.ACCUMULATING_FIRED_PANES) {
        preCombineStrategy = preCombineStrategy.withMode(
            WindowingStrategy.AccumulationMode.DISCARDING_FIRED_PANES);
      }

      // Combine the hot and cold keys separately.
      PCollection<KV<K, InputOrAccum<InputT, AccumT>>> precombinedHot = split
          .get(hot)
          .setCoder(KvCoder.of(KvCoder.of(inputCoder.getKeyCoder(), VarIntCoder.of()),
                               inputCoder.getValueCoder()))
          .setWindowingStrategyInternal(preCombineStrategy)
          .apply("PreCombineHot", Combine.perKey(hotPreCombine))
          .apply(ParDo.named("StripNonce").of(
              new DoFn<KV<KV<K, Integer>, AccumT>,
                       KV<K, InputOrAccum<InputT, AccumT>>>() {
                @Override
                public void processElement(ProcessContext c) {
                  c.output(KV.of(
                      c.element().getKey().getKey(),
                      InputOrAccum.<InputT, AccumT>accum(c.element().getValue())));
                }
              }))
          .setCoder(KvCoder.of(inputCoder.getKeyCoder(), inputOrAccumCoder))
          .apply(Window.<KV<K, InputOrAccum<InputT, AccumT>>>remerge())
          .setWindowingStrategyInternal(input.getWindowingStrategy());
      PCollection<KV<K, InputOrAccum<InputT, AccumT>>> preprocessedCold = split
          .get(cold)
          .setCoder(inputCoder)
          .apply(ParDo.named("PrepareCold").of(
              new DoFn<KV<K, InputT>, KV<K, InputOrAccum<InputT, AccumT>>>() {
                @Override
                public void processElement(ProcessContext c) {
                  c.output(KV.of(c.element().getKey(),
                                 InputOrAccum.<InputT, AccumT>input(c.element().getValue())));
                }
              }))
          .setCoder(KvCoder.of(inputCoder.getKeyCoder(), inputOrAccumCoder));

      // Combine the union of the pre-processed hot and cold key results.
      return PCollectionList.of(precombinedHot).and(preprocessedCold)
          .apply(Flatten.<KV<K, InputOrAccum<InputT, AccumT>>>pCollections())
          .apply("PostCombine", Combine.perKey(postCombine));
    }

    /**
     * Used to store either an input or accumulator value, for flattening
     * the hot and cold key paths.
     */
    private static class InputOrAccum<InputT, AccumT> {
      public final InputT input;
      public final AccumT accum;

      private InputOrAccum(InputT input, AccumT aggr) {
        this.input = input;
        this.accum = aggr;
      }

      public static <InputT, AccumT> InputOrAccum<InputT, AccumT> input(InputT input) {
        return new InputOrAccum<InputT, AccumT>(input, null);
      }

      public static <InputT, AccumT> InputOrAccum<InputT, AccumT> accum(AccumT aggr) {
        return new InputOrAccum<InputT, AccumT>(null, aggr);
      }

      private static class InputOrAccumCoder<InputT, AccumT>
          extends StandardCoder<InputOrAccum<InputT, AccumT>> {

        private final Coder<InputT> inputCoder;
        private final Coder<AccumT> accumCoder;

        public InputOrAccumCoder(Coder<InputT> inputCoder, Coder<AccumT> accumCoder) {
          this.inputCoder = inputCoder;
          this.accumCoder = accumCoder;
        }

        @JsonCreator
        @SuppressWarnings({"rawtypes", "unchecked"})
        public static <InputT, AccumT> InputOrAccumCoder<InputT, AccumT> of(
            @JsonProperty(PropertyNames.COMPONENT_ENCODINGS)
            List<Coder<?>> elementCoders) {
          return new InputOrAccumCoder(elementCoders.get(0), elementCoders.get(1));
        }

        @Override
        public void encode(
            InputOrAccum<InputT, AccumT> value, OutputStream outStream, Coder.Context context)
            throws CoderException, IOException {
          if (value.input != null) {
            outStream.write(0);
            inputCoder.encode(value.input, outStream, context);
          } else {
            outStream.write(1);
            accumCoder.encode(value.accum, outStream, context);
          }
        }

        @Override
        public InputOrAccum<InputT, AccumT> decode(InputStream inStream, Coder.Context context)
            throws CoderException, IOException {
          if (inStream.read() == 0) {
            return InputOrAccum.<InputT, AccumT>input(inputCoder.decode(inStream, context));
          } else {
            return InputOrAccum.<InputT, AccumT>accum(accumCoder.decode(inStream, context));
          }
        }

        @Override
        public List<? extends Coder<?>> getCoderArguments() {
          return ImmutableList.of(inputCoder, accumCoder);
        }

        @Override
        public void verifyDeterministic() throws Coder.NonDeterministicException {
          inputCoder.verifyDeterministic();
          accumCoder.verifyDeterministic();
        }
      }
    }
  }


  /////////////////////////////////////////////////////////////////////////////

  /**
   * {@code GroupedValues<K, InputT, OutputT>} takes a
   * {@code PCollection<KV<K, Iterable<InputT>>>}, such as the result of
   * {@link GroupByKey}, applies a specified
   * {@link KeyedCombineFn KeyedCombineFn&lt;K, InputT, AccumT, OutputT&gt;}
   * to each of the input {@code KV<K, Iterable<InputT>>} elements to
   * produce a combined output {@code KV<K, OutputT>} element, and returns a
   * {@code PCollection<KV<K, OutputT>>} containing all the combined output
   * elements.  It is common for {@code InputT == OutputT}, but not required.
   * Common combining functions include sums, mins, maxes, and averages
   * of numbers, conjunctions and disjunctions of booleans, statistical
   * aggregations, etc.
   *
   * <p>Example of use:
   * <pre> {@code
   * PCollection<KV<String, Integer>> pc = ...;
   * PCollection<KV<String, Iterable<Integer>>> groupedByKey = pc.apply(
   *     new GroupByKey<String, Integer>());
   * PCollection<KV<String, Integer>> sumByKey = groupedByKey.apply(
   *     Combine.<String, Integer>groupedValues(
   *         new Sum.SumIntegerFn()));
   * } </pre>
   *
   * <p>See also {@link #perKey}/{@link PerKey Combine.PerKey}, which
   * captures the common pattern of "combining by key" in a
   * single easy-to-use {@code PTransform}.
   *
   * <p>Combining for different keys can happen in parallel.  Moreover,
   * combining of the {@code Iterable<InputT>} values associated a single
   * key can happen in parallel, with different subsets of the values
   * being combined separately, and their intermediate results combined
   * further, in an arbitrary tree reduction pattern, until a single
   * result value is produced for each key.
   *
   * <p>By default, the {@code Coder} of the keys of the output
   * {@code PCollection<KV<K, OutputT>>} is that of the keys of the input
   * {@code PCollection<KV<K, InputT>>}, and the {@code Coder} of the values
   * of the output {@code PCollection<KV<K, OutputT>>} is inferred from the
   * concrete type of the {@code KeyedCombineFn<K, InputT, AccumT, OutputT>}'s output
   * type {@code OutputT}.
   *
   * <p>Each output element has the same timestamp and is in the same window
   * as its corresponding input element, and the output
   * {@code PCollection} has the same
   * {@link com.google.cloud.dataflow.sdk.transforms.windowing.WindowFn}
   * associated with it as the input.
   *
   * <p>See also {@link #globally}/{@link Globally Combine.Globally}, which
   * combines all the values in a {@code PCollection} into a
   * single value in a {@code PCollection}.
   *
   * @param <K> type of input and output keys
   * @param <InputT> type of input values
   * @param <OutputT> type of output values
   */
  public static class GroupedValues<K, InputT, OutputT>
      extends PTransform
                        <PCollection<? extends KV<K, ? extends Iterable<InputT>>>,
                         PCollection<KV<K, OutputT>>> {

    private final PerKeyCombineFn<? super K, ? super InputT, ?, OutputT> fn;
    private final List<PCollectionView<?>> sideInputs;

    private GroupedValues(PerKeyCombineFn<? super K, ? super InputT, ?, OutputT> fn) {
      this.fn = SerializableUtils.clone(fn);
      this.sideInputs = ImmutableList.<PCollectionView<?>>of();
    }

    private GroupedValues(
        PerKeyCombineFn<? super K, ? super InputT, ?, OutputT> fn,
        List<PCollectionView<?>> sideInputs) {
      this.fn = SerializableUtils.clone(fn);
      this.sideInputs = sideInputs;
    }

    public GroupedValues<K, InputT, OutputT> withSideInputs(
        Iterable<? extends PCollectionView<?>> sideInputs) {
      return new GroupedValues<>(fn, ImmutableList.<PCollectionView<?>>copyOf(sideInputs));
    }

    /**
     * Returns the KeyedCombineFn used by this Combine operation.
     */
    public PerKeyCombineFn<? super K, ? super InputT, ?, OutputT> getFn() {
      return fn;
    }

    public List<PCollectionView<?>> getSideInputs() {
      return sideInputs;
    }

    @Override
    public PCollection<KV<K, OutputT>> apply(
        PCollection<? extends KV<K, ? extends Iterable<InputT>>> input) {

      final PerKeyCombineFnRunner<? super K, ? super InputT, ?, OutputT> combineFnRunner =
          PerKeyCombineFnRunners.create(fn);
      PCollection<KV<K, OutputT>> output = input.apply(ParDo.of(
          new DoFn<KV<K, ? extends Iterable<InputT>>, KV<K, OutputT>>() {
            @Override
            public void processElement(ProcessContext c) {
              K key = c.element().getKey();

              c.output(KV.of(key, combineFnRunner.apply(key, c.element().getValue(), c)));
            }
          }).withSideInputs(sideInputs));

      try {
        Coder<KV<K, OutputT>> outputCoder = getDefaultOutputCoder(input);
        output.setCoder(outputCoder);
      } catch (CannotProvideCoderException exc) {
        // let coder inference happen later, if it can
      }

      return output;
    }

    /**
     * Returns the {@link CombineFn} bound to its coders.
     *
     * <p>For internal use.
     */
    public AppliedCombineFn<? super K, ? super InputT, ?, OutputT> getAppliedFn(
        CoderRegistry registry, Coder<? extends KV<K, ? extends Iterable<InputT>>> inputCoder,
        WindowingStrategy<?, ?> windowingStrategy) {
      KvCoder<K, InputT> kvCoder = getKvCoder(inputCoder);
      return AppliedCombineFn.withInputCoder(
          fn, registry, kvCoder, sideInputs, windowingStrategy);
    }

    private KvCoder<K, InputT> getKvCoder(
        Coder<? extends KV<K, ? extends Iterable<InputT>>> inputCoder) {
      if (!(inputCoder instanceof KvCoder)) {
        throw new IllegalStateException(
            "Combine.GroupedValues requires its input to use KvCoder");
      }
      @SuppressWarnings({"unchecked", "rawtypes"})
      KvCoder<K, ? extends Iterable<InputT>> kvCoder = (KvCoder) inputCoder;
      Coder<K> keyCoder = kvCoder.getKeyCoder();
      Coder<? extends Iterable<InputT>> kvValueCoder = kvCoder.getValueCoder();
      if (!(kvValueCoder instanceof IterableCoder)) {
        throw new IllegalStateException(
            "Combine.GroupedValues requires its input values to use "
            + "IterableCoder");
      }
      @SuppressWarnings("unchecked")
      IterableCoder<InputT> inputValuesCoder = (IterableCoder<InputT>) kvValueCoder;
      Coder<InputT> inputValueCoder = inputValuesCoder.getElemCoder();
      return KvCoder.of(keyCoder, inputValueCoder);
    }

    @Override
    public Coder<KV<K, OutputT>> getDefaultOutputCoder(
        PCollection<? extends KV<K, ? extends Iterable<InputT>>> input)
        throws CannotProvideCoderException {
      KvCoder<K, InputT> kvCoder = getKvCoder(input.getCoder());
      @SuppressWarnings("unchecked")
      Coder<OutputT> outputValueCoder =
          ((PerKeyCombineFn<K, InputT, ?, OutputT>) fn)
          .getDefaultOutputCoder(
              input.getPipeline().getCoderRegistry(),
              kvCoder.getKeyCoder(), kvCoder.getValueCoder());
      return KvCoder.of(kvCoder.getKeyCoder(), outputValueCoder);
    }
  }
}
