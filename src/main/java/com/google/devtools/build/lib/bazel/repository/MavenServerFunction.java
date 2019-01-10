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

package com.google.devtools.build.lib.bazel.repository;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.actions.FileValue;
import com.google.devtools.build.lib.analysis.BlazeDirectories;
import com.google.devtools.build.lib.bazel.rules.workspace.MavenServerRule;
import com.google.devtools.build.lib.packages.Rule;
import com.google.devtools.build.lib.repository.ExternalPackageException;
import com.google.devtools.build.lib.repository.ExternalPackageUtil;
import com.google.devtools.build.lib.repository.ExternalRuleNotFoundException;
import com.google.devtools.build.lib.rules.repository.RepositoryFunction.RepositoryFunctionException;
import com.google.devtools.build.lib.rules.repository.WorkspaceAttributeMapper;
import com.google.devtools.build.lib.syntax.EvalException;
import com.google.devtools.build.lib.syntax.Type;
import com.google.devtools.build.lib.util.Fingerprint;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.Root;
import com.google.devtools.build.lib.vfs.RootedPath;
import com.google.devtools.build.skyframe.SkyFunction;
import com.google.devtools.build.skyframe.SkyFunctionException.Transience;
import com.google.devtools.build.skyframe.SkyFunctionName;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;
import java.io.IOException;
import java.util.Arrays;
import java.util.Map;
import javax.annotation.Nullable;
import org.apache.maven.settings.Server;
import org.apache.maven.settings.Settings;
import org.apache.maven.settings.building.DefaultSettingsBuilder;
import org.apache.maven.settings.building.DefaultSettingsBuilderFactory;
import org.apache.maven.settings.building.DefaultSettingsBuildingRequest;
import org.apache.maven.settings.building.SettingsBuildingException;
import org.apache.maven.settings.building.SettingsBuildingResult;

/**
 * Implementation of maven_repository.
 */
public class MavenServerFunction implements SkyFunction {
  public static final SkyFunctionName NAME =
      SkyFunctionName.createHermetic("MAVEN_SERVER_FUNCTION");

  private static final String USER_KEY = "user";
  private static final String SYSTEM_KEY = "system";

  private final BlazeDirectories directories;

  public MavenServerFunction(BlazeDirectories directories) {
    this.directories = directories;
  }

  @Nullable
  @Override
  public SkyValue compute(SkyKey skyKey, Environment env)
      throws InterruptedException, RepositoryFunctionException, ExternalPackageException {
    String repository = (String) skyKey.argument();
    Rule repositoryRule = null;
    try {
      repositoryRule = ExternalPackageUtil.getRuleByName(repository, env);
    } catch (ExternalRuleNotFoundException ex) {
      // Ignored. We throw a new one below.
    }
    if (env.valuesMissing()) {
      return null;
    }
    String serverName;
    String url;
    Map<String, FileValue> settingsFiles;
    boolean foundRepoRule = repositoryRule != null
        && repositoryRule.getRuleClass().equals(MavenServerRule.NAME);
    if (!foundRepoRule) {
      if (repository.equals(MavenServerValue.DEFAULT_ID)) {
        settingsFiles = getDefaultSettingsFile(directories, env);
        serverName = MavenServerValue.DEFAULT_ID;
        url = MavenConnector.getMavenCentralRemote().getUrl();
      } else {
        throw new RepositoryFunctionException(
            new IOException("Could not find maven repository " + repository),
            Transience.TRANSIENT);
      }
    } else {
      WorkspaceAttributeMapper mapper = WorkspaceAttributeMapper.of(repositoryRule);
      serverName = repositoryRule.getName();
      try {
        url = mapper.get("url", Type.STRING);
        if (!mapper.isAttributeValueExplicitlySpecified("settings_file")) {
          settingsFiles = getDefaultSettingsFile(directories, env);
        } else {
          PathFragment settingsFilePath =
              PathFragment.create(mapper.get("settings_file", Type.STRING));
          RootedPath settingsPath =
              RootedPath.toRootedPath(
                  Root.fromPath(directories.getWorkspace().getRelative(settingsFilePath)),
                  PathFragment.EMPTY_FRAGMENT);
          FileValue fileValue = (FileValue) env.getValue(FileValue.key(settingsPath));
          if (fileValue == null) {
            return null;
          }

          if (!fileValue.exists()) {
            throw new RepositoryFunctionException(
                new IOException("Could not find settings file " + settingsPath),
                Transience.TRANSIENT);
          }
          settingsFiles = ImmutableMap.<String, FileValue>builder().put(
              USER_KEY, fileValue).build();
        }
      } catch (EvalException e) {
        throw new RepositoryFunctionException(e, Transience.PERSISTENT);
      }
    }

    if (settingsFiles == null) {
      return null;
    }

    Fingerprint fingerprint = new Fingerprint();
    try {
      for (Map.Entry<String, FileValue> entry : settingsFiles.entrySet()) {
        fingerprint.addString(entry.getKey());
        Path path = entry.getValue().realRootedPath().asPath();
        if (path.exists()) {
          fingerprint.addBoolean(true);
          fingerprint.addBytes(path.getDigest());
        } else {
          fingerprint.addBoolean(false);
        }
      }
    } catch (IOException e) {
      throw new RepositoryFunctionException(e, Transience.TRANSIENT);
    }

    byte[] fingerprintBytes = fingerprint.digestAndReset();

    if (settingsFiles.isEmpty()) {
      return new MavenServerValue(serverName, url, new Server(), fingerprintBytes);
    }

    DefaultSettingsBuildingRequest request = new DefaultSettingsBuildingRequest();
    if (settingsFiles.containsKey(SYSTEM_KEY)) {
      request.setGlobalSettingsFile(
          settingsFiles.get(SYSTEM_KEY).realRootedPath().asPath().getPathFile());
    }
    if (settingsFiles.containsKey(USER_KEY)) {
      request.setUserSettingsFile(
          settingsFiles.get(USER_KEY).realRootedPath().asPath().getPathFile());
    }
    DefaultSettingsBuilder builder = (new DefaultSettingsBuilderFactory()).newInstance();
    SettingsBuildingResult result;
    try {
      result = builder.build(request);
    } catch (SettingsBuildingException e) {
      throw new RepositoryFunctionException(
          new IOException("Error parsing settings files: " + e.getMessage()),
          Transience.TRANSIENT);
    }
    if (!result.getProblems().isEmpty()) {
      throw new RepositoryFunctionException(
          new IOException("Errors interpreting settings file: "
              + Arrays.toString(result.getProblems().toArray())), Transience.PERSISTENT);
    }
    Settings settings = result.getEffectiveSettings();
    Server server = settings.getServer(serverName);
    server = server == null ? new Server() : server;
    return new MavenServerValue(serverName, url, server, fingerprintBytes);
  }

