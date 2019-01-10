// Copyright 2018 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.exec;

import com.google.auto.value.AutoValue;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.collect.HashBasedTable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Ordering;
import com.google.common.collect.Table;
import com.google.devtools.build.lib.actions.ActionContext;
import com.google.devtools.build.lib.actions.ActionContextMarker;
import com.google.devtools.build.lib.actions.ActionExecutionContext;
import com.google.devtools.build.lib.actions.ExecException;
import com.google.devtools.build.lib.actions.ExecutionStrategy;
import com.google.devtools.build.lib.actions.ExecutorInitException;
import com.google.devtools.build.lib.actions.Spawn;
import com.google.devtools.build.lib.actions.SpawnActionContext;
import com.google.devtools.build.lib.actions.SpawnResult;
import com.google.devtools.build.lib.analysis.test.TestActionContext;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.EventHandler;
import com.google.devtools.build.lib.events.Reporter;
import com.google.devtools.build.lib.util.ExitCode;
import com.google.devtools.build.lib.util.RegexFilter;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Container for looking up the {@link ActionContext} to use for a given action.
 *
 * <p>Holds {@link ActionContext} mappings populated by modules. These include mappings from
 * mnemonics and from description patterns.
 *
 * <p>At startup time, the application provides {@link Builder} to each module to register its
 * contexts and mappings. At runtime, the {@link BlazeExecutor} uses the constructed object to find
 * the context for each action.
 */
public final class SpawnActionContextMaps {

  /** A stored entry for a {@link RegexFilter} to {@link SpawnActionContext} mapping. */
  @AutoValue
  public abstract static class RegexFilterSpawnActionContext {
    public abstract RegexFilter regexFilter();

    public abstract SpawnActionContext spawnActionContext();
  }

  private final ImmutableSortedMap<String, SpawnActionContext> spawnStrategyMnemonicMap;
  private final ImmutableList<ActionContext> strategies;
  private final ImmutableList<RegexFilterSpawnActionContext> spawnStrategyRegexList;

  private SpawnActionContextMaps(
      ImmutableSortedMap<String, SpawnActionContext> spawnStrategyMnemonicMap,
      ImmutableList<ActionContext> strategies,
      ImmutableList<RegexFilterSpawnActionContext> spawnStrategyRegexList) {
    this.spawnStrategyMnemonicMap = spawnStrategyMnemonicMap;
    this.strategies = strategies;
    this.spawnStrategyRegexList = spawnStrategyRegexList;
  }

  /**
   * Returns the appropriate {@link ActionContext} to execute the given {@link Spawn} with.
   *
   * <p>If the reason for selecting the context is worth mentioning to the user, logs a message
   * using the given {@link Reporter}.
   */
  public SpawnActionContext getSpawnActionContext(Spawn spawn, EventHandler reporter) {
    Preconditions.checkNotNull(spawn);
    if (!spawnStrategyRegexList.isEmpty() && spawn.getResourceOwner() != null) {
      String description = spawn.getResourceOwner().getProgressMessage();
      if (description != null) {
        for (RegexFilterSpawnActionContext entry : spawnStrategyRegexList) {
          if (entry.regexFilter().isIncluded(description) && entry.spawnActionContext() != null) {
            reporter.handle(Event.progress(
                description + " with context " + entry.spawnActionContext().toString()));
            return entry.spawnActionContext();
          }
        }
      }
    }
    SpawnActionContext context = spawnStrategyMnemonicMap.get(spawn.getMnemonic());
    if (context != null) {
      return context;
    }
    return Preconditions.checkNotNull(spawnStrategyMnemonicMap.get(""));
  }

  /** Returns a map from action context class to its instantiated context object. */
  public ImmutableMap<Class<? extends ActionContext>, ActionContext> contextMap() {
    Map<Class<? extends ActionContext>, ActionContext> contextMap = new HashMap<>();
    for (ActionContext context : strategies) {
      ExecutionStrategy annotation = context.getClass().getAnnotation(ExecutionStrategy.class);
      if (annotation != null) {
        contextMap.put(annotation.contextType(), context);
      }
      contextMap.put(context.getClass(), context);
    }
    contextMap.put(SpawnActionContext.class, new ProxySpawnActionContext());
    return ImmutableMap.copyOf(contextMap);
  }

  /** Returns a list of all referenced {@link ActionContext} instances. */
  public ImmutableList<ActionContext> allContexts() {
    // We need to keep only the last occurrences of the entries in contextImplementations
    // (so we respect insertion order but also instantiate them only once).
    LinkedHashSet<ActionContext> allContexts = new LinkedHashSet<>();
    allContexts.addAll(strategies);
    allContexts.addAll(spawnStrategyMnemonicMap.values());
    spawnStrategyRegexList.forEach(x -> allContexts.add(x.spawnActionContext()));
    return ImmutableList.copyOf(allContexts);
  }

