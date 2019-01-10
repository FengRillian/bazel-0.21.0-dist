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

package com.google.devtools.build.lib.analysis;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Streams;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.ArtifactPathResolver;
import com.google.devtools.build.lib.analysis.configuredtargets.RuleConfiguredTarget.Mode;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.collect.nestedset.Order;
import com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.EventHandler;
import com.google.devtools.build.lib.events.EventKind;
import com.google.devtools.build.lib.events.Location;
import com.google.devtools.build.lib.packages.BuildType;
import com.google.devtools.build.lib.skyframe.serialization.autocodec.AutoCodec;
import com.google.devtools.build.lib.skyframe.serialization.autocodec.AutoCodec.VisibleForSerialization;
import com.google.devtools.build.lib.skylarkbuildapi.RunfilesApi;
import com.google.devtools.build.lib.skylarkbuildapi.SymlinkEntryApi;
import com.google.devtools.build.lib.skylarkinterface.SkylarkPrinter;
import com.google.devtools.build.lib.util.Fingerprint;
import com.google.devtools.build.lib.vfs.PathFragment;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import javax.annotation.Nullable;

/**
 * An object that encapsulates runfiles. Conceptually, the runfiles are a map of paths to files,
 * forming a symlink tree.
 *
 * <p>In order to reduce memory consumption, this map is not explicitly stored here, but instead as
 * a combination of four parts: artifacts placed at their root-relative paths, source tree symlinks,
 * root symlinks (outside of the source tree), and artifacts included as parts of "pruning
 * manifests" (see {@link PruningManifest}).
 */
@Immutable
@AutoCodec
public final class Runfiles implements RunfilesApi {
  private static final Function<SymlinkEntry, Artifact> TO_ARTIFACT =
      new Function<SymlinkEntry, Artifact>() {
        @Override
        public Artifact apply(SymlinkEntry input) {
          return input.getArtifact();
        }
      };

  private static class DummyEmptyFilesSupplier implements EmptyFilesSupplier {
    private DummyEmptyFilesSupplier() {}

    @Override
    public Iterable<PathFragment> getExtraPaths(Set<PathFragment> manifestPaths) {
      return ImmutableList.of();
    }
  }

  @AutoCodec @AutoCodec.VisibleForSerialization
  static final EmptyFilesSupplier DUMMY_EMPTY_FILES_SUPPLIER = new DummyEmptyFilesSupplier();

  /**
   * An entry in the runfiles map.
   *
   * <p>build-runfiles.cc enforces the following constraints: The PathFragment must not be an
   * absolute path, nor contain "..". Overlapping runfiles links are also refused. This is the case
   * where you ask to create a link to "foo" and also "foo/bar.txt". I.e. you're asking it to make
   * "foo" both a file (symlink) and a directory.
   *
   * <p>Links to directories are heavily discouraged.
   */
  //
  // O intrepid fixer or bugs and implementor of features, dare not to add a .equals() method
  // to this class, lest you condemn yourself, or a fellow other developer to spending two
  // delightful hours in a fancy hotel on a Chromebook that is utterly unsuitable for Java
  // development to figure out what went wrong, just like I just did.
  //
  // The semantics of the symlinks nested set dictates that later entries overwrite earlier
  // ones. However, the semantics of nested sets dictate that if there are duplicate entries, they
  // are only returned once in the iterator.
  //
  // These two things, innocent when taken alone, result in the effect that when there are three
  // entries for the same path, the first one and the last one the same, and the middle one
  // different, the *middle* one will take effect: the middle one overrides the first one, and the
  // first one prevents the last one from appearing on the iterator.
  //
  // The lack of a .equals() method prevents this by making the first entry in the above case not
  // equals to the third one if they are not the same instance (which they almost never are)
  //
  // Goodnight, prince(ss)?, and sweet dreams.
  @AutoCodec
  @VisibleForSerialization
  static final class SymlinkEntry implements SymlinkEntryApi {
    private final PathFragment path;
    private final Artifact artifact;

    @VisibleForSerialization
    SymlinkEntry(PathFragment path, Artifact artifact) {
      this.path = Preconditions.checkNotNull(path);
      this.artifact = Preconditions.checkNotNull(artifact);
    }

    @Override
    public String getPathString() {
      return getPath().getPathString();
    }

    public PathFragment getPath() {
      return path;
    }

    @Override
    public Artifact getArtifact() {
      return artifact;
    }

    @Override
    public boolean isImmutable() {
      return true;
    }

    @Override
    public void repr(SkylarkPrinter printer) {
      printer.append("SymlinkEntry(path = ");
      printer.repr(getPathString());
      printer.append(", target_file = ");
      getArtifact().repr(printer);
      printer.append(")");
    }
  }

  // It is important to declare this *after* the DUMMY_SYMLINK_EXPANDER to avoid NPEs
  public static final Runfiles EMPTY = new Builder().build();

  /**
   * The directory to put all runfiles under.
   *
   * <p>Using "foo" will put runfiles under &lt;target&gt;.runfiles/foo.</p>
   *
   * <p>This is either set to the workspace name, or is empty.
   */
  private final PathFragment suffix;

  /**
   * The artifacts that should *always* be present in the runfiles directory. These are
   * differentiated from the artifacts that may or may not be included by a pruning manifest
   * (see {@link PruningManifest} below).
   *
   * <p>This collection may not include any middlemen. These artifacts will be placed at a location
   * that corresponds to the root-relative path of each artifact. It's possible for several
   * artifacts to have the same root-relative path, in which case the last one will win.
   */
  private final NestedSet<Artifact> unconditionalArtifacts;

