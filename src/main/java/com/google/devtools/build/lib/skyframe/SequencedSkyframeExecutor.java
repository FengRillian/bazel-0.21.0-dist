// Copyright 2014 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.skyframe;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.collect.Collections2;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Range;
import com.google.common.collect.Sets;
import com.google.devtools.build.lib.actions.ActionKeyContext;
import com.google.devtools.build.lib.actions.ArtifactRoot;
import com.google.devtools.build.lib.actions.CommandLineExpansionException;
import com.google.devtools.build.lib.actions.FileStateValue;
import com.google.devtools.build.lib.actions.FileValue;
import com.google.devtools.build.lib.analysis.AnalysisProtos.ActionGraphContainer;
import com.google.devtools.build.lib.analysis.BlazeDirectories;
import com.google.devtools.build.lib.analysis.ConfiguredTarget;
import com.google.devtools.build.lib.analysis.WorkspaceStatusAction.Factory;
import com.google.devtools.build.lib.analysis.buildinfo.BuildInfoFactory;
import com.google.devtools.build.lib.analysis.config.BuildOptions;
import com.google.devtools.build.lib.analysis.configuredtargets.RuleConfiguredTarget;
import com.google.devtools.build.lib.cmdline.PackageIdentifier;
import com.google.devtools.build.lib.concurrent.Uninterruptibles;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.EventHandler;
import com.google.devtools.build.lib.events.ExtendedEventHandler;
import com.google.devtools.build.lib.packages.AspectClass;
import com.google.devtools.build.lib.packages.BuildFileName;
import com.google.devtools.build.lib.packages.NoSuchPackageException;
import com.google.devtools.build.lib.packages.NoSuchTargetException;
import com.google.devtools.build.lib.packages.Package;
import com.google.devtools.build.lib.packages.PackageFactory;
import com.google.devtools.build.lib.packages.Rule;
import com.google.devtools.build.lib.packages.RuleClass;
import com.google.devtools.build.lib.packages.SkylarkSemanticsOptions;
import com.google.devtools.build.lib.pkgcache.PackageCacheOptions;
import com.google.devtools.build.lib.pkgcache.PathPackageLocator;
import com.google.devtools.build.lib.profiler.AutoProfiler;
import com.google.devtools.build.lib.profiler.Profiler;
import com.google.devtools.build.lib.profiler.SilentCloseable;
import com.google.devtools.build.lib.skyframe.AspectValue.AspectKey;
import com.google.devtools.build.lib.skyframe.DirtinessCheckerUtils.BasicFilesystemDirtinessChecker;
import com.google.devtools.build.lib.skyframe.DirtinessCheckerUtils.ExternalDirtinessChecker;
import com.google.devtools.build.lib.skyframe.DirtinessCheckerUtils.MissingDiffDirtinessChecker;
import com.google.devtools.build.lib.skyframe.DirtinessCheckerUtils.UnionDirtinessChecker;
import com.google.devtools.build.lib.skyframe.ExternalFilesHelper.ExternalFileAction;
import com.google.devtools.build.lib.skyframe.ExternalFilesHelper.ExternalFilesKnowledge;
import com.google.devtools.build.lib.skyframe.ExternalFilesHelper.FileType;
import com.google.devtools.build.lib.skyframe.PackageFunction.ActionOnIOExceptionReadingBuildFile;
import com.google.devtools.build.lib.skyframe.PackageLookupFunction.CrossRepositoryLabelViolationStrategy;
import com.google.devtools.build.lib.skyframe.actiongraph.ActionGraphDump;
import com.google.devtools.build.lib.util.AbruptExitException;
import com.google.devtools.build.lib.util.Pair;
import com.google.devtools.build.lib.util.ResourceUsage;
import com.google.devtools.build.lib.util.io.TimestampGranularityMonitor;
import com.google.devtools.build.lib.vfs.BatchStat;
import com.google.devtools.build.lib.vfs.FileSystem;
import com.google.devtools.build.lib.vfs.ModifiedFileSet;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.Root;
import com.google.devtools.build.skyframe.BuildDriver;
import com.google.devtools.build.skyframe.Differencer;
import com.google.devtools.build.skyframe.EvaluationContext;
import com.google.devtools.build.skyframe.GraphInconsistencyReceiver;
import com.google.devtools.build.skyframe.InMemoryMemoizingEvaluator;
import com.google.devtools.build.skyframe.Injectable;
import com.google.devtools.build.skyframe.MemoizingEvaluator.EvaluatorSupplier;
import com.google.devtools.build.skyframe.NodeEntry;
import com.google.devtools.build.skyframe.RecordingDifferencer;
import com.google.devtools.build.skyframe.SequencedRecordingDifferencer;
import com.google.devtools.build.skyframe.SequentialBuildDriver;
import com.google.devtools.build.skyframe.SkyFunction;
import com.google.devtools.build.skyframe.SkyFunctionName;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;
import com.google.devtools.common.options.OptionsProvider;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.logging.Logger;
import javax.annotation.Nullable;

/**
 * A SkyframeExecutor that implicitly assumes that builds can be done incrementally from the most
 * recent build. In other words, builds are "sequenced".
 */
public final class SequencedSkyframeExecutor extends SkyframeExecutor {

  private static final Logger logger = Logger.getLogger(SequencedSkyframeExecutor.class.getName());

  private boolean lastAnalysisDiscarded = false;

