// Copyright 2015 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.actions.util;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.truth.Truth.assertThat;

import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Streams;
import com.google.common.eventbus.EventBus;
import com.google.devtools.build.lib.actions.AbstractAction;
import com.google.devtools.build.lib.actions.Action;
import com.google.devtools.build.lib.actions.ActionAnalysisMetadata;
import com.google.devtools.build.lib.actions.ActionExecutionContext;
import com.google.devtools.build.lib.actions.ActionGraph;
import com.google.devtools.build.lib.actions.ActionInput;
import com.google.devtools.build.lib.actions.ActionInputHelper;
import com.google.devtools.build.lib.actions.ActionInputPrefetcher;
import com.google.devtools.build.lib.actions.ActionKeyContext;
import com.google.devtools.build.lib.actions.ActionOwner;
import com.google.devtools.build.lib.actions.ActionResult;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.Artifact.ArtifactExpander;
import com.google.devtools.build.lib.actions.Artifact.SpecialArtifact;
import com.google.devtools.build.lib.actions.Artifact.TreeFileArtifact;
import com.google.devtools.build.lib.actions.ArtifactOwner;
import com.google.devtools.build.lib.actions.ArtifactResolver;
import com.google.devtools.build.lib.actions.ArtifactRoot;
import com.google.devtools.build.lib.actions.Executor;
import com.google.devtools.build.lib.actions.FileArtifactValue;
import com.google.devtools.build.lib.actions.MutableActionGraph;
import com.google.devtools.build.lib.actions.MutableActionGraph.ActionConflictException;
import com.google.devtools.build.lib.actions.PackageRootResolver;
import com.google.devtools.build.lib.actions.cache.Md5Digest;
import com.google.devtools.build.lib.actions.cache.MetadataHandler;
import com.google.devtools.build.lib.actions.cache.Protos.ActionCacheStatistics.MissDetail;
import com.google.devtools.build.lib.actions.cache.Protos.ActionCacheStatistics.MissReason;
import com.google.devtools.build.lib.analysis.actions.CustomCommandLine;
import com.google.devtools.build.lib.analysis.actions.SpawnActionTemplate;
import com.google.devtools.build.lib.analysis.actions.SpawnActionTemplate.OutputPathMapper;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.RepositoryName;
import com.google.devtools.build.lib.events.EventHandler;
import com.google.devtools.build.lib.events.ExtendedEventHandler;
import com.google.devtools.build.lib.events.Reporter;
import com.google.devtools.build.lib.exec.SingleBuildFileCache;
import com.google.devtools.build.lib.packages.AspectDescriptor;
import com.google.devtools.build.lib.skyframe.serialization.autocodec.AutoCodec;
import com.google.devtools.build.lib.util.FileType;
import com.google.devtools.build.lib.util.Fingerprint;
import com.google.devtools.build.lib.util.ResourceUsage;
import com.google.devtools.build.lib.util.io.FileOutErr;
import com.google.devtools.build.lib.vfs.FileStatus;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.Root;
import com.google.devtools.build.lib.vfs.inmemoryfs.InMemoryFileSystem;
import com.google.devtools.build.skyframe.AbstractSkyFunctionEnvironment;
import com.google.devtools.build.skyframe.BuildDriver;
import com.google.devtools.build.skyframe.ErrorInfo;
import com.google.devtools.build.skyframe.EvaluationContext;
import com.google.devtools.build.skyframe.EvaluationResult;
import com.google.devtools.build.skyframe.SkyFunction;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;
import com.google.devtools.build.skyframe.ValueOrUntypedException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import javax.annotation.Nullable;

/**
 * A bunch of utilities that are useful for test concerning actions, artifacts,
 * etc.
 */
public final class ActionsTestUtil {

  private final ActionGraph actionGraph;

  public ActionsTestUtil(ActionGraph actionGraph) {
    this.actionGraph = actionGraph;
  }

  private static final Label NULL_LABEL = Label.parseAbsoluteUnchecked("//null/action:owner");