  /**
   * A map of symlinks that should be present in the runfiles directory. In general, the symlink can
   * be determined from the artifact by using the root-relative path, so this should only be used
   * for cases where that isn't possible.
   *
   * <p>This may include runfiles symlinks from the root of the runfiles tree.
   */
  private final NestedSet<SymlinkEntry> symlinks;

  /**
   * A map of symlinks that should be present above the runfiles directory. These are useful for
   * certain rule types like AppEngine apps which have root level config files outside of the
   * regular source tree.
   */
  private final NestedSet<SymlinkEntry> rootSymlinks;

  /**
   * A set of middlemen artifacts. {@link RuleConfiguredTargetBuilder} adds these to the {@link
   * FilesToRunProvider} of binaries that include this runfiles tree in their runfiles.
   */
  private final NestedSet<Artifact> extraMiddlemen;

  /**
   * Interface used for adding empty files to the runfiles at the last minute. Mainly to support
   * python-related rules adding __init__.py files.
   */
  public interface EmptyFilesSupplier {
    /** Calculate additional empty files to add based on the existing manifest paths. */
    Iterable<PathFragment> getExtraPaths(Set<PathFragment> manifestPaths);
  }

  /** Generates extra (empty file) inputs. */
  private final EmptyFilesSupplier emptyFilesSupplier;

  /**
   * Behavior upon finding a conflict between two runfile entries. A conflict means that two
   * different artifacts have the same runfiles path specified.  For example, adding artifact
   * "a.foo" at path "bar" when there is already an artifact "b.foo" at path "bar".  The policies
   * are ordered from least strict to most strict.
   *
   * <p>Note that conflicts are found relatively late, when the manifest file is created, not when
   * the symlinks are added to runfiles.
   *
   * <p>If no EventHandler is available, all values are treated as IGNORE.
   */
  public enum ConflictPolicy {
    IGNORE,
    WARN,
    ERROR,
  }

  /** Policy for this Runfiles tree */
  private ConflictPolicy conflictPolicy = ConflictPolicy.IGNORE;

  /**
   * Defines a set of artifacts that may or may not be included in the runfiles directory and a
   * manifest file that makes that determination. These are applied on top of any artifacts
   * specified in {@link #unconditionalArtifacts}.
   *
   * <p>The incentive behind this is to enable execution-phase "pruning" of runfiles. Anything set
   * in unconditionalArtifacts is hard-set in Blaze's analysis phase, and thus unchangeable in
   * response to execution phase results. This isn't always convenient. For example, say we have an
   * action that consumes a set of "possible" runtime dependencies for a source file, parses that
   * file for "import a.b.c" statements, and outputs a manifest of the actual dependencies that are
   * referenced and thus really needed. This can reduce the size of the runfiles set, but we can't
   * use this information until the manifest output is available.
   *
   * <p>Only artifacts present in the candidate set AND the manifest output make it into the
   * runfiles tree. The candidate set requirement guarantees that analysis-time dependencies are a
   * superset of the pruned dependencies, so undeclared inclusions (which can break build
   * correctness) aren't possible.
   */
  @AutoCodec
  public static class PruningManifest {
    private final NestedSet<Artifact> candidateRunfiles;
    private final Artifact manifestFile;

    /**
     * Creates a new pruning manifest.
     *
     * @param candidateRunfiles set of possible artifacts that the manifest file may reference
     * @param manifestFile the manifest file, expected to be a newline-separated list of
     *     source tree root-relative paths (i.e. "my/package/myfile.txt"). Anything that can't be
     *     resolved back to an entry in candidateRunfiles is ignored and will *not* make it into
     *     the runfiles tree.
     */
    public PruningManifest(NestedSet<Artifact> candidateRunfiles, Artifact manifestFile) {
      this.candidateRunfiles = candidateRunfiles;
      this.manifestFile = manifestFile;
    }

    public NestedSet<Artifact> getCandidateRunfiles() {
      return candidateRunfiles;
    }

    public Artifact getManifestFile() {
      return manifestFile;
    }
  }

  /**
   * The pruning manifests that should be applied to these runfiles.
   */
  private final NestedSet<PruningManifest> pruningManifests;

  /**
   * If external runfiles should be created under .runfiles/wsname/external/repo as well as
   * .runfiles/repo.
   */
  private final boolean legacyExternalRunfiles;

  @AutoCodec.Instantiator
  @VisibleForSerialization
  Runfiles(
      PathFragment suffix,
      NestedSet<Artifact> unconditionalArtifacts,
      NestedSet<SymlinkEntry> symlinks,
      NestedSet<SymlinkEntry> rootSymlinks,
      NestedSet<PruningManifest> pruningManifests,
      NestedSet<Artifact> extraMiddlemen,
      EmptyFilesSupplier emptyFilesSupplier,
      ConflictPolicy conflictPolicy,
      boolean legacyExternalRunfiles) {
    this.suffix = suffix;
    this.unconditionalArtifacts = Preconditions.checkNotNull(unconditionalArtifacts);
    this.symlinks = Preconditions.checkNotNull(symlinks);
    this.rootSymlinks = Preconditions.checkNotNull(rootSymlinks);
    this.extraMiddlemen = Preconditions.checkNotNull(extraMiddlemen);
    this.pruningManifests = Preconditions.checkNotNull(pruningManifests);
    this.emptyFilesSupplier = Preconditions.checkNotNull(emptyFilesSupplier);
    this.conflictPolicy = conflictPolicy;
    this.legacyExternalRunfiles = legacyExternalRunfiles;
  }