  /**
   * If false, the graph will not store state useful for incremental builds, saving memory but
   * leaving the graph un-reusable. Subsequent builds will therefore not be incremental.
   *
   * <p>Avoids storing edges entirely and dereferences each action after execution.
   */
  private boolean trackIncrementalState = true;

  private boolean evaluatorNeedsReset = false;

  // This is intentionally not kept in sync with the evaluator: we may reset the evaluator without
  // ever losing injected/invalidated data here. This is safe because the worst that will happen is
  // that on the next build we try to inject/invalidate some nodes that aren't needed for the build.
  private final RecordingDifferencer recordingDiffer = new SequencedRecordingDifferencer();
  private final DiffAwarenessManager diffAwarenessManager;
  private final Iterable<SkyValueDirtinessChecker> customDirtinessCheckers;
  private Set<String> previousClientEnvironment = ImmutableSet.of();

  private SequencedSkyframeExecutor(
      EvaluatorSupplier evaluatorSupplier,
      PackageFactory pkgFactory,
      FileSystem fileSystem,
      BlazeDirectories directories,
      ActionKeyContext actionKeyContext,
      Factory workspaceStatusActionFactory,
      ImmutableList<BuildInfoFactory> buildInfoFactories,
      Iterable<? extends DiffAwareness.Factory> diffAwarenessFactories,
      ImmutableMap<SkyFunctionName, SkyFunction> extraSkyFunctions,
      Iterable<SkyValueDirtinessChecker> customDirtinessCheckers,
      ImmutableSet<PathFragment> hardcodedBlacklistedPackagePrefixes,
      PathFragment additionalBlacklistedPackagePrefixesFile,
      CrossRepositoryLabelViolationStrategy crossRepositoryLabelViolationStrategy,
      List<BuildFileName> buildFilesByPriority,
      ActionOnIOExceptionReadingBuildFile actionOnIOExceptionReadingBuildFile,
      BuildOptions defaultBuildOptions,
      MutableArtifactFactorySupplier mutableArtifactFactorySupplier) {
    super(
        evaluatorSupplier,
        pkgFactory,
        fileSystem,
        directories,
        actionKeyContext,
        workspaceStatusActionFactory,
        buildInfoFactories,
        extraSkyFunctions,
        ExternalFileAction.DEPEND_ON_EXTERNAL_PKG_FOR_EXTERNAL_REPO_PATHS,
        hardcodedBlacklistedPackagePrefixes,
        additionalBlacklistedPackagePrefixesFile,
        crossRepositoryLabelViolationStrategy,
        buildFilesByPriority,
        actionOnIOExceptionReadingBuildFile,
        /*shouldUnblockCpuWorkWhenFetchingDeps=*/ false,
        GraphInconsistencyReceiver.THROWING,
        defaultBuildOptions,
        new PackageProgressReceiver(),
        mutableArtifactFactorySupplier,
        new ConfiguredTargetProgressReceiver(),
        /*nonexistentFileReceiver=*/ null);
    this.diffAwarenessManager = new DiffAwarenessManager(diffAwarenessFactories);
    this.customDirtinessCheckers = customDirtinessCheckers;
  }

  public static SequencedSkyframeExecutor create(
      PackageFactory pkgFactory,
      FileSystem fileSystem,
      BlazeDirectories directories,
      ActionKeyContext actionKeyContext,
      Factory workspaceStatusActionFactory,
      ImmutableList<BuildInfoFactory> buildInfoFactories,
      Iterable<? extends DiffAwareness.Factory> diffAwarenessFactories,
      ImmutableMap<SkyFunctionName, SkyFunction> extraSkyFunctions,
      Iterable<SkyValueDirtinessChecker> customDirtinessCheckers,
      ImmutableSet<PathFragment> hardcodedBlacklistedPackagePrefixes,
      PathFragment additionalBlacklistedPackagePrefixesFile,
      CrossRepositoryLabelViolationStrategy crossRepositoryLabelViolationStrategy,
      List<BuildFileName> buildFilesByPriority,
      ActionOnIOExceptionReadingBuildFile actionOnIOExceptionReadingBuildFile,
      BuildOptions defaultBuildOptions) {
    return create(
        InMemoryMemoizingEvaluator.SUPPLIER,
        pkgFactory,
        fileSystem,
        directories,
        actionKeyContext,
        workspaceStatusActionFactory,
        buildInfoFactories,
        diffAwarenessFactories,
        extraSkyFunctions,
        customDirtinessCheckers,
        hardcodedBlacklistedPackagePrefixes,
        additionalBlacklistedPackagePrefixesFile,
        crossRepositoryLabelViolationStrategy,
        buildFilesByPriority,
        actionOnIOExceptionReadingBuildFile,
        defaultBuildOptions,
        new MutableArtifactFactorySupplier());
  }