  public static ActionExecutionContext createContext(
      Executor executor,
      ActionKeyContext actionKeyContext,
      FileOutErr fileOutErr,
      Path execRoot,
      MetadataHandler metadataHandler,
      @Nullable ActionGraph actionGraph) {
    return createContext(
        executor,
        actionKeyContext,
        fileOutErr,
        execRoot,
        metadataHandler,
        ImmutableMap.of(),
        actionGraph);
  }

  public static ActionExecutionContext createContext(
      Executor executor,
      ActionKeyContext actionKeyContext,
      FileOutErr fileOutErr,
      Path execRoot,
      MetadataHandler metadataHandler,
      Map<String, String> clientEnv,
      @Nullable ActionGraph actionGraph) {
    return new ActionExecutionContext(
        executor,
        new SingleBuildFileCache(execRoot.getPathString(), execRoot.getFileSystem()),
        ActionInputPrefetcher.NONE,
        actionKeyContext,
        metadataHandler,
        fileOutErr,
        ImmutableMap.copyOf(clientEnv),
        ImmutableMap.of(),
        actionGraph == null
            ? createDummyArtifactExpander()
            : ActionInputHelper.actionGraphArtifactExpander(actionGraph),
        /*actionFileSystem=*/ null,
        /*skyframeDepsResult=*/ null);
  }

  public static ActionExecutionContext createContextForInputDiscovery(
      Executor executor,
      ActionKeyContext actionKeyContext,
      FileOutErr fileOutErr,
      Path execRoot,
      MetadataHandler metadataHandler,
      BuildDriver buildDriver) {
    return ActionExecutionContext.forInputDiscovery(
        executor,
        new SingleBuildFileCache(execRoot.getPathString(), execRoot.getFileSystem()),
        ActionInputPrefetcher.NONE,
        actionKeyContext,
        metadataHandler,
        fileOutErr,
        ImmutableMap.of(),
        new BlockingSkyFunctionEnvironment(
            buildDriver, executor == null ? null : executor.getEventHandler()),
        /*actionFileSystem=*/ null);
  }

  public static ActionExecutionContext createContext(ExtendedEventHandler eventHandler) {
    DummyExecutor dummyExecutor = new DummyExecutor(eventHandler);
    return new ActionExecutionContext(
        dummyExecutor,
        null,
        ActionInputPrefetcher.NONE,
        new ActionKeyContext(),
        null,
        null,
        ImmutableMap.of(),
        ImmutableMap.of(),
        createDummyArtifactExpander(),
        /*actionFileSystem=*/ null,
        /*skyframeDepsResult=*/ null);
  }

  private static ArtifactExpander createDummyArtifactExpander() {
    return new ArtifactExpander() {
      @Override
      public void expand(Artifact artifact, Collection<? super Artifact> output) {
        return;
      }
    };
  }


  /**
   * {@link SkyFunction.Environment} that internally makes a full Skyframe evaluate call for the
   * requested keys, blocking until the values are ready.
   */
  private static class BlockingSkyFunctionEnvironment extends AbstractSkyFunctionEnvironment {
    private final BuildDriver driver;
    private final EventHandler eventHandler;

    private BlockingSkyFunctionEnvironment(BuildDriver driver, EventHandler eventHandler) {
      this.driver = driver;
      this.eventHandler = eventHandler;
    }

    @Override
    protected Map<SkyKey, ValueOrUntypedException> getValueOrUntypedExceptions(
        Iterable<? extends SkyKey> depKeys) {
      EvaluationResult<SkyValue> evaluationResult;
      Map<SkyKey, ValueOrUntypedException> result = new HashMap<>();
      try {
        EvaluationContext evaluationContext =
            EvaluationContext.newBuilder()
                .setKeepGoing(false)
                .setNumThreads(ResourceUsage.getAvailableProcessors())
                .setEventHander(new Reporter(new EventBus(), eventHandler))
                .build();
        evaluationResult = driver.evaluate(depKeys, evaluationContext);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        for (SkyKey key : depKeys) {
          result.put(key, ValueOrUntypedException.ofNull());
        }
        return result;
      }
      for (SkyKey key : depKeys) {
        SkyValue value = evaluationResult.get(key);
        if (value != null) {
          result.put(key, ValueOrUntypedException.ofValueUntyped(value));
          continue;
        }
        errorMightHaveBeenFound = true;
        ErrorInfo errorInfo = evaluationResult.getError(key);
        if (errorInfo == null || errorInfo.getException() == null) {
          result.put(key, ValueOrUntypedException.ofNull());
          continue;
        }
        result.put(key, ValueOrUntypedException.ofExn(errorInfo.getException()));
      }
      return result;
    }