  /**
   * Returns the runfiles' suffix.
   */
  public PathFragment getSuffix() {
    return suffix;
  }

  /**
   * Returns the artifacts that are unconditionally included in the runfiles (as opposed to
   * pruning manifest candidates, which may or may not be included).
   */
  public NestedSet<Artifact> getUnconditionalArtifacts() {
    return unconditionalArtifacts;
  }

  public NestedSet<Artifact> getExtraMiddlemen() {
    return extraMiddlemen;
  }

  /**
   * Returns the collection of runfiles as artifacts, including both unconditional artifacts and
   * pruning manifest candidates.
   */
  @Override
  public NestedSet<Artifact> getArtifacts() {
    NestedSetBuilder<Artifact> allArtifacts = NestedSetBuilder.stableOrder();
    allArtifacts.addTransitive(unconditionalArtifacts);
    for (PruningManifest manifest : getPruningManifests()) {
      allArtifacts.addTransitive(manifest.getCandidateRunfiles());
    }
    return allArtifacts.build();
  }

  /** Returns the symlinks. */
  @Override
  public NestedSet<SymlinkEntry> getSymlinks() {
    return symlinks;
  }

  @Override
  public NestedSet<String> getEmptyFilenames() {
    Set<PathFragment> manifestKeys =
        Streams.concat(
                Streams.stream(symlinks).map(SymlinkEntry::getPath),
                Streams.stream(getArtifacts()).map(Artifact::getRootRelativePath))
            .collect(ImmutableSet.toImmutableSet());
    Iterable<PathFragment> emptyKeys = emptyFilesSupplier.getExtraPaths(manifestKeys);
    return NestedSetBuilder.<String>stableOrder()
        .addAll(
            Streams.stream(emptyKeys)
                .map(PathFragment::toString)
                .collect(ImmutableList.toImmutableList()))
        .build();
  }

  /**
   * Returns the symlinks as a map from path fragment to artifact.
   *
   * @param checker If not null, check for conflicts using this checker.
   */
  public Map<PathFragment, Artifact> getSymlinksAsMap(@Nullable ConflictChecker checker) {
    return entriesToMap(symlinks, checker);
  }

  /**
   * @param eventHandler Used for throwing an error if we have an obscuring runlink.
   *                 May be null, in which case obscuring symlinks are silently discarded.
   * @param location Location for reporter. Ignored if reporter is null.
   * @param workingManifest Manifest to be checked for obscuring symlinks.
   * @return map of source file names mapped to their location on disk.
   */
  @VisibleForTesting
  static Map<PathFragment, Artifact> filterListForObscuringSymlinks(
      EventHandler eventHandler, Location location, Map<PathFragment, Artifact> workingManifest) {
    Map<PathFragment, Artifact> newManifest = new HashMap<>();

    outer:
    for (Iterator<Map.Entry<PathFragment, Artifact>> i = workingManifest.entrySet().iterator();
        i.hasNext(); ) {
      Map.Entry<PathFragment, Artifact> entry = i.next();
      PathFragment source = entry.getKey();
      Artifact symlink = entry.getValue();
      // drop nested entries; warn if this changes anything
      int n = source.segmentCount();
      for (int j = 1; j < n; ++j) {
        PathFragment prefix = source.subFragment(0, n - j);
        Artifact ancestor = workingManifest.get(prefix);
        if (ancestor != null) {
          // This is an obscuring symlink, so just drop it and move on if there's no reporter.
          if (eventHandler == null) {
            continue outer;
          }
          PathFragment suffix = source.subFragment(n - j, n);
          PathFragment viaAncestor = ancestor.getExecPath().getRelative(suffix);
          PathFragment expected = symlink.getExecPath();
          if (!viaAncestor.equals(expected)) {
            eventHandler.handle(
                Event.warn(
                    location,
                    "runfiles symlink "
                        + source
                        + " -> "
                        + expected
                        + " obscured by "
                        + prefix
                        + " -> "
                        + ancestor.getExecPath()));
          }
          continue outer;
        }
      }
      newManifest.put(entry.getKey(), entry.getValue());
    }
    return newManifest;
  }