  public static SequencedSkyframeExecutor create(
      EvaluatorSupplier evaluatorSupplier,
      PackageFactory pkgFactory,
      FileSystem fileSystem,
      BlazeDirectories directories,
      ActionKeyContext actionKeyContext,
      Factory workspaceStatusActionFactory,
      ImmutableList<BuildInfoFactory> buildInfoFactories,
      Iterable<? extends DiffAwareness.Factory> diffAwarenessFactories,
      ImmutableMap<SkyFunctionName, SkyFunction> extraSkyFunctions,
      Iterable<SkyValueDirtinessChecker> customDirtinessCheckers,
      ImmutableSet<PathFragment> hardcodedBlacklistedPackagePrefixes,
      PathFragment additionalBlacklistedPackagePrefixesFile,
      CrossRepositoryLabelViolationStrategy crossRepositoryLabelViolationStrategy,
      List<BuildFileName> buildFilesByPriority,
      ActionOnIOExceptionReadingBuildFile actionOnIOExceptionReadingBuildFile,
      BuildOptions defaultBuildOptions,
      MutableArtifactFactorySupplier mutableArtifactFactorySupplier) {
    SequencedSkyframeExecutor skyframeExecutor =
        new SequencedSkyframeExecutor(
            evaluatorSupplier,
            pkgFactory,
            fileSystem,
            directories,
            actionKeyContext,
            workspaceStatusActionFactory,
            buildInfoFactories,
            diffAwarenessFactories,
            extraSkyFunctions,
            customDirtinessCheckers,
            hardcodedBlacklistedPackagePrefixes,
            additionalBlacklistedPackagePrefixesFile,
            crossRepositoryLabelViolationStrategy,
            buildFilesByPriority,
            actionOnIOExceptionReadingBuildFile,
            defaultBuildOptions,
            mutableArtifactFactorySupplier);
    skyframeExecutor.init();
    return skyframeExecutor;
  }

  @Override
  protected BuildDriver getBuildDriver() {
    return new SequentialBuildDriver(memoizingEvaluator);
  }

  @Override
  public void resetEvaluator() {
    super.resetEvaluator();
    diffAwarenessManager.reset();
  }

  @Override
  protected Differencer evaluatorDiffer() {
    return recordingDiffer;
  }

  @Override
  protected Injectable injectable() {
    return recordingDiffer;
  }

  @VisibleForTesting
  public RecordingDifferencer getDifferencerForTesting() {
    return recordingDiffer;
  }

  @Override
  public void sync(
      ExtendedEventHandler eventHandler,
      PackageCacheOptions packageCacheOptions,
      PathPackageLocator packageLocator,
      SkylarkSemanticsOptions skylarkSemanticsOptions,
      String defaultsPackageContents,
      UUID commandId,
      Map<String, String> clientEnv,
      TimestampGranularityMonitor tsgm,
      OptionsProvider options)
      throws InterruptedException, AbruptExitException {
    if (evaluatorNeedsReset) {
      // Recreate MemoizingEvaluator so that graph is recreated with correct edge-clearing status,
      // or if the graph doesn't have edges, so that a fresh graph can be used.
      resetEvaluator();
      evaluatorNeedsReset = false;
    }
    super.sync(
        eventHandler,
        packageCacheOptions,
        packageLocator,
        skylarkSemanticsOptions,
        defaultsPackageContents,
        commandId,
        clientEnv,
        tsgm,
        options);
    try (SilentCloseable c = Profiler.instance().profile("handleDiffs")) {
      handleDiffs(eventHandler, packageCacheOptions.checkOutputFiles, options);
    }
  }

  /**
   * The value types whose builders have direct access to the package locator, rather than accessing
   * it via an explicit Skyframe dependency. They need to be invalidated if the package locator
   * changes.
   */
  private static final ImmutableSet<SkyFunctionName> PACKAGE_LOCATOR_DEPENDENT_VALUES =
      ImmutableSet.of(
          SkyFunctions.AST_FILE_LOOKUP,
          FileStateValue.FILE_STATE,
          FileValue.FILE,
          SkyFunctions.DIRECTORY_LISTING_STATE,
          SkyFunctions.TARGET_PATTERN,
          SkyFunctions.PREPARE_DEPS_OF_PATTERN,
          SkyFunctions.WORKSPACE_FILE,
          SkyFunctions.EXTERNAL_PACKAGE,
          SkyFunctions.TARGET_PATTERN,
          SkyFunctions.TARGET_PATTERN_PHASE);

  @Override
  protected ImmutableMap<Root, ArtifactRoot> createSourceArtifactRootMapOnNewPkgLocator(
      PathPackageLocator oldLocator, PathPackageLocator pkgLocator) {
    invalidate(SkyFunctionName.functionIsIn(PACKAGE_LOCATOR_DEPENDENT_VALUES));
    return super.createSourceArtifactRootMapOnNewPkgLocator(oldLocator, pkgLocator);
  }

  @Override
  protected void invalidate(Predicate<SkyKey> pred) {
    recordingDiffer.invalidate(Iterables.filter(memoizingEvaluator.getValues().keySet(), pred));
  }

  private void invalidateDeletedPackages(Iterable<PackageIdentifier> deletedPackages) {
    ArrayList<SkyKey> packagesToInvalidate = Lists.newArrayList();
    for (PackageIdentifier deletedPackage : deletedPackages) {
      packagesToInvalidate.add(PackageLookupValue.key(deletedPackage));
    }
    recordingDiffer.invalidate(packagesToInvalidate);
  }

  /**
   * Sets the packages that should be treated as deleted and ignored.
   */
  @Override
  @VisibleForTesting  // productionVisibility = Visibility.PRIVATE
  public void setDeletedPackages(Iterable<PackageIdentifier> pkgs) {
    // Invalidate the old deletedPackages as they may exist now.
    invalidateDeletedPackages(deletedPackages.get());
    deletedPackages.set(ImmutableSet.copyOf(pkgs));
    // Invalidate the new deletedPackages as we need to pretend that they don't exist now.
    invalidateDeletedPackages(deletedPackages.get());
  }