    @Override
    public ExtendedEventHandler getListener() {
      return null;
    }

    @Override
    public boolean inErrorBubblingForTesting() {
      return false;
    }
  }

  public static final Artifact DUMMY_ARTIFACT =
      new Artifact(
          PathFragment.create("/dummy"),
          ArtifactRoot.asSourceRoot(Root.absoluteRoot(new InMemoryFileSystem())));

  public static final ActionOwner NULL_ACTION_OWNER =
      ActionOwner.create(
          NULL_LABEL,
          ImmutableList.<AspectDescriptor>of(),
          null,
          "dummy-configuration-mnemonic",
          null,
          "dummy-configuration",
          null,
          null,
          null);

  static class NullArtifactOwner implements ArtifactOwner {
    private NullArtifactOwner() {}

    @Override
    public Label getLabel() {
      return NULL_LABEL;
    }
  }

  @AutoCodec public static final ArtifactOwner NULL_ARTIFACT_OWNER = new NullArtifactOwner();

  /** An unchecked exception class for action conflicts. */
  public static class UncheckedActionConflictException extends RuntimeException {
    public UncheckedActionConflictException(ActionConflictException e) {
      super(e);
    }
  }

  /**
   * A dummy Action class for use in tests.
   */
  public static class NullAction extends AbstractAction {

    public NullAction() {
      super(NULL_ACTION_OWNER, Artifact.NO_ARTIFACTS, ImmutableList.of(DUMMY_ARTIFACT));
    }

    public NullAction(ActionOwner owner, Artifact... outputs) {
      super(owner, Artifact.NO_ARTIFACTS, ImmutableList.copyOf(outputs));
    }

    public NullAction(Artifact... outputs) {
      super(NULL_ACTION_OWNER, Artifact.NO_ARTIFACTS, ImmutableList.copyOf(outputs));
    }

    public NullAction(List<Artifact> inputs, Artifact... outputs) {
      super(NULL_ACTION_OWNER, inputs, ImmutableList.copyOf(outputs));
    }

    @Override
    public ActionResult execute(ActionExecutionContext actionExecutionContext) {
      return ActionResult.EMPTY;
    }

    @Override
    protected void computeKey(ActionKeyContext actionKeyContext, Fingerprint fp) {
      fp.addString("action");
    }

    @Override
    public String getMnemonic() {
      return "Null";
    }
  }

  /**
   * For a bunch of actions, gets the basenames of the paths and accumulates
   * them in a space separated string, like <code>foo.o bar.o baz.a</code>.
   */
  public static String baseNamesOf(Iterable<Artifact> artifacts) {
    List<String> baseNames = baseArtifactNames(artifacts);
    return Joiner.on(' ').join(baseNames);
  }

  /**
   * For a bunch of actions, gets the basenames of the paths, sorts them in alphabetical
   * order and accumulates them in a space separated string, for example
   * <code>bar.o baz.a foo.o</code>.
   */
  public static String sortedBaseNamesOf(Iterable<Artifact> artifacts) {
    List<String> baseNames = baseArtifactNames(artifacts);
    Collections.sort(baseNames);
    return Joiner.on(' ').join(baseNames);
  }

  /**
   * For a bunch of artifacts, gets the basenames and accumulates them in a
   * List.
   */
  public static List<String> baseArtifactNames(Iterable<Artifact> artifacts) {
    return transform(artifacts, artifact -> artifact.getExecPath().getBaseName());
  }

  /**
   * For a bunch of artifacts, gets the exec paths and accumulates them in a
   * List.
   */
  public static List<String> execPaths(Iterable<Artifact> artifacts) {
    return transform(artifacts, Artifact::getExecPathString);
  }

  /**
   * For a bunch of artifacts, gets the pretty printed names and accumulates them in a List. Note
   * that this returns the root-relative paths, not the exec paths.
   */
  public static List<String> prettyArtifactNames(Iterable<Artifact> artifacts) {
    return transform(artifacts, Artifact::prettyPrint);
  }

