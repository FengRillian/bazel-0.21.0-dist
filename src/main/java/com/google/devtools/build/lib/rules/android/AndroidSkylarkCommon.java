// Copyright 2016 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.rules.android;

import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.skylarkbuildapi.android.AndroidSkylarkCommonApi;
import com.google.devtools.build.lib.skylarkbuildapi.android.AndroidSplitTransititionApi;
import com.google.devtools.build.lib.vfs.PathFragment;

/** Common utilities for Skylark rules related to Android. */
public class AndroidSkylarkCommon implements AndroidSkylarkCommonApi<Artifact> {

  @Override
  public AndroidDeviceBrokerInfo createDeviceBrokerInfo(String deviceBrokerType) {
    return new AndroidDeviceBrokerInfo(deviceBrokerType);
  }

  @Override
  public PathFragment getSourceDirectoryRelativePathFromResource(Artifact resource) {
    return AndroidCommon.getSourceDirectoryRelativePathFromResource(resource);
  }

  @Override
  public AndroidSplitTransititionApi getAndroidSplitTransition() {
    return AndroidRuleClasses.ANDROID_SPLIT_TRANSITION;
  }
}