  /** Uses diff awareness on all the package paths to invalidate changed files. */
  @VisibleForTesting
  public void handleDiffs(ExtendedEventHandler eventHandler) throws InterruptedException {
    handleDiffs(eventHandler, /*checkOutputFiles=*/false, OptionsProvider.EMPTY);
  }

  private void handleDiffs(
      ExtendedEventHandler eventHandler, boolean checkOutputFiles, OptionsProvider options)
      throws InterruptedException {
    if (lastAnalysisDiscarded) {
      // Values were cleared last build, but they couldn't be deleted because they were needed for
      // the execution phase. We can delete them now.
      dropConfiguredTargetsNow(eventHandler);
      lastAnalysisDiscarded = false;
    }
    TimestampGranularityMonitor tsgm = this.tsgm.get();
    modifiedFiles = 0;
    Map<Root, DiffAwarenessManager.ProcessableModifiedFileSet> modifiedFilesByPathEntry =
        Maps.newHashMap();
    Set<Pair<Root, DiffAwarenessManager.ProcessableModifiedFileSet>>
        pathEntriesWithoutDiffInformation = Sets.newHashSet();
    for (Root pathEntry : pkgLocator.get().getPathEntries()) {
      DiffAwarenessManager.ProcessableModifiedFileSet modifiedFileSet =
          diffAwarenessManager.getDiff(eventHandler, pathEntry, options);
      if (modifiedFileSet.getModifiedFileSet().treatEverythingAsModified()) {
        pathEntriesWithoutDiffInformation.add(Pair.of(pathEntry, modifiedFileSet));
      } else {
        modifiedFilesByPathEntry.put(pathEntry, modifiedFileSet);
      }
    }
    handleDiffsWithCompleteDiffInformation(tsgm, modifiedFilesByPathEntry);
    handleDiffsWithMissingDiffInformation(eventHandler, tsgm, pathEntriesWithoutDiffInformation,
        checkOutputFiles);
    handleClientEnvironmentChanges();
  }

  /** Invalidates entries in the client environment. */
  private void handleClientEnvironmentChanges() {
    // Remove deleted client environmental variables.
    Iterable<SkyKey> deletedKeys =
        Sets.difference(previousClientEnvironment, clientEnv.get().keySet())
            .stream()
            .map(ClientEnvironmentFunction::key)
            .collect(ImmutableList.toImmutableList());
    recordingDiffer.invalidate(deletedKeys);
    previousClientEnvironment = clientEnv.get().keySet();
    // Inject current client environmental values. We can inject unconditionally without fearing
    // over-invalidation; skyframe will not invalidate an injected key if the key's new value is the
    // same as the old value.
    ImmutableMap.Builder<SkyKey, SkyValue> newValuesBuilder = ImmutableMap.builder();
    for (Map.Entry<String, String> entry : clientEnv.get().entrySet()) {
      newValuesBuilder.put(
          ClientEnvironmentFunction.key(entry.getKey()),
          new ClientEnvironmentValue(entry.getValue()));
    }
    recordingDiffer.inject(newValuesBuilder.build());
  }

  /**
   * Invalidates files under path entries whose corresponding {@link DiffAwareness} gave an exact
   * diff. Removes entries from the given map as they are processed. All of the files need to be
   * invalidated, so the map should be empty upon completion of this function.
   */
  private void handleDiffsWithCompleteDiffInformation(
      TimestampGranularityMonitor tsgm,
      Map<Root, DiffAwarenessManager.ProcessableModifiedFileSet> modifiedFilesByPathEntry)
      throws InterruptedException {
    for (Root pathEntry : ImmutableSet.copyOf(modifiedFilesByPathEntry.keySet())) {
      DiffAwarenessManager.ProcessableModifiedFileSet processableModifiedFileSet =
          modifiedFilesByPathEntry.get(pathEntry);
      ModifiedFileSet modifiedFileSet = processableModifiedFileSet.getModifiedFileSet();
      Preconditions.checkState(!modifiedFileSet.treatEverythingAsModified(), pathEntry);
      handleChangedFiles(ImmutableList.of(pathEntry),
          getDiff(tsgm, modifiedFileSet.modifiedSourceFiles(), pathEntry));
      processableModifiedFileSet.markProcessed();
    }
  }