  /**
   * Returns the symlinks as a map from PathFragment to Artifact.
   *
   * @param eventHandler Used for throwing an error if we have an obscuring runlink within the
   *     normal source tree entries, or runfile conflicts. May be null, in which case obscuring
   *     symlinks are silently discarded, and conflicts are overwritten.
   * @param location Location for eventHandler warnings. Ignored if eventHandler is null.
   * @param resolver The {@link ArtifactPathResolver} to use for the pruning manifest, if present.
   * @return Map<PathFragment, Artifact> path fragment to artifact, of normal source tree entries
   *     and elements that live outside the source tree. Null values represent empty input files.
   */
  public Map<PathFragment, Artifact> getRunfilesInputs(EventHandler eventHandler, Location location,
      ArtifactPathResolver resolver) throws IOException {
    ConflictChecker checker = new ConflictChecker(conflictPolicy, eventHandler, location);
    Map<PathFragment, Artifact> manifest = getSymlinksAsMap(checker);
    // Add unconditional artifacts (committed to inclusion on construction of runfiles).
    for (Artifact artifact : getUnconditionalArtifacts()) {
      checker.put(manifest, artifact.getRootRelativePath(), artifact);
    }

    // Add conditional artifacts (only included if they appear in a pruning manifest).
    for (Runfiles.PruningManifest pruningManifest : getPruningManifests()) {
      // This map helps us convert from source tree root-relative paths back to artifacts.
      Map<String, Artifact> allowedRunfiles = new HashMap<>();
      for (Artifact artifact : pruningManifest.getCandidateRunfiles()) {
        allowedRunfiles.put(artifact.getRootRelativePath().getPathString(), artifact);
      }
      try (BufferedReader reader = new BufferedReader(
          new InputStreamReader(resolver.toPath(pruningManifest.getManifestFile()).getInputStream()))) {
        String line;
        while ((line = reader.readLine()) != null) {
          Artifact artifact = allowedRunfiles.get(line);
          if (artifact != null) {
            checker.put(manifest, artifact.getRootRelativePath(), artifact);
          }
        }
      }
    }
    manifest = filterListForObscuringSymlinks(eventHandler, location, manifest);

    // TODO(bazel-team): Create /dev/null-like Artifact to avoid nulls?
    for (PathFragment extraPath : emptyFilesSupplier.getExtraPaths(manifest.keySet())) {
      checker.put(manifest, extraPath, null);
    }

    // Copy manifest map to another manifest map, prepending the workspace name to every path.
    // E.g. for workspace "myworkspace", the runfile entry "mylib.so"->"/path/to/mylib.so" becomes
    // "myworkspace/mylib.so"->"/path/to/mylib.so".
    ManifestBuilder builder = new ManifestBuilder(suffix, legacyExternalRunfiles);
    builder.addUnderWorkspace(manifest, checker);

    // Finally add symlinks relative to the root of the runfiles tree, on top of everything else.
    // This operation is always checked for conflicts, to match historical behavior.
    if (conflictPolicy == ConflictPolicy.IGNORE) {
      checker = new ConflictChecker(ConflictPolicy.WARN, eventHandler, location);
    }
    builder.add(getRootSymlinksAsMap(checker), checker);
    return builder.build();
  }

  /**
   * Helper class to handle munging the paths of external artifacts.
   */
  @VisibleForTesting
  static final class ManifestBuilder {
    // Manifest of paths to artifacts. Path fragments are relative to the .runfiles directory.
    private final Map<PathFragment, Artifact> manifest;
    private final PathFragment workspaceName;
    private final boolean legacyExternalRunfiles;
    // Whether we saw the local workspace name in the runfiles. If legacyExternalRunfiles is true,
    // then this is true, as anything under external/ will also have a runfile under the local
    // workspace.
    private boolean sawWorkspaceName;

    public ManifestBuilder(
        PathFragment workspaceName, boolean legacyExternalRunfiles) {
      this.manifest = new HashMap<>();
      this.workspaceName = workspaceName;
      this.legacyExternalRunfiles = legacyExternalRunfiles;
      this.sawWorkspaceName = legacyExternalRunfiles;
    }

    /**
     * Adds a map under the workspaceName.
     */
    public void addUnderWorkspace(
        Map<PathFragment, Artifact> inputManifest, ConflictChecker checker) {
      for (Map.Entry<PathFragment, Artifact> entry : inputManifest.entrySet()) {
        PathFragment path = entry.getKey();
        if (isUnderWorkspace(path)) {
          sawWorkspaceName = true;
          checker.put(manifest, workspaceName.getRelative(path), entry.getValue());
        } else {
          if (legacyExternalRunfiles) {
            checker.put(manifest, workspaceName.getRelative(path), entry.getValue());
          }
          // Always add the non-legacy .runfiles/repo/whatever path.
          checker.put(manifest, getExternalPath(path), entry.getValue());
        }
      }
    }

    /**
     * Adds a map to the root directory.
     */
    public void add(Map<PathFragment, Artifact> inputManifest, ConflictChecker checker) {
      for (Map.Entry<PathFragment, Artifact> entry : inputManifest.entrySet()) {
        checker.put(manifest, checkForWorkspace(entry.getKey()), entry.getValue());
      }
    }

    /**
     * Returns the manifest, adding the workspaceName directory if it is not already present.
     */
    public Map<PathFragment, Artifact> build() {
      if (!sawWorkspaceName) {
        // If we haven't seen it and we have seen other files, add the workspace name directory.
        // It might not be there if all of the runfiles are from other repos (and then running from
        // x.runfiles/ws will fail, because ws won't exist). We can't tell Runfiles to create a
        // directory, so instead this creates a hidden file inside the desired directory.
        manifest.put(workspaceName.getRelative(".runfile"), null);
      }
      return manifest;
    }

    private PathFragment getExternalPath(PathFragment path) {
      return checkForWorkspace(path.relativeTo(Label.EXTERNAL_PACKAGE_NAME));
    }

    private PathFragment checkForWorkspace(PathFragment path) {
      sawWorkspaceName = sawWorkspaceName
          || path.getSegment(0).equals(workspaceName.getPathString());
      return path;
    }

    private static boolean isUnderWorkspace(PathFragment path) {
      return !path.startsWith(Label.EXTERNAL_PACKAGE_NAME);
    }
  }

  boolean getLegacyExternalRunfiles() {
    return legacyExternalRunfiles;
  }

  /** Returns the root symlinks. */
  @Override
  public NestedSet<SymlinkEntry> getRootSymlinks() {
    return rootSymlinks;
  }

  /**
   * Returns the root symlinks as a map from path fragment to artifact.
   *
   * @param checker If not null, check for conflicts using this checker.
   */
  public Map<PathFragment, Artifact> getRootSymlinksAsMap(@Nullable ConflictChecker checker) {
    return entriesToMap(rootSymlinks, checker);
  }

