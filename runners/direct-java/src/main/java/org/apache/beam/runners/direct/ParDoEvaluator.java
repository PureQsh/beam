/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.beam.runners.direct;

import com.google.common.collect.ImmutableList;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.beam.runners.core.DoFnRunner;
import org.apache.beam.runners.core.DoFnRunners;
import org.apache.beam.runners.core.DoFnRunners.OutputManager;
import org.apache.beam.runners.core.PushbackSideInputDoFnRunner;
import org.apache.beam.runners.direct.DirectExecutionContext.DirectStepContext;
import org.apache.beam.runners.direct.DirectRunner.UncommittedBundle;
import org.apache.beam.sdk.transforms.AppliedPTransform;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.windowing.BoundedWindow;
import org.apache.beam.sdk.util.ReadyCheckingSideInputReader;
import org.apache.beam.sdk.util.TimerInternals.TimerData;
import org.apache.beam.sdk.util.UserCodeException;
import org.apache.beam.sdk.util.WindowedValue;
import org.apache.beam.sdk.util.WindowingStrategy;
import org.apache.beam.sdk.values.PCollection;
import org.apache.beam.sdk.values.PCollectionView;
import org.apache.beam.sdk.values.TupleTag;

class ParDoEvaluator<InputT, OutputT> implements TransformEvaluator<InputT> {

  public static <InputT, OutputT> ParDoEvaluator<InputT, OutputT> create(
      EvaluationContext evaluationContext,
      DirectStepContext stepContext,
      AppliedPTransform<?, ?, ?> application,
      WindowingStrategy<?, ? extends BoundedWindow> windowingStrategy,
      DoFn<InputT, OutputT> fn,
      StructuralKey<?> key,
      List<PCollectionView<?>> sideInputs,
      TupleTag<OutputT> mainOutputTag,
      List<TupleTag<?>> sideOutputTags,
      Map<TupleTag<?>, PCollection<?>> outputs) {
    AggregatorContainer.Mutator aggregatorChanges = evaluationContext.getAggregatorMutator();

    Map<TupleTag<?>, UncommittedBundle<?>> outputBundles = new HashMap<>();
    for (Map.Entry<TupleTag<?>, PCollection<?>> outputEntry : outputs.entrySet()) {
      // Just trust the context's decision as to whether the output should be keyed.
      // The logic for whether this ParDo is key-preserving and whether the input
      // is keyed lives elsewhere.
      if (evaluationContext.isKeyed(outputEntry.getValue())) {
        outputBundles.put(
            outputEntry.getKey(), evaluationContext.createKeyedBundle(key, outputEntry.getValue()));
      } else {
        outputBundles.put(
            outputEntry.getKey(), evaluationContext.createBundle(outputEntry.getValue()));
      }
    }
    BundleOutputManager outputManager = BundleOutputManager.create(outputBundles);

    ReadyCheckingSideInputReader sideInputReader =
        evaluationContext.createSideInputReader(sideInputs);

    DoFnRunner<InputT, OutputT> underlying =
        DoFnRunners.simpleRunner(
            evaluationContext.getPipelineOptions(),
            fn,
            sideInputReader,
            outputManager,
            mainOutputTag,
            sideOutputTags,
            stepContext,
            aggregatorChanges,
            windowingStrategy);
    PushbackSideInputDoFnRunner<InputT, OutputT> runner =
        PushbackSideInputDoFnRunner.create(underlying, sideInputs, sideInputReader);

    try {
      runner.startBundle();
    } catch (Exception e) {
      throw UserCodeException.wrap(e);
    }

    return new ParDoEvaluator<>(
        evaluationContext, runner, application, aggregatorChanges, outputManager, stepContext);
  }

  ////////////////////////////////////////////////////////////////////////////////////////////////

  private final EvaluationContext evaluationContext;
  private final PushbackSideInputDoFnRunner<InputT, ?> fnRunner;
  private final AppliedPTransform<?, ?, ?> transform;
  private final AggregatorContainer.Mutator aggregatorChanges;
  private final BundleOutputManager outputManager;
  private final DirectStepContext stepContext;

  private final ImmutableList.Builder<WindowedValue<InputT>> unprocessedElements;

  private ParDoEvaluator(
      EvaluationContext evaluationContext,
      PushbackSideInputDoFnRunner<InputT, ?> fnRunner,
      AppliedPTransform<?, ?, ?> transform,
      AggregatorContainer.Mutator aggregatorChanges,
      BundleOutputManager outputManager,
      DirectStepContext stepContext) {
    this.evaluationContext = evaluationContext;
    this.fnRunner = fnRunner;
    this.transform = transform;
    this.outputManager = outputManager;
    this.stepContext = stepContext;
    this.aggregatorChanges = aggregatorChanges;
    this.unprocessedElements = ImmutableList.builder();
  }

  public BundleOutputManager getOutputManager() {
    return outputManager;
  }

  @Override
  public void processElement(WindowedValue<InputT> element) {
    try {
      Iterable<WindowedValue<InputT>> unprocessed = fnRunner.processElementInReadyWindows(element);
      unprocessedElements.addAll(unprocessed);
    } catch (Exception e) {
      throw UserCodeException.wrap(e);
    }
  }

  public void onTimer(TimerData timer, BoundedWindow window) {
    try {
      fnRunner.onTimer(timer.getTimerId(), window, timer.getTimestamp(), timer.getDomain());
    } catch (Exception e) {
      throw UserCodeException.wrap(e);
    }
  }

  @Override
  public TransformResult<InputT> finishBundle() {
    try {
      fnRunner.finishBundle();
    } catch (Exception e) {
      throw UserCodeException.wrap(e);
    }
    StepTransformResult.Builder resultBuilder;
    CopyOnAccessInMemoryStateInternals<?> state = stepContext.commitState();
    if (state != null) {
      resultBuilder =
          StepTransformResult.withHold(transform, state.getEarliestWatermarkHold())
              .withState(state);
    } else {
      resultBuilder = StepTransformResult.withoutHold(transform);
    }
    return resultBuilder
        .addOutput(outputManager.bundles.values())
        .withTimerUpdate(stepContext.getTimerUpdate())
        .withAggregatorChanges(aggregatorChanges)
        .addUnprocessedElements(unprocessedElements.build())
        .build();
  }

  static class BundleOutputManager implements OutputManager {
    private final Map<TupleTag<?>, UncommittedBundle<?>> bundles;
    private final Map<TupleTag<?>, List<?>> undeclaredOutputs;

    public static BundleOutputManager create(Map<TupleTag<?>, UncommittedBundle<?>> outputBundles) {
      return new BundleOutputManager(outputBundles);
    }

    private BundleOutputManager(Map<TupleTag<?>, UncommittedBundle<?>> bundles) {
      this.bundles = bundles;
      undeclaredOutputs = new HashMap<>();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Override
    public <T> void output(TupleTag<T> tag, WindowedValue<T> output) {
      UncommittedBundle bundle = bundles.get(tag);
      if (bundle == null) {
        List<WindowedValue<T>> undeclaredContents = (List) undeclaredOutputs.get(tag);
        if (undeclaredContents == null) {
          undeclaredContents = new ArrayList<>();
          undeclaredOutputs.put(tag, undeclaredContents);
        }
        undeclaredContents.add(output);
      } else {
        bundle.add(output);
      }
    }
  }
}
