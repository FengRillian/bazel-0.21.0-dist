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

package com.google.devtools.build.lib.sandbox;

import build.bazel.remote.execution.v2.Platform;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.MoreCollectors;
import com.google.common.eventbus.Subscribe;
import com.google.common.io.ByteStreams;
import com.google.devtools.build.lib.actions.ExecException;
import com.google.devtools.build.lib.actions.Spawn;
import com.google.devtools.build.lib.actions.SpawnResult;
import com.google.devtools.build.lib.actions.Spawns;
import com.google.devtools.build.lib.actions.UserExecException;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.Reporter;
import com.google.devtools.build.lib.exec.local.LocalEnvProvider;
import com.google.devtools.build.lib.exec.local.PosixLocalEnvProvider;
import com.google.devtools.build.lib.runtime.CommandCompleteEvent;
import com.google.devtools.build.lib.runtime.CommandEnvironment;
import com.google.devtools.build.lib.runtime.ProcessWrapperUtil;
import com.google.devtools.build.lib.sandbox.SandboxHelpers.SandboxOutputs;
import com.google.devtools.build.lib.shell.Command;
import com.google.devtools.build.lib.shell.CommandException;
import com.google.devtools.build.lib.util.OS;
import com.google.devtools.build.lib.util.ProcessUtils;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.protobuf.TextFormat;
import com.google.protobuf.TextFormat.ParseException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Spawn runner that uses Docker to execute a local subprocess. */
final class DockerSandboxedSpawnRunner extends AbstractSandboxSpawnRunner {

  // The name of the container image entry in the Platform proto
  // (see third_party/googleapis/devtools/remoteexecution/*/remote_execution.proto and
  // experimental_remote_platform_override in
  // src/main/java/com/google/devtools/build/lib/remote/RemoteOptions.java)
  private static final String CONTAINER_IMAGE_ENTRY_NAME = "container-image";
  private static final String DOCKER_IMAGE_PREFIX = "docker://";

  /**
   * Returns whether the darwin sandbox is supported on the local machine by running docker info.
   * This is expensive, and we have also reports of docker hanging for a long time!
   */
  public static boolean isSupported(CommandEnvironment cmdEnv, Path dockerClient) {
    boolean verbose = cmdEnv.getOptions().getOptions(SandboxOptions.class).dockerVerbose;

    if (!ProcessWrapperUtil.isSupported(cmdEnv)) {
      if (verbose) {
        cmdEnv
            .getReporter()
            .handle(
                Event.error(
                    "Docker sandboxing is disabled, because ProcessWrapperUtil.isSupported "
                        + "returned false. This should never happen - is your Bazel binary "
                        + "corrupted?"));
      }
      return false;
    }

    // On Linux we need to know the UID and GID that we're running as, because otherwise Docker will
    // create files as 'root' and we can't move them to the execRoot.
    if (OS.getCurrent() == OS.LINUX) {
      try {
        ProcessUtils.getuid();
        ProcessUtils.getgid();
      } catch (UnsatisfiedLinkError e) {
        if (verbose) {
          cmdEnv
              .getReporter()
              .handle(
                  Event.error(
                      "Docker sandboxing is disabled, because ProcessUtils.getuid/getgid threw an "
                          + "UnsatisfiedLinkError. This means that you're running a Bazel version "
                          + "that doesn't have JNI libraries - did you build it correctly?\n"
                          + Throwables.getStackTraceAsString(e)));
        }
        return false;
      }
    }

    Command cmd =
        new Command(
            new String[] {dockerClient.getPathString(), "info"},
            cmdEnv.getClientEnv(),
            cmdEnv.getExecRoot().getPathFile());
    try {
      cmd.execute(ByteStreams.nullOutputStream(), ByteStreams.nullOutputStream());
    } catch (CommandException e) {
      if (verbose) {
        cmdEnv
            .getReporter()
            .handle(
                Event.error(
                    "Docker sandboxing is disabled, because running 'docker info' failed: "
                        + Throwables.getStackTraceAsString(e)));
      }
      return false;
    }

    if (verbose) {
      cmdEnv.getReporter().handle(Event.info("Docker sandboxing is supported"));
    }

    return true;
  }