  private Map<String, FileValue> getDefaultSettingsFile(
      BlazeDirectories directories, Environment env) throws InterruptedException {
    // The system settings file is at $M2_HOME/conf/settings.xml.
    String m2Home = System.getenv("M2_HOME");
    ImmutableList.Builder<SkyKey> settingsFilesBuilder = ImmutableList.builder();
    SkyKey systemKey = null;
    if (m2Home != null) {
      PathFragment mavenInstallSettings =
          PathFragment.create(m2Home).getRelative("conf/settings.xml");
      systemKey =
          FileValue.key(
              RootedPath.toRootedPath(
                  Root.fromPath(directories.getWorkspace().getRelative(mavenInstallSettings)),
                  PathFragment.EMPTY_FRAGMENT));
      settingsFilesBuilder.add(systemKey);
    }

    // The user settings file is at $HOME/.m2/settings.xml.
    String userHome = System.getenv("HOME");
    SkyKey userKey = null;
    if (userHome != null) {
      PathFragment userSettings = PathFragment.create(userHome).getRelative(".m2/settings.xml");
      userKey =
          FileValue.key(
              RootedPath.toRootedPath(
                  Root.fromPath(directories.getWorkspace().getRelative(userSettings)),
                  PathFragment.EMPTY_FRAGMENT));
      settingsFilesBuilder.add(userKey);
    }

    ImmutableList<SkyKey> settingsFiles = settingsFilesBuilder.build();
    if (settingsFiles.isEmpty()) {
      return ImmutableMap.of();
    }
    Map<SkyKey, SkyValue> values = env.getValues(settingsFiles);
    ImmutableMap.Builder<String, FileValue> settingsBuilder = ImmutableMap.builder();
    for (Map.Entry<SkyKey, SkyValue> entry : values.entrySet()) {
      if (entry.getValue() == null) {
        return null;
      }
      if (systemKey != null && systemKey.equals(entry.getKey())) {
        settingsBuilder.put(SYSTEM_KEY, (FileValue) entry.getValue());
      } else if (userKey != null && userKey.equals(entry.getKey())) {
        settingsBuilder.put(USER_KEY, (FileValue) entry.getValue());
      }
    }
    return settingsBuilder.build();
  }

  @Nullable
  @Override
  public String extractTag(SkyKey skyKey) {
    return null;
  }
}