  /**
   * Finds and invalidates changed files under path entries whose corresponding {@link
   * DiffAwareness} said all files may have been modified.
   */
  private void handleDiffsWithMissingDiffInformation(
      ExtendedEventHandler eventHandler,
      TimestampGranularityMonitor tsgm,
      Set<Pair<Root, DiffAwarenessManager.ProcessableModifiedFileSet>>
          pathEntriesWithoutDiffInformation,
      boolean checkOutputFiles)
      throws InterruptedException {
    ExternalFilesKnowledge externalFilesKnowledge =
        externalFilesHelper.getExternalFilesKnowledge();
    if (pathEntriesWithoutDiffInformation.isEmpty()
        && Iterables.isEmpty(customDirtinessCheckers)
        && ((!externalFilesKnowledge.anyOutputFilesSeen || !checkOutputFiles)
            && !externalFilesKnowledge.anyNonOutputExternalFilesSeen)) {
      // Avoid a full graph scan if we have good diff information for all path entries, there are
      // no custom checkers that need to look at the whole graph, and no external (not under any
      // path) files need to be checked.
      return;
    }
    // Before running the FilesystemValueChecker, ensure that all values marked for invalidation
    // have actually been invalidated (recall that invalidation happens at the beginning of the
    // next evaluate() call), because checking those is a waste of time.
    EvaluationContext evaluationContext =
        EvaluationContext.newBuilder()
            .setKeepGoing(false)
            .setNumThreads(DEFAULT_THREAD_COUNT)
            .setEventHander(eventHandler)
            .build();
    buildDriver.evaluate(ImmutableList.<SkyKey>of(), evaluationContext);

    FilesystemValueChecker fsvc = new FilesystemValueChecker(tsgm, null);
    // We need to manually check for changes to known files. This entails finding all dirty file
    // system values under package roots for which we don't have diff information. If at least
    // one path entry doesn't have diff information, then we're going to have to iterate over
    // the skyframe values at least once no matter what.
    Set<Root> diffPackageRootsUnderWhichToCheck = new HashSet<>();
    for (Pair<Root, DiffAwarenessManager.ProcessableModifiedFileSet> pair :
        pathEntriesWithoutDiffInformation) {
      diffPackageRootsUnderWhichToCheck.add(pair.getFirst());
    }

    // We freshly compute knowledge of the presence of external files in the skyframe graph. We use
    // a fresh ExternalFilesHelper instance and only set the real instance's knowledge *after* we
    // are done with the graph scan, lest an interrupt during the graph scan causes us to
    // incorrectly think there are no longer any external files.
    ExternalFilesHelper tmpExternalFilesHelper =
        externalFilesHelper.cloneWithFreshExternalFilesKnowledge();
    // See the comment for FileType.OUTPUT for why we need to consider output files here.
    EnumSet<FileType> fileTypesToCheck = checkOutputFiles
        ? EnumSet.of(FileType.EXTERNAL, FileType.EXTERNAL_REPO, FileType.OUTPUT)
        : EnumSet.of(FileType.EXTERNAL, FileType.EXTERNAL_REPO);
    logger.info(
        "About to scan skyframe graph checking for filesystem nodes of types "
            + Iterables.toString(fileTypesToCheck));
    Differencer.Diff diff;
    try (SilentCloseable c = Profiler.instance().profile("fsvc.getDirtyKeys")) {
      diff =
          fsvc.getDirtyKeys(
              memoizingEvaluator.getValues(),
              new UnionDirtinessChecker(
                  Iterables.concat(
                      customDirtinessCheckers,
                      ImmutableList.<SkyValueDirtinessChecker>of(
                          new ExternalDirtinessChecker(tmpExternalFilesHelper, fileTypesToCheck),
                          new MissingDiffDirtinessChecker(diffPackageRootsUnderWhichToCheck)))));
    }
    handleChangedFiles(diffPackageRootsUnderWhichToCheck, diff);

    for (Pair<Root, DiffAwarenessManager.ProcessableModifiedFileSet> pair :
        pathEntriesWithoutDiffInformation) {
      pair.getSecond().markProcessed();
    }
    // We use the knowledge gained during the graph scan that just completed. Otherwise, naively,
    // once an external file gets into the Skyframe graph, we'll overly-conservatively always think
    // the graph needs to be scanned.
    externalFilesHelper.setExternalFilesKnowledge(
        tmpExternalFilesHelper.getExternalFilesKnowledge());
  }

  private void handleChangedFiles(
      Collection<Root> diffPackageRootsUnderWhichToCheck, Differencer.Diff diff) {
    Collection<SkyKey> changedKeysWithoutNewValues = diff.changedKeysWithoutNewValues();
    Map<SkyKey, SkyValue> changedKeysWithNewValues = diff.changedKeysWithNewValues();

    logDiffInfo(diffPackageRootsUnderWhichToCheck, changedKeysWithoutNewValues,
        changedKeysWithNewValues);

    recordingDiffer.invalidate(changedKeysWithoutNewValues);
    recordingDiffer.inject(changedKeysWithNewValues);
    modifiedFiles += getNumberOfModifiedFiles(changedKeysWithoutNewValues);
    modifiedFiles += getNumberOfModifiedFiles(changedKeysWithNewValues.keySet());
    incrementalBuildMonitor.accrue(changedKeysWithoutNewValues);
    incrementalBuildMonitor.accrue(changedKeysWithNewValues.keySet());
  }

  private static final int MAX_NUMBER_OF_CHANGED_KEYS_TO_LOG = 10;

  private static void logDiffInfo(
      Iterable<Root> pathEntries,
      Collection<SkyKey> changedWithoutNewValue,
      Map<SkyKey, ? extends SkyValue> changedWithNewValue) {
    int numModified = changedWithNewValue.size() + changedWithoutNewValue.size();
    StringBuilder result = new StringBuilder("DiffAwareness found ")
        .append(numModified)
        .append(" modified source files and directory listings");
    if (!Iterables.isEmpty(pathEntries)) {
      result.append(" for ");
      result.append(Joiner.on(", ").join(pathEntries));
    }

    if (numModified > 0) {
      Iterable<SkyKey> allModifiedKeys = Iterables.concat(changedWithoutNewValue,
          changedWithNewValue.keySet());
      Iterable<SkyKey> trimmed =
          Iterables.limit(allModifiedKeys, MAX_NUMBER_OF_CHANGED_KEYS_TO_LOG);

      result.append(": ")
          .append(Joiner.on(", ").join(trimmed));

      if (numModified > MAX_NUMBER_OF_CHANGED_KEYS_TO_LOG) {
        result.append(", ...");
      }
    }

    logger.info(result.toString());
  }