  public static <T, R> List<R> transform(Iterable<T> iterable, Function<T, R> mapper) {
    // Can not use com.google.common.collect.Iterables.transform() there, as it returns Iterable.
    return Streams.stream(iterable)
        .map(mapper)
        .collect(Collectors.toList());
  }

  /**
   * Returns the closure of the predecessors of any of the given types, joining the basenames of the
   * artifacts into a space-separated string like "libfoo.a libbar.a libbaz.a".
   */
  public String predecessorClosureOf(Artifact artifact, FileType... types) {
    return predecessorClosureOf(Collections.singleton(artifact), types);
  }

  /**
   * Returns the closure of the predecessors of any of the given types.
   */
  public Collection<String> predecessorClosureAsCollection(Artifact artifact, FileType... types) {
    return predecessorClosureAsCollection(Collections.singleton(artifact), types);
  }

  /**
   * Returns the closure of the predecessors of any of the given types, joining the basenames of the
   * artifacts into a space-separated string like "libfoo.a libbar.a libbaz.a".
   */
  public String predecessorClosureOf(Iterable<Artifact> artifacts, FileType... types) {
    Set<Artifact> visited = artifactClosureOf(artifacts);
    return baseNamesOf(FileType.filter(visited, types));
  }

  /**
   * Returns the closure of the predecessors of any of the given types.
   */
  public Collection<String> predecessorClosureAsCollection(Iterable<Artifact> artifacts,
      FileType... types) {
    return baseArtifactNames(FileType.filter(artifactClosureOf(artifacts), types));
  }

  public String predecessorClosureOfJars(Iterable<Artifact> artifacts, FileType... types) {
    return baseNamesOf(FileType.filter(artifactClosureOf(artifacts), types));
  }

  public Collection<String> predecessorClosureJarsAsCollection(Iterable<Artifact> artifacts,
      FileType... types) {
    Set<Artifact> visited = artifactClosureOf(artifacts);
    return baseArtifactNames(FileType.filter(visited, types));
  }

  /**
   * Returns the closure over the input files of an action.
   */
  public Set<Artifact> inputClosureOf(ActionAnalysisMetadata action) {
    return artifactClosureOf(action.getInputs());
  }

  /**
   * Returns the closure over the input files of an artifact.
   */
  public Set<Artifact> artifactClosureOf(Artifact artifact) {
    return artifactClosureOf(Collections.singleton(artifact));
  }

  /**
   * Returns the closure over the input files of an artifact, filtered by the given matcher.
   */
  public Set<Artifact> filteredArtifactClosureOf(Artifact artifact, Predicate<Artifact> matcher) {
    return ImmutableSet.copyOf(Iterables.filter(artifactClosureOf(artifact), matcher));
  }

  /**
   * Returns the closure over the input files of a set of artifacts.
   */
  public Set<Artifact> artifactClosureOf(Iterable<Artifact> artifacts) {
    Set<Artifact> visited = new LinkedHashSet<>();
    List<Artifact> toVisit = Lists.newArrayList(artifacts);
    while (!toVisit.isEmpty()) {
      Artifact current = toVisit.remove(0);
      if (!visited.add(current)) {
        continue;
      }
      ActionAnalysisMetadata generatingAction = actionGraph.getGeneratingAction(current);
      if (generatingAction != null) {
        Iterables.addAll(toVisit, generatingAction.getInputs());
      }
    }
    return visited;
  }

  /**
   * Returns the closure over the input files of a set of artifacts, filtered by the given matcher.
   */
  public Set<Artifact> filteredArtifactClosureOf(Iterable<Artifact> artifacts,
      Predicate<Artifact> matcher) {
    return ImmutableSet.copyOf(Iterables.filter(artifactClosureOf(artifacts), matcher));
  }

  /**
   * Returns a predicate to match {@link Artifact}s with the given root-relative path suffix.
   */
  public static Predicate<Artifact> getArtifactSuffixMatcher(final String suffix) {
    return new Predicate<Artifact>() {
      @Override
      public boolean apply(Artifact input) {
        return input.getRootRelativePath().getPathString().endsWith(suffix);
      }
    };
  }

