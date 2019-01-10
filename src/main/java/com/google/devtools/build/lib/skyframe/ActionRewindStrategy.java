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

package com.google.devtools.build.lib.skyframe;

import com.google.common.base.Preconditions;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import com.google.devtools.build.lib.actions.Action;
import com.google.devtools.build.lib.actions.ActionInput;
import com.google.devtools.build.lib.actions.ActionInputDepOwners;
import com.google.devtools.build.lib.actions.ActionLookupData;
import com.google.devtools.build.lib.actions.AlreadyReportedActionExecutionException;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.LostInputsExecException;
import com.google.devtools.build.lib.actions.LostInputsExecException.LostInputsActionExecutionException;
import com.google.devtools.build.lib.skyframe.ActionExecutionFunction.ActionExecutionFunctionException;
import com.google.devtools.build.skyframe.SkyFunction.Environment;
import com.google.devtools.build.skyframe.SkyFunction.Restart;
import com.google.devtools.build.skyframe.SkyKey;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;
import javax.annotation.Nullable;

/**
 * Given an action that failed to execute because of lost inputs which were generated by other
 * actions, this finds the actions which generated them and the set of Skyframe nodes which must be
 * restarted in order to recreate the lost inputs.
 */
public class ActionRewindStrategy {
  private static final Logger logger = Logger.getLogger(ActionRewindStrategy.class.getName());

  /**
   * Returns a {@link RewindPlan} specifying:
   *
   * <ol>
   *   <li>the Skyframe nodes to restart to recreate the lost inputs specified by {@code
   *       lostInputsException}
   *   <li>the actions whose execution state (in {@link SkyframeActionExecutor}) must be reset
   * </ol>
   *
   * <p>Note that all Skyframe nodes between the currently executing (failed) action's node and the
   * nodes corresponding to the actions which create the lost inputs, inclusive, must be restarted.
   * This ensures that reevaluating the current node will also reevaluate the nodes that will
   * recreate the lost inputs.
   *
   * @throws ActionExecutionFunctionException if any lost inputs are not the outputs of previously
   *     executed actions
   */
  RewindPlan getRewindPlan(
      Action failedAction,
      Iterable<SkyKey> failedActionDeps,
      LostInputsActionExecutionException lostInputsException,
      ActionInputDepOwners runfilesDepOwners,
      Environment env)
      throws ActionExecutionFunctionException, InterruptedException {
    ImmutableList<ActionInput> lostInputs = lostInputsException.getLostInputs();

    // This collection tracks which Skyframe nodes must be restarted.
    HashSet<SkyKey> depsToRestart = new HashSet<>();

    // SkyframeActionExecutor must re-execute the actions being restarted, so we must tell it to
    // evict its cached results for those actions. This collection tracks those actions.
    ImmutableList.Builder<Action> actionsToRestart = ImmutableList.builder();
    actionsToRestart.add(failedAction);

    HashMultimap<Artifact, ActionInput> lostInputsByDepOwners =
        getLostInputsByDepOwners(
            lostInputs,
            lostInputsException.getInputOwners(),
            runfilesDepOwners,
            ImmutableSet.copyOf(failedActionDeps),
            failedAction);

    for (Map.Entry<Artifact, Collection<ActionInput>> entry :
        lostInputsByDepOwners.asMap().entrySet()) {
      Artifact lostArtifact = entry.getKey();

      if (lostArtifact.isSourceArtifact()) {
        // Rewinding source artifacts is not possible. They should not be losable, but we tolerate
        // their loss--by failing the build instead of crashing--in case some kind of infrastructure
        // failure results in their apparent loss.
        logger.info(
            String.format(
                "lostArtifact unexpectedly source.\nlostArtifact: %s\nlostInputs for artifact: %s\n"
                    + "failedAction: %s",
                lostArtifact, entry.getValue(), failedAction));
        throw new ActionExecutionFunctionException(
            new AlreadyReportedActionExecutionException(lostInputsException));
      }

      // Note that this artifact must be restarted.
      depsToRestart.add(lostArtifact);

      Map<ActionLookupData, Action> actionMap = getActionsForLostArtifact(lostArtifact, env);
      if (actionMap == null) {
        // Some deps of the artifact are not done. Another rewind must be in-flight, and there is no
        // need to restart the shared deps twice.
        continue;
      }
      ImmutableList<Action> actionsToCheckForPropagation =
          noteDepsAndActionsToRestartAndGetActionsToCheckForPropagation(
              actionMap, depsToRestart, actionsToRestart);
      recurseAcrossPropagatingActions(
          actionsToCheckForPropagation, env, depsToRestart, actionsToRestart);
    }

    return new RewindPlan(
        Restart.selfAnd(ImmutableList.copyOf(depsToRestart)), actionsToRestart.build());
  }