  private static int getNumberOfModifiedFiles(Iterable<SkyKey> modifiedValues) {
    // We are searching only for changed files, DirectoryListingValues don't depend on
    // child values, that's why they are invalidated separately
    return Iterables.size(
        Iterables.filter(modifiedValues, SkyFunctionName.functionIs(FileStateValue.FILE_STATE)));
  }

  /**
   * {@inheritDoc}
   *
   * <p>Necessary conditions to not store graph edges are either
   *
   * <ol>
   *   <li>batch (since incremental builds are not possible) and discard_analysis_cache (since
   *       otherwise user isn't concerned about saving memory this way).
   *   <li>track_incremental_state set to false.
   * </ol>
   */
  @Override
  public void decideKeepIncrementalState(
      boolean batch,
      boolean keepStateAfterBuild,
      boolean shouldTrackIncrementalState,
      boolean discardAnalysisCache,
      EventHandler eventHandler) {
    Preconditions.checkState(!active);
    boolean oldValueOfTrackIncrementalState = trackIncrementalState;

    // First check if the incrementality state should be kept around during the build.
    boolean explicitlyRequestedNoIncrementalData = !shouldTrackIncrementalState;
    boolean implicitlyRequestedNoIncrementalData = (batch && discardAnalysisCache);
    trackIncrementalState =
        !explicitlyRequestedNoIncrementalData && !implicitlyRequestedNoIncrementalData;
    if (explicitlyRequestedNoIncrementalData != implicitlyRequestedNoIncrementalData) {
      if (!explicitlyRequestedNoIncrementalData) {
        eventHandler.handle(
            Event.warn(
                "--batch and --discard_analysis_cache specified, but --notrack_incremental_state "
                    + "not specified: incrementality data is implicitly discarded, but you may need"
                    + " to specify --notrack_incremental_state in the future if you want to "
                    + "maximize memory savings."));
      }
      if (!batch && keepStateAfterBuild) {
        eventHandler.handle(
            Event.warn(
                "--notrack_incremental_state was specified, but without "
                    + "--nokeep_state_after_build. Inmemory state from this build will not be "
                    + "reusable, but it will not get fully wiped until the beginning of the next "
                    + "build. Use --nokeep_state_after_build to clean up eagerly."));
      }
    }

    // Now check if it is necessary to wipe the previous state. We do this if either the previous
    // or current incrementalStateRetentionStrategy requires the build to have been isolated.
    if (oldValueOfTrackIncrementalState != trackIncrementalState) {
      logger.info("Set incremental state to " + trackIncrementalState);
      evaluatorNeedsReset = true;
    } else if (!trackIncrementalState) {
      evaluatorNeedsReset = true;
    }
  }

  @Override
  public boolean tracksStateForIncrementality() {
    return trackIncrementalState;
  }

  @Override
  public void invalidateFilesUnderPathForTesting(
      ExtendedEventHandler eventHandler, ModifiedFileSet modifiedFileSet, Root pathEntry)
      throws InterruptedException {
    if (lastAnalysisDiscarded) {
      // Values were cleared last build, but they couldn't be deleted because they were needed for
      // the execution phase. We can delete them now.
      dropConfiguredTargetsNow(eventHandler);
      lastAnalysisDiscarded = false;
    }
    TimestampGranularityMonitor tsgm = this.tsgm.get();
    Differencer.Diff diff;
    if (modifiedFileSet.treatEverythingAsModified()) {
      diff = new FilesystemValueChecker(tsgm, null).getDirtyKeys(memoizingEvaluator.getValues(),
          new BasicFilesystemDirtinessChecker());
    } else {
      diff = getDiff(tsgm, modifiedFileSet.modifiedSourceFiles(), pathEntry);
    }
    syscalls.set(getPerBuildSyscallCache(/*concurrencyLevel=*/ 42));
    recordingDiffer.invalidate(diff.changedKeysWithoutNewValues());
    recordingDiffer.inject(diff.changedKeysWithNewValues());
    // Blaze invalidates transient errors on every build.
    invalidateTransientErrors();
  }

  @Override
  public void invalidateTransientErrors() {
    checkActive();
    recordingDiffer.invalidateTransientErrors();
  }

  @Override
  public void detectModifiedOutputFiles(
      ModifiedFileSet modifiedOutputFiles, @Nullable Range<Long> lastExecutionTimeRange)
      throws AbruptExitException, InterruptedException {

    // Detect external modifications in the output tree.
    FilesystemValueChecker fsvc =
        new FilesystemValueChecker(Preconditions.checkNotNull(tsgm.get()), lastExecutionTimeRange);
    BatchStat batchStatter = outputService == null ? null : outputService.getBatchStatter();
    recordingDiffer.invalidate(
        fsvc.getDirtyActionValues(
            memoizingEvaluator.getValues(), batchStatter, modifiedOutputFiles));
    modifiedFiles += fsvc.getNumberOfModifiedOutputFiles();
    outputDirtyFiles += fsvc.getNumberOfModifiedOutputFiles();
    modifiedFilesDuringPreviousBuild += fsvc.getNumberOfModifiedOutputFilesDuringPreviousBuild();
    informAboutNumberOfModifiedFiles();
  }