  private static final ConcurrentHashMap<String, String> imageMap = new ConcurrentHashMap<>();

  private final Path execRoot;
  private final boolean allowNetwork;
  private final Path dockerClient;
  private final Path processWrapper;
  private final Path sandboxBase;
  private final String defaultImage;
  private final LocalEnvProvider localEnvProvider;
  private final Duration timeoutKillDelay;
  private final String commandId;
  private final Reporter reporter;
  private final boolean useCustomizedImages;
  private final int uid;
  private final int gid;
  private final List<UUID> containersToCleanup;
  private final CommandEnvironment cmdEnv;

  /**
   * Creates a sandboxed spawn runner that uses the {@code linux-sandbox} tool.
   *
   * @param cmdEnv the command environment to use
   * @param dockerClient path to the `docker` executable
   * @param sandboxBase path to the sandbox base directory
   * @param defaultImage the Docker image to use if the platform doesn't specify one
   * @param timeoutKillDelay an additional grace period before killing timing out commands
   * @param useCustomizedImages whether to use customized images for execution
   */
  DockerSandboxedSpawnRunner(
      CommandEnvironment cmdEnv,
      Path dockerClient,
      Path sandboxBase,
      String defaultImage,
      Duration timeoutKillDelay,
      boolean useCustomizedImages) {
    super(cmdEnv);
    this.execRoot = cmdEnv.getExecRoot();
    this.allowNetwork = SandboxHelpers.shouldAllowNetwork(cmdEnv.getOptions());
    this.dockerClient = dockerClient;
    this.processWrapper = ProcessWrapperUtil.getProcessWrapper(cmdEnv);
    this.sandboxBase = sandboxBase;
    this.defaultImage = defaultImage;
    this.localEnvProvider = new PosixLocalEnvProvider(cmdEnv.getClientEnv());
    this.timeoutKillDelay = timeoutKillDelay;
    this.commandId = cmdEnv.getCommandId().toString();
    this.reporter = cmdEnv.getReporter();
    this.useCustomizedImages = useCustomizedImages;
    this.cmdEnv = cmdEnv;
    if (OS.getCurrent() == OS.LINUX) {
      this.uid = ProcessUtils.getuid();
      this.gid = ProcessUtils.getgid();
    } else {
      this.uid = -1;
      this.gid = -1;
    }
    this.containersToCleanup = Collections.synchronizedList(new ArrayList<>());

    cmdEnv.getEventBus().register(this);
  }