  /**
   * Print a sorted list of our (Spawn)ActionContext maps.
   *
   * <p>Prints out debug information about the mappings.
   */
  public void debugPrintSpawnActionContextMaps(Reporter reporter) {
    for (Map.Entry<String, SpawnActionContext> entry : spawnStrategyMnemonicMap.entrySet()) {
      reporter.handle(
          Event.info(
              String.format(
                  "SpawnActionContextMap: \"%s\" = %s",
                  entry.getKey(), entry.getValue().getClass().getSimpleName())));
    }

    ImmutableMap<Class<? extends ActionContext>, ActionContext> contextMap = contextMap();
    TreeMap<String, String> sortedContextMapWithSimpleNames = new TreeMap<>();
    for (Map.Entry<Class<? extends ActionContext>, ActionContext> entry : contextMap.entrySet()) {
      sortedContextMapWithSimpleNames.put(
          entry.getKey().getSimpleName(), entry.getValue().getClass().getSimpleName());
    }
    for (Map.Entry<String, String> entry : sortedContextMapWithSimpleNames.entrySet()) {
      // Skip uninteresting identity mappings of contexts.
      if (!entry.getKey().equals(entry.getValue())) {
        reporter.handle(
            Event.info(String.format("ContextMap: %s = %s", entry.getKey(), entry.getValue())));
      }
    }

    for (RegexFilterSpawnActionContext entry : spawnStrategyRegexList) {
      reporter.handle(
          Event.info(
              String.format(
                  "SpawnActionContextMap: \"%s\" = %s",
                  entry.regexFilter().toString(),
                  entry.spawnActionContext().getClass().getSimpleName())));
    }
  }

  @VisibleForTesting
  public static SpawnActionContextMaps createStub(
      List<ActionContext> strategies, Map<String, SpawnActionContext> spawnStrategyMnemonicMap) {
    return new SpawnActionContextMaps(
        ImmutableSortedMap.<String, SpawnActionContext>orderedBy(String.CASE_INSENSITIVE_ORDER)
            .putAll(spawnStrategyMnemonicMap)
            .build(),
        ImmutableList.copyOf(strategies),
        ImmutableList.of());
  }

  /** A stored entry for a {@link RegexFilter} to {@code strategy} mapping. */
  @AutoValue
  public abstract static class RegexFilterStrategy {
    public abstract RegexFilter regexFilter();

    public abstract String strategy();
  }

  /** Builder for {@code SpawnActionContextMaps}. */
  public static final class Builder {
    private ImmutableListMultimap.Builder<String, String> strategyByMnemonicMapBuilder =
        ImmutableListMultimap.builder();
    private ImmutableListMultimap.Builder<Class<? extends ActionContext>, String>
        strategyByContextMapBuilder = ImmutableListMultimap.builder();

    private final ImmutableList.Builder<RegexFilterStrategy> strategyByRegexpBuilder =
        ImmutableList.builder();

    /**
     * Returns a builder modules can use to add mappings from mnemonics to strategy names.
     *
     * <p>If a spawn action is executed whose mnemonic maps to the empty string or is not present in
     * the map at all, the choice of the implementation is left to Blaze.
     *
     * <p>Matching on mnemonics is done case-insensitively so it is recommended that any
     * module makes sure that no two strategies refer to the same mnemonic.  If they do, Blaze
     * will pick the last one added.
     */
    public ImmutableMultimap.Builder<String, String> strategyByMnemonicMap() {
      return strategyByMnemonicMapBuilder;
    }

    /**
     * Returns a builder modules can use to associate {@link ActionContext} classes with
     * strategy names.
     */
    public ImmutableMultimap.Builder<Class<? extends ActionContext>, String>
        strategyByContextMap() {
      return strategyByContextMapBuilder;
    }

    /** Adds a mapping from the given {@link RegexFilter} to a {@code strategy}. */
    public void addStrategyByRegexp(RegexFilter regexFilter, String strategy) {
      strategyByRegexpBuilder.add(
          new AutoValue_SpawnActionContextMaps_RegexFilterStrategy(regexFilter, strategy));
    }