  /**
   * Returns the unified map of path fragments to artifacts, taking both artifacts and symlinks into
   * account.
   */
  public Map<PathFragment, Artifact> asMapWithoutRootSymlinks() {
    Map<PathFragment, Artifact> result = entriesToMap(symlinks, null);
    // If multiple artifacts have the same root-relative path, the last one in the list will win.
    // That is because the runfiles tree cannot contain the same artifact for different
    // configurations, because it only uses root-relative paths.
    for (Artifact artifact : unconditionalArtifacts) {
      result.put(artifact.getRootRelativePath(), artifact);
    }
    return result;
  }

  /**
   * Returns the pruning manifests specified for this runfiles tree.
   */
  public NestedSet<PruningManifest> getPruningManifests() {
    return pruningManifests;
  }

  /**
   * Returns the manifest expander specified for this runfiles tree.
   */
  private EmptyFilesSupplier getEmptyFilesProvider() {
    return emptyFilesSupplier;
  }

  /**
   * Returns the unified map of path fragments to artifacts, taking into account artifacts,
   * symlinks, and pruning manifest candidates. The returned set is guaranteed to be a (not
   * necessarily strict) superset of the actual runfiles tree created at execution time.
   */
  public NestedSet<Artifact> getAllArtifacts() {
    if (isEmpty()) {
      return NestedSetBuilder.emptySet(Order.STABLE_ORDER);
    }
    NestedSetBuilder<Artifact> allArtifacts = NestedSetBuilder.stableOrder();
    allArtifacts
        .addTransitive(unconditionalArtifacts)
        .addAll(Iterables.transform(symlinks, TO_ARTIFACT))
        .addAll(Iterables.transform(rootSymlinks, TO_ARTIFACT));
    for (PruningManifest manifest : getPruningManifests()) {
      allArtifacts.addTransitive(manifest.getCandidateRunfiles());
    }
    return allArtifacts.build();
  }

  /**
   * Returns if there are no runfiles.
   */
  public boolean isEmpty() {
    return unconditionalArtifacts.isEmpty()
        && symlinks.isEmpty()
        && rootSymlinks.isEmpty()
        && pruningManifests.isEmpty()
        && extraMiddlemen.isEmpty();
  }

  /**
   * Flatten a sequence of entries into a single map.
   *
   * @param entrySet Sequence of entries to add.
   * @param checker If not null, check for conflicts with this checker, otherwise silently allow
   *    entries to overwrite previous entries.
   * @return Map<PathFragment, Artifact> Map of runfile entries.
   */
  private static Map<PathFragment, Artifact> entriesToMap(
      Iterable<SymlinkEntry> entrySet, @Nullable ConflictChecker checker) {
    checker = (checker != null) ? checker : ConflictChecker.IGNORE_CHECKER;
    Map<PathFragment, Artifact> map = new LinkedHashMap<>();
    for (SymlinkEntry entry : entrySet) {
      checker.put(map, entry.getPath(), entry.getArtifact());
    }
    return map;
  }

  /** Returns currently policy for conflicting symlink entries. */
  public ConflictPolicy getConflictPolicy() {
    return this.conflictPolicy;
  }

  /** Set whether we should warn about conflicting symlink entries. */
  public Runfiles setConflictPolicy(ConflictPolicy conflictPolicy) {
    this.conflictPolicy = conflictPolicy;
    return this;
  }

  /**
   * Checks for conflicts between entries in a runfiles tree while putting them in a map.
   */
  public static final class ConflictChecker {
    /** Prebuilt ConflictChecker with policy set to IGNORE */
    public static final ConflictChecker IGNORE_CHECKER =
        new ConflictChecker(ConflictPolicy.IGNORE, null, null);

    /** Behavior when a conflict is found. */
    private final ConflictPolicy policy;

    /** Used for warning on conflicts. May be null, in which case conflicts are ignored. */
    private final EventHandler eventHandler;

    /** Location for eventHandler warnings. Ignored if eventHandler is null. */
    private final Location location;

    /** Type of event to emit */
    private final EventKind eventKind;

    /** Construct a ConflictChecker for the given reporter with the given behavior */
    public ConflictChecker(ConflictPolicy policy, EventHandler eventHandler, Location location) {
      if (eventHandler == null) {
        this.policy = ConflictPolicy.IGNORE; // Can't warn even if we wanted to
      } else {
        this.policy = policy;
      }
      this.eventHandler = eventHandler;
      this.location = location;
      this.eventKind = (policy == ConflictPolicy.ERROR) ? EventKind.ERROR : EventKind.WARNING;
    }

    /**
     * Add an entry to a Map of symlinks, optionally reporting conflicts.
     *
     * @param map Manifest of runfile entries.
     * @param path Path fragment to use as key in map.
     * @param artifact Artifact to store in map. This may be null to indicate an empty file.
     */
    public void put(Map<PathFragment, Artifact> map, PathFragment path, Artifact artifact) {
      Preconditions.checkArgument(
          artifact == null || !artifact.isMiddlemanArtifact(), "%s", artifact);
      if (policy != ConflictPolicy.IGNORE && map.containsKey(path)) {
        // Previous and new entry might have value of null
        Artifact previous = map.get(path);
        if (!Objects.equals(previous, artifact)) {
          String previousStr =
              (previous == null) ? "empty file" : previous.getExecPath().toString();
          String artifactStr =
              (artifact == null) ? "empty file" : artifact.getExecPath().toString();
          String message =
              String.format(
                  "overwrote runfile %s, was symlink to %s, now symlink to %s",
                  path.getSafePathString(),
                  previousStr,
                  artifactStr);
          eventHandler.handle(Event.of(eventKind, location, message));
        }
      }
      map.put(path, artifact);
    }
  }

  /**
   * Builder for Runfiles objects.
   */
  public static final class Builder {