  @Override
  protected SpawnResult actuallyExec(Spawn spawn, SpawnExecutionContext context)
      throws IOException, ExecException, InterruptedException {
    // Each invocation of "exec" gets its own sandbox base, execroot and temporary directory.
    Path sandboxPath =
        sandboxBase.getRelative(getName()).getRelative(Integer.toString(context.getId()));
    sandboxPath.getParentDirectory().createDirectory();
    sandboxPath.createDirectory();

    // b/64689608: The execroot of the sandboxed process must end with the workspace name, just like
    // the normal execroot does.
    Path sandboxExecRoot = sandboxPath.getRelative("execroot").getRelative(execRoot.getBaseName());
    sandboxExecRoot.getParentDirectory().createDirectory();
    sandboxExecRoot.createDirectory();

    Map<String, String> environment =
        localEnvProvider.rewriteLocalEnv(spawn.getEnvironment(), binTools, "/tmp");

    SandboxOutputs outputs = SandboxHelpers.getOutputs(spawn);
    Duration timeout = context.getTimeout();

    UUID uuid = UUID.randomUUID();

    String baseImageName = dockerContainerFromSpawn(spawn).orElse(this.defaultImage);
    if (baseImageName.isEmpty()) {
      throw new UserExecException(
          String.format(
              "Cannot execute %s mnemonic with Docker, because no "
                  + "image could be found in the remote_execution_properties of the platform and "
                  + "no default image was set via --experimental_docker_image",
              spawn.getMnemonic()));
    }

    String customizedImageName = getOrCreateCustomizedImage(baseImageName);
    if (customizedImageName == null) {
      throw new UserExecException("Could not prepare Docker image for execution");
    }

    DockerCommandLineBuilder cmdLine = new DockerCommandLineBuilder();
    cmdLine
        .setProcessWrapper(processWrapper)
        .setDockerClient(dockerClient)
        .setImageName(customizedImageName)
        .setCommandArguments(spawn.getArguments())
        .setSandboxExecRoot(sandboxExecRoot)
        .setPrivileged(getSandboxOptions().dockerPrivileged)
        .setEnvironmentVariables(environment)
        .setKillDelay(timeoutKillDelay)
        .setCreateNetworkNamespace(
            !(allowNetwork
                || Spawns.requiresNetwork(spawn, getSandboxOptions().defaultSandboxAllowNetwork)))
        .setCommandId(commandId)
        .setUuid(uuid);
    // If uid / gid are -1, we are on an operating system that doesn't require us to set them on the
    // Docker invocation. If they're 0, it means we are running as root and don't need to set them.
    if (uid > 0) {
      cmdLine.setUid(uid);
    }
    if (gid > 0) {
      cmdLine.setGid(gid);
    }
    if (!timeout.isZero()) {
      cmdLine.setTimeout(timeout);
    }

    SandboxedSpawn sandbox =
        new CopyingSandboxedSpawn(
            sandboxPath,
            sandboxExecRoot,
            cmdLine.build(),
            cmdEnv.getClientEnv(),
            SandboxHelpers.processInputFiles(
                spawn,
                context,
                execRoot,
                getSandboxOptions().symlinkedSandboxExpandsTreeArtifactsInRunfilesTree),
            outputs,
            ImmutableSet.of());

    try {
      return runSpawn(spawn, sandbox, context, execRoot, timeout, null);
    } catch (InterruptedException e) {
      // If we were interrupted, it is possible that "docker run" gets killed in exactly the moment
      // between the create and the start call, leaving behind a container that is created but never
      // ran. This means that Docker won't automatically clean it up (as --rm only affects the start
      // phase and has no effect on the create phase of "docker run").
      // We add the container UUID to a list and clean them up after the execution is over.
      containersToCleanup.add(uuid);
      throw e;
    }
  }

  private String getOrCreateCustomizedImage(String baseImage) {
    // TODO(philwo) docker run implicitly does a docker pull if the image does not exist locally.
    // Pulling an image can take a long time and a user might not be aware of that. We could check
    // if the image exists locally (docker images -q name:tag) and if not, do a docker pull and
    // notify the user in a similar way as when we download a http_archive.
    //
    // This is mostly relevant for the case where we don't build a customized image, as that prints
    // a message when it runs.

    if (!useCustomizedImages) {
      return baseImage;
    }

    // If we're running as root, we can skip this step, as it's safe to assume that every image
    // already has a built-in root user and group.
    if (uid == 0 && gid == 0) {
      return baseImage;
    }

    // We only need to create a customized image, if we're running on Linux, as Docker on macOS
    // and Windows doesn't map users from the host into the container anyway.
    if (OS.getCurrent() != OS.LINUX) {
      return baseImage;
    }

    return imageMap.computeIfAbsent(
        baseImage,
        (image) -> {
          reporter.handle(Event.info("Preparing Docker image " + image + " for use..."));
          String workDir =
              PathFragment.create("/execroot").getRelative(execRoot.getBaseName()).getPathString();
          StringBuilder dockerfile = new StringBuilder();
          dockerfile.append(String.format("FROM %s\n", image));
          dockerfile.append(String.format("RUN [\"mkdir\", \"-p\", \"%s\"]\n", workDir));
          // TODO(philwo) this will fail if a user / group with the given uid / gid already exists
          // in the container. For now this seems reasonably unlikely, but we'll have to come up
          // with a better way.
          if (gid > 0) {
            dockerfile.append(
                String.format("RUN [\"groupadd\", \"-g\", \"%d\", \"bazelbuild\"]\n", gid));
          }
          if (uid > 0) {
            dockerfile.append(
                String.format(
                    "RUN [\"useradd\", \"-m\", \"-g\", \"%d\", \"-d\", \"%s\", \"-N\", \"-u\", "
                        + "\"%d\", \"bazelbuild\"]\n",
                    gid, workDir, uid));
          }
          dockerfile.append(
              String.format("RUN [\"chown\", \"-R\", \"%d:%d\", \"%s\"]\n", uid, gid, workDir));
          dockerfile.append(String.format("USER %d:%d\n", uid, gid));
          dockerfile.append(String.format("ENV HOME %s\n", workDir));
          if (uid > 0) {
            dockerfile.append(String.format("ENV USER bazelbuild\n"));
          }
          dockerfile.append(String.format("WORKDIR %s\n", workDir));
          try {
            return executeCommand(
                ImmutableList.of(dockerClient.getPathString(), "build", "-q", "-"),
                new ByteArrayInputStream(dockerfile.toString().getBytes(Charset.defaultCharset())));
          } catch (UserExecException e) {
            reporter.handle(Event.error(e.getMessage()));
            return null;
          }
        });
  }