  /**
   * Finds all the actions that are instances of <code>actionClass</code>
   * in the transitive closure of prerequisites.
   */
  public <A extends Action> List<A> findTransitivePrerequisitesOf(Artifact artifact,
      Class<A> actionClass, Predicate<Artifact> allowedArtifacts) {
    List<A> actions = new ArrayList<>();
    Set<Artifact> visited = new LinkedHashSet<>();
    List<Artifact> toVisit = new LinkedList<>();
    toVisit.add(artifact);
    while (!toVisit.isEmpty()) {
      Artifact current = toVisit.remove(0);
      if (!visited.add(current)) {
        continue;
      }
      ActionAnalysisMetadata generatingAction = actionGraph.getGeneratingAction(current);
      if (generatingAction != null) {
        Iterables.addAll(toVisit, Iterables.filter(generatingAction.getInputs(), allowedArtifacts));
        if (actionClass.isInstance(generatingAction)) {
          actions.add(actionClass.cast(generatingAction));
        }
      }
    }
    return actions;
  }

  public <A extends Action> List<A> findTransitivePrerequisitesOf(
      Artifact artifact, Class<A> actionClass) {
    return findTransitivePrerequisitesOf(artifact, actionClass, Predicates.<Artifact>alwaysTrue());
  }

  /**
   * Looks in the given artifacts Iterable for the first Artifact whose path ends with the given
   * suffix and returns its generating Action.
   */
  public Action getActionForArtifactEndingWith(
      Iterable<Artifact> artifacts, String suffix) {
    Artifact a = getFirstArtifactEndingWith(artifacts, suffix);

    if (a == null) {
      return null;
    }

    ActionAnalysisMetadata action = actionGraph.getGeneratingAction(a);
    if (action != null) {
      Preconditions.checkState(
          action instanceof Action,
          "%s is not a proper Action object",
          action.prettyPrint());
      return (Action) action;
    } else {
      return null;
    }
  }

  /**
   * Looks in the given artifacts Iterable for the first Artifact whose path ends with the given
   * suffix and returns the Artifact.
   */
  public static Artifact getFirstArtifactEndingWith(
      Iterable<Artifact> artifacts, String suffix) {
    for (Artifact a : artifacts) {
      if (a.getExecPath().getPathString().endsWith(suffix)) {
        return a;
      }
    }
    return null;
  }

  /**
   * Returns the first artifact which is an input to "action" and has the
   * specified basename. An assertion error is raised if none is found.
   */
  public static Artifact getInput(ActionAnalysisMetadata action, String basename) {
    for (Artifact artifact : action.getInputs()) {
      if (artifact.getExecPath().getBaseName().equals(basename)) {
        return artifact;
      }
    }
    throw new AssertionError("No input with basename '" + basename + "' in action " + action);
  }

  /**
   * Returns true if an artifact that is an input to "action" with the specific
   * basename exists.
   */
  public static boolean hasInput(ActionAnalysisMetadata action, String basename) {
    try {
      getInput(action, basename);
      return true;
    } catch (AssertionError e) {
      return false;
    }
  }

  /**
   * Assert that an artifact is the primary output of its generating action.
   */
  public void assertPrimaryInputAndOutputArtifacts(Artifact input, Artifact output) {
    ActionAnalysisMetadata generatingAction = actionGraph.getGeneratingAction(output);
    assertThat(generatingAction).isNotNull();
    assertThat(generatingAction.getPrimaryOutput()).isEqualTo(output);
    assertThat(generatingAction.getPrimaryInput()).isEqualTo(input);
  }

  /**
   * Returns the first artifact which is an output of "action" and has the
   * specified basename. An assertion error is raised if none is found.
   */
  public static Artifact getOutput(ActionAnalysisMetadata action, String basename) {
    for (Artifact artifact : action.getOutputs()) {
      if (artifact.getExecPath().getBaseName().equals(basename)) {
        return artifact;
      }
    }
    throw new AssertionError("No output with basename '" + basename + "' in action " + action);
  }

  public static void registerActionWith(ActionAnalysisMetadata action,
      MutableActionGraph actionGraph) {
    try {
      actionGraph.registerAction(action);
    } catch (ActionConflictException e) {
      throw new UncheckedActionConflictException(e);
    }
  }