  private static ImmutableSet<SkyFunctionName> LOADING_TYPES =
      ImmutableSet.of(
          SkyFunctions.PACKAGE,
          SkyFunctions.SKYLARK_IMPORTS_LOOKUP,
          SkyFunctions.AST_FILE_LOOKUP,
          SkyFunctions.GLOB);

  /**
   * Save memory by removing references to configured targets and aspects in Skyframe.
   *
   * <p>These nodes must be recreated on subsequent builds. We do not clear the top-level target
   * nodes, since their configured targets are needed for the target completion middleman values.
   *
   * <p>The nodes are not deleted during this method call, because they are needed for the execution
   * phase. Instead, their analysis-time data is cleared while preserving the generating action info
   * needed for execution. The next build will delete the nodes (and recreate them if necessary).
   *
   * <p>If {@link #tracksStateForIncrementality} is false, then also delete loading-phase nodes (as
   * determined by {@link #LOADING_TYPES}) from the graph, since there will be no future builds to
   * use them for.
   */
  private void discardAnalysisCache(
      Collection<ConfiguredTarget> topLevelTargets, Collection<AspectValue> topLevelAspects) {
    topLevelTargets = ImmutableSet.copyOf(topLevelTargets);
    topLevelAspects = ImmutableSet.copyOf(topLevelAspects);
    // This is to prevent throwing away Packages we may need during execution.
    ImmutableSet.Builder<PackageIdentifier> packageSetBuilder = ImmutableSet.builder();
    packageSetBuilder.addAll(
        Collections2.transform(
            topLevelTargets, (target) -> target.getLabel().getPackageIdentifier()));
    packageSetBuilder.addAll(
        Collections2.transform(
            topLevelAspects, (aspect) -> aspect.getLabel().getPackageIdentifier()));
    ImmutableSet<PackageIdentifier> topLevelPackages = packageSetBuilder.build();
    try (AutoProfiler p = AutoProfiler.logged("discarding analysis cache", logger)) {
      lastAnalysisDiscarded = true;
      Iterator<? extends Map.Entry<SkyKey, ? extends NodeEntry>> it =
          memoizingEvaluator.getGraphMap().entrySet().iterator();
      while (it.hasNext()) {
        Map.Entry<SkyKey, ? extends NodeEntry> keyAndEntry = it.next();
        NodeEntry entry = keyAndEntry.getValue();
        if (entry == null || !entry.isDone()) {
          continue;
        }
        SkyKey key = keyAndEntry.getKey();
        SkyFunctionName functionName = key.functionName();
        // Keep packages for top-level targets and aspects in memory to get the target from later.
        if (functionName.equals(SkyFunctions.PACKAGE)
            && topLevelPackages.contains((key.argument()))) {
          continue;
        }
        if (!tracksStateForIncrementality() && LOADING_TYPES.contains(functionName)) {
          it.remove();
          continue;
        }
        if (functionName.equals(SkyFunctions.CONFIGURED_TARGET)) {
          ConfiguredTargetValue ctValue;
          try {
            ctValue = (ConfiguredTargetValue) entry.getValue();
          } catch (InterruptedException e) {
            throw new IllegalStateException("No interruption in sequenced evaluation", e);
          }
          // ctValue may be null if target was not successfully analyzed.
          if (ctValue != null) {
            ctValue.clear(!topLevelTargets.contains(ctValue.getConfiguredTarget()));
          }
        } else if (functionName.equals(SkyFunctions.ASPECT)) {
          AspectValue aspectValue;
          try {
            aspectValue = (AspectValue) entry.getValue();
          } catch (InterruptedException e) {
            throw new IllegalStateException("No interruption in sequenced evaluation", e);
          }
          // value may be null if target was not successfully analyzed.
          if (aspectValue != null) {
            aspectValue.clear(!topLevelAspects.contains(aspectValue));
          }
        }
      }
    }
  }

  @Override
  public void clearAnalysisCache(
      Collection<ConfiguredTarget> topLevelTargets, Collection<AspectValue> topLevelAspects) {
    discardAnalysisCache(topLevelTargets, topLevelAspects);
  }

  @Override
  public List<RuleStat> getRuleStats(ExtendedEventHandler eventHandler) {
    Map<String, RuleStat> ruleStats = new HashMap<>();
    for (Map.Entry<SkyKey, SkyValue> skyKeyAndValue :
        memoizingEvaluator.getDoneValues().entrySet()) {
      SkyValue value = skyKeyAndValue.getValue();
      SkyKey key = skyKeyAndValue.getKey();
      SkyFunctionName functionName = key.functionName();
      if (functionName.equals(SkyFunctions.CONFIGURED_TARGET)) {
        ConfiguredTargetValue ctValue = (ConfiguredTargetValue) value;
          ConfiguredTarget configuredTarget = ctValue.getConfiguredTarget();
          if (configuredTarget instanceof RuleConfiguredTarget) {

            Rule rule;
            try {
              rule =
                  (Rule) getPackageManager().getTarget(eventHandler, configuredTarget.getLabel());
            } catch (NoSuchPackageException | NoSuchTargetException | InterruptedException e) {
              throw new IllegalStateException(
                  "Failed to get Rule target from package when calculating stats.", e);
            }
            RuleConfiguredTarget ruleConfiguredTarget = (RuleConfiguredTarget) configuredTarget;
            RuleClass ruleClass = rule.getRuleClassObject();
            RuleStat ruleStat =
                ruleStats.computeIfAbsent(
                    ruleClass.getKey(), k -> new RuleStat(k, ruleClass.getName(), true));
            ruleStat.addRule(ctValue.getNumActions());
          }
      } else if (functionName.equals(SkyFunctions.ASPECT)) {
        AspectValue aspectValue = (AspectValue) value;
          AspectClass aspectClass = aspectValue.getAspect().getAspectClass();
          RuleStat ruleStat =
              ruleStats.computeIfAbsent(
                  aspectClass.getKey(), k -> new RuleStat(k, aspectClass.getName(), false));
          ruleStat.addRule(aspectValue.getNumActions());
      }
    }
    return new ArrayList<>(ruleStats.values());
  }