  private String executeCommand(List<String> cmdLine, InputStream stdIn) throws UserExecException {
    ByteArrayOutputStream stdOut = new ByteArrayOutputStream();
    ByteArrayOutputStream stdErr = new ByteArrayOutputStream();

    // Docker might need the $HOME and $PATH variables in order to be able to use advanced
    // authentication mechanisms (e.g. for Google Cloud), thus we pass in the client env.
    Command cmd =
        new Command(cmdLine.toArray(new String[0]), cmdEnv.getClientEnv(), execRoot.getPathFile());
    try {
      cmd.executeAsync(stdIn, stdOut, stdErr, Command.KILL_SUBPROCESS_ON_INTERRUPT).get();
    } catch (CommandException e) {
      throw new UserExecException(
          "Running command " + cmd.toDebugString() + " failed: " + stdErr, e);
    }
    return stdOut.toString().trim();
  }

  private Optional<String> dockerContainerFromSpawn(Spawn spawn) {
    Platform platform = null;
    // TODO(philwo) Figure out if this is the right mechanism to specify a Docker image per action.
    String platformDescription = spawn.getExecutionPlatform().remoteExecutionProperties();
    if (platformDescription != null) {
      try {
        Platform.Builder platformBuilder = Platform.newBuilder();
        TextFormat.getParser().merge(platformDescription, platformBuilder);
        platform = platformBuilder.build();
      } catch (ParseException e) {
        throw new IllegalArgumentException(
            String.format(
                "Failed to parse remote_execution_properties from platform %s",
                spawn.getExecutionPlatform().label()),
            e);
      }
    }

    if (platform != null) {
      try {
        return platform
            .getPropertiesList()
            .stream()
            .filter(p -> p.getName().equals(CONTAINER_IMAGE_ENTRY_NAME))
            .map(p -> p.getValue())
            .filter(r -> r.startsWith(DOCKER_IMAGE_PREFIX))
            .map(r -> r.substring(DOCKER_IMAGE_PREFIX.length()))
            .collect(MoreCollectors.toOptional());
      } catch (IllegalArgumentException e) {
        throw new IllegalArgumentException(
            String.format(
                "Platform %s contained multiple container-image entries, but only one is allowed.",
                spawn.getExecutionPlatform().label()),
            e);
      }
    } else {
      return Optional.empty();
    }
  }

  // Remove all Docker containers that might be stuck in "Created" state and weren't automatically
  // cleaned up by Docker itself.
  public void cleanup() {
    if (containersToCleanup == null || containersToCleanup.isEmpty()) {
      return;
    }

    ArrayList<String> cmdLine = new ArrayList<>();
    cmdLine.add(dockerClient.getPathString());
    cmdLine.add("rm");
    cmdLine.add("-fv");
    for (UUID uuid : containersToCleanup) {
      cmdLine.add(uuid.toString());
    }

    Command cmd =
        new Command(cmdLine.toArray(new String[0]), cmdEnv.getClientEnv(), execRoot.getPathFile());

    try {
      cmd.execute();
    } catch (CommandException e) {
      // This is to be expected, as not all UUIDs that we pass to "docker rm" will still be alive
      // when this method is called. However, it will successfully remove all the containers that
      // *are* still there, even when the command exits with an error.
    }

    containersToCleanup.clear();
  }

  @Subscribe
  public void commandComplete(CommandCompleteEvent event) {
    cleanup();
  }

  @Override
  public String getName() {
    return "docker";
  }
}