  private ImmutableList<Action> noteDepsAndActionsToRestartAndGetActionsToCheckForPropagation(
      Map<ActionLookupData, Action> actionMap,
      Set<SkyKey> depsToRestart,
      ImmutableList.Builder<Action> actionsToRestart) {
    ImmutableList.Builder<Action> actionsToCheckForPropagation =
        ImmutableList.builderWithExpectedSize(actionMap.size());
    for (Map.Entry<ActionLookupData, Action> actionEntry : actionMap.entrySet()) {
      if (depsToRestart.add(actionEntry.getKey())) {
        Action action = actionEntry.getValue();
        actionsToRestart.add(action);
        actionsToCheckForPropagation.add(action);
      }
    }
    return actionsToCheckForPropagation.build();
  }

  private HashMultimap<Artifact, ActionInput> getLostInputsByDepOwners(
      ImmutableList<ActionInput> lostInputs,
      LostInputsExecException.InputOwners inputOwners,
      ActionInputDepOwners runfilesDepOwners,
      ImmutableSet<SkyKey> failedActionDeps,
      Action failedActionForLogging) {

    HashMultimap<Artifact, ActionInput> lostInputsByDepOwners = HashMultimap.create();
    for (ActionInput lostInput : lostInputs) {
      if (failedActionDeps.contains(lostInput)) {
        Preconditions.checkState(
            lostInput instanceof Artifact,
            "unexpected non-artifact lostInput which is a dep of the current action.\n"
                + "lostInput: %s\nfailedAction: %s",
            lostInput,
            failedActionForLogging);
        lostInputsByDepOwners.put((Artifact) lostInput, lostInput);
        continue;
      }

      Artifact owner = inputOwners.getOwner(lostInput);
      if (owner != null && failedActionDeps.contains(owner)) {
        // The lost input is included in a tree artifact or fileset that the action directly depends
        // on.
        lostInputsByDepOwners.put(owner, lostInput);
        continue;
      }

      Artifact runfilesDepOwner = runfilesDepOwners.getDepOwner(lostInput);
      if (runfilesDepOwner != null && failedActionDeps.contains(runfilesDepOwner)) {
        // The lost input is included in a runfiles middleman that the action directly depends on.
        lostInputsByDepOwners.put(runfilesDepOwner, lostInput);
        continue;
      }

      Artifact runfilesDepTransitiveOwner = null;
      if (owner != null) {
        runfilesDepTransitiveOwner = runfilesDepOwners.getDepOwner(owner);
        if (runfilesDepTransitiveOwner != null
            && failedActionDeps.contains(runfilesDepTransitiveOwner)) {
          // The lost input is included in a tree artifact or fileset which is included in a
          // runfiles middleman that the action directly depends on.
          lostInputsByDepOwners.put(runfilesDepTransitiveOwner, lostInput);
          continue;
        }
      }

      // Rewinding can't do anything about a lost input that can't be associated with a direct dep
      // of the failed action. This may happen if the action consists of a sequence of spawns where
      // an output generated by one spawn is consumed by another but was lost in-between. In this
      // case, reevaluating the failed action (and no other deps) may help, because doing so may
      // rerun the generating spawn.
      //
      // It may also happen because of a bug, so we log that this has occurred.
      logger.info(
          String.format(
              "lostInput not a dep of the failed action, and can't be associated with such a dep.\n"
                  + "lostInput: %s\nowner: %s\nrunfilesDepOwner: %s\nrunfilesDepTransitiveOwner: %s"
                  + "\nfailedAction: %s",
              lostInput,
              owner,
              runfilesDepOwner,
              runfilesDepTransitiveOwner,
              failedActionForLogging));
    }
    return lostInputsByDepOwners;
  }