  public static SpawnActionTemplate createDummySpawnActionTemplate(
      SpecialArtifact inputTreeArtifact, SpecialArtifact outputTreeArtifact) {
    return new SpawnActionTemplate.Builder(inputTreeArtifact, outputTreeArtifact)
        .setCommandLineTemplate(CustomCommandLine.builder().build())
        .setExecutable(PathFragment.create("bin/executable"))
        .setOutputPathMapper(new OutputPathMapper() {
          @Override
          public PathFragment parentRelativeOutputPath(TreeFileArtifact inputTreeFileArtifact) {
            return inputTreeFileArtifact.getParentRelativePath();
          }
        })
        .build(NULL_ACTION_OWNER);
  }

  /** Builder for a list of {@link MissDetail}s with defaults set to zero for all possible items. */
  public static class MissDetailsBuilder {
    private final Map<MissReason, Integer> details = new EnumMap<>(MissReason.class);

    /** Constructs a new builder with all possible cache miss reasons set to zero counts. */
    public MissDetailsBuilder() {
      for (MissReason reason : MissReason.values()) {
        if (reason == MissReason.UNRECOGNIZED) {
          // The presence of this enum value is a protobuf artifact and not part of our metrics
          // collection. Just skip it.
          continue;
        }
        details.put(reason, 0);
      }
    }

    /** Sets the count of the given miss reason to the given value. */
    public MissDetailsBuilder set(MissReason reason, int count) {
      checkArgument(details.containsKey(reason));
      details.put(reason, count);
      return this;
    }

    /** Constructs the list of {@link MissDetail}s. */
    public Iterable<MissDetail> build() {
      List<MissDetail> result = new ArrayList<>(details.size());
      for (Map.Entry<MissReason, Integer> entry : details.entrySet()) {
        MissDetail detail = MissDetail.newBuilder()
            .setReason(entry.getKey())
            .setCount(entry.getValue())
            .build();
        result.add(detail);
      }
      return result;
    }

    /** Counts the total number of misses registered so far regardless of their reason. */
    public int countMisses() {
      int total = 0;
      for (Map.Entry<MissReason, Integer> entry : details.entrySet()) {
        total += entry.getValue();
      }
      return total;
    }
  }

  /**
   * An {@link ArtifactResolver} all of whose operations throw an exception.
   *
   * <p>This is to be used as a base class by other test programs that need to implement only a
   * few of the hooks required by the scenario under test.
   */
  public static class FakeArtifactResolverBase implements ArtifactResolver {
    @Override
    public Artifact getSourceArtifact(PathFragment execPath, Root root, ArtifactOwner owner) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Artifact getSourceArtifact(PathFragment execPath, Root root) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Artifact resolveSourceArtifact(
        PathFragment execPath, RepositoryName repositoryName) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Map<PathFragment, Artifact> resolveSourceArtifacts(
        Iterable<PathFragment> execPaths, PackageRootResolver resolver) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Path getPathFromSourceExecPath(PathFragment execPath) {
      throw new UnsupportedOperationException();
    }
  }

  /**
   * A {@link MetadataHandler} all of whose operations throw an exception.
   *
   * <p>This is to be used as a base class by other test programs that need to implement only a
   * few of the hooks required by the scenario under test.
   */
  public static class FakeMetadataHandlerBase implements MetadataHandler {
    @Override
    public FileArtifactValue getMetadata(ActionInput input) throws IOException {
      throw new UnsupportedOperationException();
    }

    @Override
    public ActionInput getInput(String execPath) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void setDigestForVirtualArtifact(Artifact artifact, Md5Digest md5Digest) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void addExpandedTreeOutput(TreeFileArtifact output) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Iterable<TreeFileArtifact> getExpandedOutputs(Artifact artifact) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void injectDigest(ActionInput output, FileStatus statNoFollow, byte[] digest) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void injectRemoteFile(Artifact output, byte[] digest, long size, int locationIndex) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void markOmitted(ActionInput output) {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean artifactOmitted(Artifact artifact) {
      return false;
    }

    @Override
    public void discardOutputMetadata() {
      throw new UnsupportedOperationException();
    }
  }
}