  @Override
  public ActionGraphContainer getActionGraphContainer(
      List<String> actionGraphTargets, boolean includeActionCmdLine)
      throws CommandLineExpansionException {
    ActionGraphDump actionGraphDump = new ActionGraphDump(actionGraphTargets, includeActionCmdLine);
    for (Map.Entry<SkyKey, SkyValue> skyKeyAndValue :
        memoizingEvaluator.getDoneValues().entrySet()) {
      SkyKey key = skyKeyAndValue.getKey();
      SkyValue skyValue = skyKeyAndValue.getValue();
      SkyFunctionName functionName = key.functionName();
      try {
        // The skyValue may be null in case analysis of the previous build failed.
        if (skyValue != null) {
          if (functionName.equals(SkyFunctions.CONFIGURED_TARGET)) {
            actionGraphDump.dumpConfiguredTarget((ConfiguredTargetValue) skyValue);
          } else if (functionName.equals(SkyFunctions.ASPECT)) {
            AspectValue aspectValue = (AspectValue) skyValue;
            AspectKey aspectKey = aspectValue.getKey();
            ConfiguredTargetValue configuredTargetValue =
                (ConfiguredTargetValue)
                memoizingEvaluator.getExistingValue(aspectKey.getBaseConfiguredTargetKey());
            actionGraphDump.dumpAspect(aspectValue, configuredTargetValue);
          }
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("No interruption in sequenced evaluation", e);
      }
    }
    return actionGraphDump.build();
  }


  /**
   * In addition to calling the superclass method, deletes all ConfiguredTarget values from the
   * Skyframe cache. This is done to save memory (e.g. on a configuration change); since the
   * configuration is part of the key, these key/value pairs will be sitting around doing nothing
   * until the configuration changes back to the previous value.
   *
   * <p>The next evaluation will delete all invalid values.
   */
  @Override
  public void handleConfiguredTargetChange() {
    super.handleConfiguredTargetChange();
    memoizingEvaluator.delete(
        // We delete any value that can hold an action -- all subclasses of ActionLookupValue -- as
        // well as ActionExecutionValues, since they do not depend on ActionLookupValues.
        SkyFunctionName.functionIsIn(ImmutableSet.of(
            SkyFunctions.CONFIGURED_TARGET,
            SkyFunctions.BUILD_INFO,
            SkyFunctions.TARGET_COMPLETION,
            SkyFunctions.BUILD_INFO_COLLECTION,
            SkyFunctions.ACTION_EXECUTION))
    );
  }

  /**
   * Deletes all ConfiguredTarget values from the Skyframe cache.
   *
   * <p>After the execution of this method all invalidated and marked for deletion values (and the
   * values depending on them) will be deleted from the cache.
   *
   * <p>WARNING: Note that a call to this method leaves legacy data inconsistent with Skyframe. The
   * next build should clear the legacy caches.
   */
  private void dropConfiguredTargetsNow(final ExtendedEventHandler eventHandler) {
    handleConfiguredTargetChange();
    // Run the invalidator to actually delete the values.
    try {
      progressReceiver.ignoreInvalidations = true;
      Uninterruptibles.callUninterruptibly(
          new Callable<Void>() {
            @Override
            public Void call() throws InterruptedException {
              EvaluationContext evaluationContext =
                  EvaluationContext.newBuilder()
                      .setKeepGoing(false)
                      .setNumThreads(ResourceUsage.getAvailableProcessors())
                      .setEventHander(eventHandler)
                      .build();
              buildDriver.evaluate(ImmutableList.<SkyKey>of(), evaluationContext);
              return null;
            }
          });
    } catch (Exception e) {
      throw new IllegalStateException(e);
    } finally {
      progressReceiver.ignoreInvalidations = false;
    }
  }

  @Override
  public void deleteOldNodes(long versionWindowForDirtyGc) {
    // TODO(bazel-team): perhaps we should come up with a separate GC class dedicated to maintaining
    // value garbage. If we ever do so, this logic should be moved there.
    memoizingEvaluator.deleteDirty(versionWindowForDirtyGc);
  }

  @Override
  public void dumpPackages(PrintStream out) {
    Iterable<SkyKey> packageSkyKeys = Iterables.filter(memoizingEvaluator.getValues().keySet(),
        SkyFunctions.isSkyFunction(SkyFunctions.PACKAGE));
    out.println(Iterables.size(packageSkyKeys) + " packages");
    for (SkyKey packageSkyKey : packageSkyKeys) {
      Package pkg = ((PackageValue) memoizingEvaluator.getValues().get(packageSkyKey)).getPackage();
      pkg.dump(out);
    }
  }
}