    /** Builds a {@link SpawnActionContextMaps} instance. */
    public SpawnActionContextMaps build(
        ImmutableList<ActionContextProvider> actionContextProviders, String testStrategyValue)
        throws ExecutorInitException {
      StrategyConverter strategyConverter = new StrategyConverter(actionContextProviders);

      ImmutableSortedMap.Builder<String, SpawnActionContext> spawnStrategyMap =
          ImmutableSortedMap.orderedBy(String.CASE_INSENSITIVE_ORDER);
      ImmutableList.Builder<ActionContext> strategies = ImmutableList.builder();
      ImmutableList.Builder<RegexFilterSpawnActionContext> spawnStrategyRegexList =
          ImmutableList.builder();

      ImmutableListMultimap<String, String> multimap = strategyByMnemonicMapBuilder.build();
      for (String mnemonic : multimap.keySet()) {
        String strategy = Iterables.getLast(multimap.get(mnemonic));
        if (strategy.isEmpty() && !mnemonic.isEmpty()) {
          // If strategy is set to the empty value, and we're not looking at the empty mnemonic,
          // then don't create a per-mnemonic entry for this case at all. At runtime, we'll fall
          // back to the setting for the empty mnemonic, which may or may not be the setting for
          // the "" strategy (the default strategy), but may be overridden to a specific one.
          continue;
        }
        SpawnActionContext context =
            strategyConverter.getStrategy(SpawnActionContext.class, strategy);
        if (context == null) {
          String strategyOrNull = Strings.emptyToNull(strategy);
          throw makeExceptionForInvalidStrategyValue(
              strategy,
              Joiner.on(' ').skipNulls().join(strategyOrNull, "spawn"),
              strategyConverter.getValidValues(SpawnActionContext.class));
        }
        spawnStrategyMap.put(mnemonic, context);
      }

      Set<ActionContext> seenContext = new HashSet<>();
      for (Map.Entry<Class<? extends ActionContext>, String> entry :
          strategyByContextMapBuilder.orderValuesBy(Collections.reverseOrder()).build().entries()) {
        ActionContext context = strategyConverter.getStrategy(entry.getKey(), entry.getValue());
        if (context == null) {
          throw makeExceptionForInvalidStrategyValue(
              entry.getValue(),
              strategyConverter.getUserFriendlyName(entry.getKey()),
              strategyConverter.getValidValues(entry.getKey()));
        }
        if (seenContext.contains(context)) {
          continue;
        }
        seenContext.add(context);
        strategies.add(context);
      }

      for (RegexFilterStrategy entry : strategyByRegexpBuilder.build()) {
        SpawnActionContext context =
            strategyConverter.getStrategy(SpawnActionContext.class, entry.strategy());
        if (context == null) {
          String strategy = Strings.emptyToNull(entry.strategy().toString());
          throw makeExceptionForInvalidStrategyValue(
              entry.regexFilter().toString(),
              Joiner.on(' ').skipNulls().join(strategy, "spawn"),
              strategyConverter.getValidValues(SpawnActionContext.class));
        }

        spawnStrategyRegexList.add(
            new AutoValue_SpawnActionContextMaps_RegexFilterSpawnActionContext(
                entry.regexFilter(), context));
      }

      ActionContext context =
          strategyConverter.getStrategy(TestActionContext.class, testStrategyValue);
      if (context == null) {
        throw makeExceptionForInvalidStrategyValue(
            testStrategyValue, "test", strategyConverter.getValidValues(TestActionContext.class));
      }
      strategies.add(context);

      return new SpawnActionContextMaps(
          spawnStrategyMap.build(), strategies.build(), spawnStrategyRegexList.build());
    }
  }

  private static ExecutorInitException makeExceptionForInvalidStrategyValue(
      String value, String strategy, String validValues) {
    return new ExecutorInitException(
        String.format(
            "'%s' is an invalid value for %s strategy. Valid values are: %s",
            value, strategy, validValues),
        ExitCode.COMMAND_LINE_ERROR);
  }

  private static class StrategyConverter {
    private Table<Class<? extends ActionContext>, String, ActionContext> classMap =
        HashBasedTable.create();
    private Map<Class<? extends ActionContext>, ActionContext> defaultClassMap = new HashMap<>();

    /** Aggregates all {@link ActionContext}s that are in {@code contextProviders}. */
    @SuppressWarnings("unchecked")
    private StrategyConverter(Iterable<ActionContextProvider> contextProviders) {
      for (ActionContextProvider provider : contextProviders) {
        for (ActionContext strategy : provider.getActionContexts()) {
          ExecutionStrategy annotation = strategy.getClass().getAnnotation(ExecutionStrategy.class);
          // TODO(ulfjack): Don't silently ignore action contexts without annotation.
          if (annotation != null) {
            defaultClassMap.put(annotation.contextType(), strategy);

            for (String name : annotation.name()) {
              classMap.put(annotation.contextType(), name, strategy);
            }
          }
        }
      }
    }

    @SuppressWarnings("unchecked")
    private <T extends ActionContext> T getStrategy(Class<T> clazz, String name) {
      return (T) (name.isEmpty() ? defaultClassMap.get(clazz) : classMap.get(clazz, name));
    }

    private String getValidValues(Class<? extends ActionContext> context) {
      return Joiner.on(", ").join(Ordering.natural().sortedCopy(classMap.row(context).keySet()));
    }

    private String getUserFriendlyName(Class<? extends ActionContext> context) {
      ActionContextMarker marker = context.getAnnotation(ActionContextMarker.class);
      return marker != null ? marker.name() : context.getSimpleName();
    }
  }

  /** Proxy that looks up the right SpawnActionContext for a spawn during exec. */
  @VisibleForTesting
  public final class ProxySpawnActionContext implements SpawnActionContext {
    @Override
    public List<SpawnResult> exec(Spawn spawn, ActionExecutionContext actionExecutionContext)
        throws ExecException, InterruptedException {
      return resolve(spawn, actionExecutionContext.getEventHandler())
          .exec(spawn, actionExecutionContext);
    }

    @VisibleForTesting
    public SpawnActionContext resolve(Spawn spawn, EventHandler eventHandler) {
      return getSpawnActionContext(spawn, eventHandler);
    }
  }
}