    /** This is set to the workspace name */
    private PathFragment suffix;

    /**
     * This must be COMPILE_ORDER because {@link #asMapWithoutRootSymlinks} overwrites earlier
     * entries with later ones, so we want a post-order iteration.
     */
    private NestedSetBuilder<Artifact> artifactsBuilder =
        NestedSetBuilder.compileOrder();
    private NestedSetBuilder<SymlinkEntry> symlinksBuilder =
        NestedSetBuilder.stableOrder();
    private NestedSetBuilder<SymlinkEntry> rootSymlinksBuilder =
        NestedSetBuilder.stableOrder();
    private NestedSetBuilder<PruningManifest> pruningManifestsBuilder =
        NestedSetBuilder.stableOrder();
    private NestedSetBuilder<Artifact> extraMiddlemenBuilder = NestedSetBuilder.stableOrder();
    private EmptyFilesSupplier emptyFilesSupplier = DUMMY_EMPTY_FILES_SUPPLIER;

    /** Build the Runfiles object with this policy */
    private ConflictPolicy conflictPolicy = ConflictPolicy.IGNORE;

    private final boolean legacyExternalRunfiles;

    /**
     * Only used for Runfiles.EMPTY.
     */
    private Builder() {
      this.suffix = PathFragment.EMPTY_FRAGMENT;
      this.legacyExternalRunfiles = false;
    }

    /**
     * Creates a builder with the given suffix. Transitional constructor so that new rules don't
     * accidentally depend on the legacy repository structure, until that option is removed.
     *
     * @param workspace is the string specified in workspace() in the WORKSPACE file.
     */
    public Builder(String workspace) {
      this(workspace, false);
    }

    /**
     * Creates a builder with the given suffix.
     * @param workspace is the string specified in workspace() in the WORKSPACE file.
     * @param legacyExternalRunfiles if the wsname/external/repo symlinks should also be
     *     created.
     */
    public Builder(String workspace, boolean legacyExternalRunfiles) {
      this(PathFragment.create(workspace), legacyExternalRunfiles);
    }

    /**
     * Creates a builder with the given suffix.
     * @param suffix is the PathFragment wrapping the string specified in workspace() in the
     *     WORKSPACE file.
     * @param legacyExternalRunfiles if the wsname/external/repo symlinks should also be
     *     created.
     */
    private Builder(PathFragment suffix, boolean legacyExternalRunfiles) {
      this.suffix = suffix;
      this.legacyExternalRunfiles = legacyExternalRunfiles;
    }

    /**
     * Builds a new Runfiles object.
     */
    public Runfiles build() {
      return new Runfiles(
          suffix,
          artifactsBuilder.build(),
          symlinksBuilder.build(),
          rootSymlinksBuilder.build(),
          pruningManifestsBuilder.build(),
          extraMiddlemenBuilder.build(),
          emptyFilesSupplier,
          conflictPolicy,
          legacyExternalRunfiles);
    }

    /**
     * Adds an artifact to the internal collection of artifacts.
     */
    public Builder addArtifact(Artifact artifact) {
      Preconditions.checkNotNull(artifact);
      Preconditions.checkArgument(!artifact.isMiddlemanArtifact());
      artifactsBuilder.add(artifact);
      return this;
    }

    /**
     * Adds several artifacts to the internal collection.
     */
    public Builder addArtifacts(Iterable<Artifact> artifacts) {
      for (Artifact artifact : artifacts) {
        addArtifact(artifact);
      }
      return this;
    }

    /**
     * @deprecated Use {@link #addTransitiveArtifacts} instead, to prevent increased memory use.
     *     <p>See also {@link Builder#addTransitiveArtifactsWrappedInStableOrder}
     */
    @Deprecated
    public Builder addArtifacts(NestedSet<Artifact> artifacts) {
      // Do not delete this method, or else addArtifacts(Iterable) calls with a NestedSet argument
      // will not be flagged.
      Iterable<Artifact> it = artifacts;
      addArtifacts(it);
      return this;
    }

    /**
     * Adds a nested set to the internal collection.
     */
    public Builder addTransitiveArtifacts(NestedSet<Artifact> artifacts) {
      artifactsBuilder.addTransitive(artifacts);
      return this;
    }

    /**
     * Adds a nested set to the internal collection.
     *
     * <p>The nested set will become wrapped in stable order. Only use this when the set of
     * artifacts will not have conflicting root relative paths, or the wrong artifact will end up in
     * the runfiles tree.
     */
    public Builder addTransitiveArtifactsWrappedInStableOrder(NestedSet<Artifact> artifacts) {
      NestedSet<Artifact> wrappedArtifacts =
          NestedSetBuilder.<Artifact>stableOrder().addTransitive(artifacts).build();
      artifactsBuilder.addTransitive(wrappedArtifacts);
      return this;
    }

    /**
     * Adds a symlink.
     */
    public Builder addSymlink(PathFragment link, Artifact target) {
      symlinksBuilder.add(new SymlinkEntry(link, target));
      return this;
    }

    /** Adds several symlinks. Neither keys nor values may be null. */
    public Builder addSymlinks(Map<PathFragment, Artifact> symlinks) {
      for (Map.Entry<PathFragment, Artifact> symlink : symlinks.entrySet()) {
        symlinksBuilder.add(new SymlinkEntry(symlink.getKey(), symlink.getValue()));
      }
      return this;
    }

    /**
     * Adds several symlinks as a NestedSet.
     */
    public Builder addSymlinks(NestedSet<SymlinkEntry> symlinks) {
      symlinksBuilder.addTransitive(symlinks);
      return this;
    }