  private void recurseAcrossPropagatingActions(
      ImmutableList<Action> actionsToCheckForPropagation,
      Environment env,
      HashSet<SkyKey> depsToRestart,
      ImmutableList.Builder<Action> actionsToRestart)
      throws InterruptedException {
    ArrayDeque<Action> possiblyPropagatingActions = new ArrayDeque<>(actionsToCheckForPropagation);
    while (!possiblyPropagatingActions.isEmpty()) {
      Action action = possiblyPropagatingActions.removeFirst();

      if (!action.mayInsensitivelyPropagateInputs()) {
        continue;
      }
      // Restarting this action is insufficient. Doing so will not recreate the missing input.
      // We need to also restart this action's non-source inputs and the actions which created
      // those inputs.
      //
      // Note that the artifacts returned by Action#getAllowedDerivedInputs do not need to be
      // considered because none of the actions which provide non-throwing implementations of
      // getAllowedDerivedInputs "insensitively propagate inputs".
      Iterable<Artifact> inputs = action.getInputs();
      for (Artifact input : inputs) {
        if (input.isSourceArtifact()) {
          continue;
        }
        // Restarting all derived inputs of propagating actions is overkill. Preferably, we'd want
        // to only restart the inputs which correspond to the known lost outputs. The information
        // to do this is probably present in the ActionInputs contained in getRewindPlan's
        // lostInputsByOwners.
        //
        // Rewinding is expected to be rare, so refining this may not be necessary.
        depsToRestart.add(input);
        Map<ActionLookupData, Action> actionMap = getActionsForLostArtifact(input, env);
        if (actionMap == null) {
          continue;
        }
        ImmutableList<Action> nextActionsToCheckForPropagation =
            noteDepsAndActionsToRestartAndGetActionsToCheckForPropagation(
                actionMap, depsToRestart, actionsToRestart);
        possiblyPropagatingActions.addAll(nextActionsToCheckForPropagation);
      }
    }
  }

  @Nullable
  private Map<ActionLookupData, Action> getActionsForLostArtifact(
      Artifact lostInput, Environment env) throws InterruptedException {
    Set<ActionLookupData> actionExecutionDeps = getActionExecutionDeps(lostInput, env);
    if (actionExecutionDeps == null) {
      return null;
    }

    Map<ActionLookupData, Action> actions =
        Maps.newHashMapWithExpectedSize(actionExecutionDeps.size());
    for (ActionLookupData dep : actionExecutionDeps) {
      actions.put(dep, ActionExecutionFunction.getActionForLookupData(env, dep));
    }
    return actions;
  }

  /**
   * Returns the set of {@code lostInput}'s execution-phase dependencies, or {@code null} if any of
   * those dependencies are not done.
   */
  @Nullable
  private Set<ActionLookupData> getActionExecutionDeps(Artifact lostInput, Environment env)
      throws InterruptedException {
    ArtifactFunction.ArtifactDependencies artifactDependencies =
        ArtifactFunction.ArtifactDependencies.discoverDependencies(lostInput, env);
    if (artifactDependencies == null) {
      return null;
    }

    if (artifactDependencies.isTemplateActionForTreeArtifact()) {
      ArtifactFunction.ActionTemplateExpansion actionTemplateExpansion =
          artifactDependencies.getActionTemplateExpansion(env);
      if (actionTemplateExpansion == null) {
        return null;
      }
      // This ignores the ActionTemplateExpansionKey dependency of the template artifact because we
      // expect to never need to rewind that.
      return ImmutableSet.copyOf(actionTemplateExpansion.getExpandedActionExecutionKeys());
    }

    return ImmutableSet.of(artifactDependencies.getNontemplateActionExecutionKey());
  }

  static class RewindPlan {
    private final Restart nodesToRestart;
    private final ImmutableList<Action> actionsToRestart;

    RewindPlan(Restart nodesToRestart, ImmutableList<Action> actionsToRestart) {
      this.nodesToRestart = nodesToRestart;
      this.actionsToRestart = actionsToRestart;
    }

    Restart getNodesToRestart() {
      return nodesToRestart;
    }

    ImmutableList<Action> getActionsToRestart() {
      return actionsToRestart;
    }
  }
}