    /**
     * Adds a root symlink.
     */
    public Builder addRootSymlink(PathFragment link, Artifact target) {
      rootSymlinksBuilder.add(new SymlinkEntry(link, target));
      return this;
    }

    /** Adds several root symlinks. Neither keys nor values may be null. */
    public Builder addRootSymlinks(Map<PathFragment, Artifact> symlinks) {
      for (Map.Entry<PathFragment, Artifact> symlink : symlinks.entrySet()) {
        rootSymlinksBuilder.add(new SymlinkEntry(symlink.getKey(), symlink.getValue()));
      }
      return this;
    }

    /**
     * Adds several root symlinks as a NestedSet.
     */
    public Builder addRootSymlinks(NestedSet<SymlinkEntry> symlinks) {
      rootSymlinksBuilder.addTransitive(symlinks);
      return this;
    }

    /**
     * Adds a pruning manifest. See {@link PruningManifest} for an explanation.
     */
    public Builder addPruningManifest(PruningManifest manifest) {
      pruningManifestsBuilder.add(manifest);
      return this;
    }

    /**
     * Adds several pruning manifests as a NestedSet. See {@link PruningManifest} for an
     * explanation.
     */
    public Builder addPruningManifests(NestedSet<PruningManifest> manifests) {
      pruningManifestsBuilder.addTransitive(manifests);
      return this;
    }

    /**
     * Specify a function that can create additional manifest entries based on the input entries,
     * see {@link EmptyFilesSupplier} for more details.
     */
    public Builder setEmptyFilesSupplier(EmptyFilesSupplier supplier) {
      emptyFilesSupplier = Preconditions.checkNotNull(supplier);
      return this;
    }

    /**
     * Merges runfiles from a given runfiles support.
     *
     * @param runfilesSupport the runfiles support to be merged in
     */
    public Builder merge(@Nullable RunfilesSupport runfilesSupport) {
      if (runfilesSupport == null) {
        return this;
      }
      merge(runfilesSupport.getRunfiles());
      return this;
    }

    /**
     * Adds the runfiles for a particular target and visits the transitive closure of "srcs",
     * "deps" and "data", collecting all of their respective runfiles.
     */
    public Builder addRunfiles(RuleContext ruleContext,
        Function<TransitiveInfoCollection, Runfiles> mapping) {
      Preconditions.checkNotNull(mapping);
      Preconditions.checkNotNull(ruleContext);
      addDataDeps(ruleContext);
      addNonDataDeps(ruleContext, mapping);
      return this;
    }

    /**
     * Adds the files specified by a mapping from the transitive info collection to the runfiles.
     *
     * <p>Dependencies in {@code srcs} and {@code deps} are considered.
     */
    public Builder add(RuleContext ruleContext,
        Function<TransitiveInfoCollection, Runfiles> mapping) {
      Preconditions.checkNotNull(ruleContext);
      Preconditions.checkNotNull(mapping);
      for (TransitiveInfoCollection dep : getNonDataDeps(ruleContext)) {
        Runfiles runfiles = mapping.apply(dep);
        if (runfiles != null) {
          merge(runfiles);
        }
      }

      return this;
    }

    /**
     * Collects runfiles from data dependencies of a target.
     */
    public Builder addDataDeps(RuleContext ruleContext) {
      addTargets(getPrerequisites(ruleContext, "data", Mode.DONT_CHECK),
          RunfilesProvider.DATA_RUNFILES);
      return this;
    }

    /**
     * Collects runfiles from "srcs" and "deps" of a target.
     */
    public Builder addNonDataDeps(RuleContext ruleContext,
        Function<TransitiveInfoCollection, Runfiles> mapping) {
      for (TransitiveInfoCollection target : getNonDataDeps(ruleContext)) {
        addTargetExceptFileTargets(target, mapping);
      }
      return this;
    }

    public Builder addTargets(Iterable<? extends TransitiveInfoCollection> targets,
        Function<TransitiveInfoCollection, Runfiles> mapping) {
      for (TransitiveInfoCollection target : targets) {
        addTarget(target, mapping);
      }
      return this;
    }

    public Builder addTarget(TransitiveInfoCollection target,
        Function<TransitiveInfoCollection, Runfiles> mapping) {
      return addTargetIncludingFileTargets(target, mapping);
    }

    private Builder addTargetExceptFileTargets(TransitiveInfoCollection target,
        Function<TransitiveInfoCollection, Runfiles> mapping) {
      Runfiles runfiles = mapping.apply(target);
      if (runfiles != null) {
        merge(runfiles);
      }

      return this;
    }

    private Builder addTargetIncludingFileTargets(TransitiveInfoCollection target,
        Function<TransitiveInfoCollection, Runfiles> mapping) {
      if (target.getProvider(RunfilesProvider.class) == null
          && mapping == RunfilesProvider.DATA_RUNFILES) {
        // RuleConfiguredTarget implements RunfilesProvider, so this will only be called on
        // FileConfiguredTarget instances.
        // TODO(bazel-team): This is a terrible hack. We should be able to make this go away
        // by implementing RunfilesProvider on FileConfiguredTarget. We'd need to be mindful
        // of the memory use, though, since we have a whole lot of FileConfiguredTarget instances.
        addTransitiveArtifacts(target.getProvider(FileProvider.class).getFilesToBuild());
        return this;
      }

      return addTargetExceptFileTargets(target, mapping);
    }

    /**
     * Adds symlinks to given artifacts at their exec paths.
     */
    public Builder addSymlinksToArtifacts(Iterable<Artifact> artifacts) {
      for (Artifact artifact : artifacts) {
        addSymlink(artifact.getExecPath(), artifact);
      }
      return this;
    }

    /**
     * Add extra middlemen artifacts that should be built by reverse dependency binaries. This
     * method exists solely to support the unfortunate legacy behavior of some rules; new uses
     * should not be added.
     */
    public Builder addLegacyExtraMiddleman(Artifact middleman) {
      Preconditions.checkArgument(middleman.isMiddlemanArtifact(), middleman);
      extraMiddlemenBuilder.add(middleman);
      return this;
    }

    /**
     * Add the other {@link Runfiles} object transitively.
     */
    public Builder merge(Runfiles runfiles) {
      return merge(runfiles, true, true);
    }

    /**
     * Add the other {@link Runfiles} object transitively, but don't merge
     * unconditional artifacts.
     */
    public Builder mergeExceptUnconditionalArtifacts(Runfiles runfiles) {
      return merge(runfiles, false, true);
    }

    /**
     * Add the other {@link Runfiles} object transitively, but don't merge
     * pruning manifests.
     */
    public Builder mergeExceptPruningManifests(Runfiles runfiles) {
      return merge(runfiles, true, false);
    }

    /**
     * Add the other {@link Runfiles} object transitively, with the option to include or exclude
     * pruning manifests in the merge.
     */
    private Builder merge(Runfiles runfiles, boolean includeUnconditionalArtifacts,
        boolean includePruningManifests) {
      // Propagate the most strict conflict checking from merged-in runfiles
      if (runfiles.conflictPolicy.compareTo(conflictPolicy) > 0) {
        conflictPolicy = runfiles.conflictPolicy;
      }
      if (runfiles.isEmpty()) {
        return this;
      }
      // The suffix should be the same within any blaze build, except for the EMPTY runfiles, which
      // may have an empty suffix, but that is covered above.
      Preconditions.checkArgument(suffix.equals(runfiles.suffix));
      if (includeUnconditionalArtifacts) {
        artifactsBuilder.addTransitive(runfiles.getUnconditionalArtifacts());
      }
      symlinksBuilder.addTransitive(runfiles.getSymlinks());
      rootSymlinksBuilder.addTransitive(runfiles.getRootSymlinks());
      if (includePruningManifests) {
        pruningManifestsBuilder.addTransitive(runfiles.getPruningManifests());
      }
      extraMiddlemenBuilder.addTransitive(runfiles.getExtraMiddlemen());
      if (emptyFilesSupplier == DUMMY_EMPTY_FILES_SUPPLIER) {
        emptyFilesSupplier = runfiles.getEmptyFilesProvider();
      } else {
        EmptyFilesSupplier otherSupplier = runfiles.getEmptyFilesProvider();
        Preconditions.checkState((otherSupplier == DUMMY_EMPTY_FILES_SUPPLIER)
          || emptyFilesSupplier.equals(otherSupplier));
      }
      return this;
    }

    private static Iterable<TransitiveInfoCollection> getNonDataDeps(RuleContext ruleContext) {
      return Iterables.concat(
          // TODO(bazel-team): This line shouldn't be here. Removing it requires that no rules have
          // dependent rules in srcs (except for filegroups and such), but always in deps.
          // TODO(bazel-team): DONT_CHECK is not optimal here. Rules that use split configs need to
          // be changed not to call into here.
          getPrerequisites(ruleContext, "srcs", Mode.DONT_CHECK),
          getPrerequisites(ruleContext, "deps", Mode.DONT_CHECK));
    }

    /**
     * For the specified attribute "attributeName" (which must be of type list(label)), resolves all
     * the labels into ConfiguredTargets (for the same configuration as this one) and returns them
     * as a list.
     *
     * <p>If the rule does not have the specified attribute, returns the empty list.
     */
    private static Iterable<? extends TransitiveInfoCollection> getPrerequisites(
        RuleContext ruleContext, String attributeName, Mode mode) {
      if (ruleContext.getRule().isAttrDefined(attributeName, BuildType.LABEL_LIST)) {
        return ruleContext.getPrerequisites(attributeName, mode);
      } else {
        return Collections.emptyList();
      }
    }
  }

  @Override
  public Runfiles merge(RunfilesApi other) {
    Runfiles.Builder builder = new Runfiles.Builder(suffix, false);
    builder.merge(this);
    builder.merge((Runfiles) other);
    return builder.build();
  }

  /**
   * Fingerprint this {@link Runfiles} tree.
   */
  public void fingerprint(Fingerprint fp) {
    fp.addBoolean(getLegacyExternalRunfiles());
    fp.addPath(getSuffix());
    Map<PathFragment, Artifact> symlinks = getSymlinksAsMap(null);
    fp.addInt(symlinks.size());
    for (Map.Entry<PathFragment, Artifact> symlink : symlinks.entrySet()) {
      fp.addPath(symlink.getKey());
      fp.addPath(symlink.getValue().getExecPath());
    }
    Map<PathFragment, Artifact> rootSymlinks = getRootSymlinksAsMap(null);
    fp.addInt(rootSymlinks.size());
    for (Map.Entry<PathFragment, Artifact> rootSymlink : rootSymlinks.entrySet()) {
      fp.addPath(rootSymlink.getKey());
      fp.addPath(rootSymlink.getValue().getExecPath());
    }

    for (Artifact artifact : getArtifacts()) {
      fp.addPath(artifact.getRootRelativePath());
      fp.addPath(artifact.getExecPath());
    }

    for (String name : getEmptyFilenames()) {
      fp.addString(name);
    }
  }
}
