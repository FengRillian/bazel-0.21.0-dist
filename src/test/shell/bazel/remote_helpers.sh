#!/bin/bash
#
# Copyright 2015 The Bazel Authors. All rights reserved.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

set -eu

case "${PLATFORM}" in
  darwin|freebsd)
    function nc_l() {
      nc -l $1
    }
    ;;
  *)
    function nc_l() {
      nc -l -p $1 -q 1
    }
    ;;
esac

# Serves $1 as a file on localhost:$nc_port.  Sets the following variables:
#   * nc_port - the port nc is listening on.
#   * nc_log - the path to nc's log.
#   * nc_pid - the PID of nc.
#   * http_response - the full response nc will provide to a request.
# This also creates the file $TEST_TMPDIR/http_response.
function serve_file() {
  http_response=$TEST_TMPDIR/http_response
  cat > $http_response <<EOF
HTTP/1.0 200 OK

EOF
  cat $1 >> $http_response
  # Assign random_port to nc_port if not already set.
  echo ${nc_port:=$(pick_random_unused_tcp_port)} > /dev/null
  nc_log=$TEST_TMPDIR/nc.log
  nc_l $nc_port < $http_response >& $nc_log &
  nc_pid=$!
}

# Creates a jar carnivore.Mongoose and serves it using serve_file.
function serve_jar() {
  make_test_jar
  serve_file $test_jar
  cd ${WORKSPACE_DIR}
}

function make_test_jar() {
  pkg_dir=$TEST_TMPDIR/carnivore
  rm -fr $pkg_dir
  mkdir -p $pkg_dir
  cat > $pkg_dir/Mongoose.java <<EOF
package carnivore;
public class Mongoose {
    public static void frolic() {
        System.out.println("Tra-la!");
    }
}
EOF
  ${bazel_javabase}/bin/javac $pkg_dir/Mongoose.java
  test_jar=$TEST_TMPDIR/libcarnivore.jar
  test_srcjar=$TEST_TMPDIR/libcarnivore-sources.jar
  cd ${TEST_TMPDIR}
  ${bazel_javabase}/bin/jar cf $test_jar carnivore/Mongoose.class
  ${bazel_javabase}/bin/jar cf $test_srcjar carnivore/Mongoose.java
  sha256=$(sha256sum $test_jar | cut -f 1 -d ' ')
  # OS X doesn't have sha1sum, so use openssl.
  sha1=$(openssl sha1 $test_jar | cut -f 2 -d ' ')
  sha1_src=$(openssl sha1 $test_srcjar | cut -f 2 -d ' ')
  cd -
}

function make_test_aar() {
  test_aar=${TEST_TMPDIR}/example.aar
  cd ${TEST_TMPDIR}
  cat > AndroidManifest.xml <<EOF
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="com.example" >
    <uses-sdk
        android:minSdkVersion="9"
        android:targetSdkVersion="20" />
    <application />
</manifest>
EOF
  mkdir -p com/herbivore
  cat > com/herbivore/Stegosaurus.java <<EOF
package com.herbivore;
class Stegosaurus {}
EOF
  ${bazel_javabase}/bin/javac -source 7 -target 7 com/herbivore/Stegosaurus.java
  ${bazel_javabase}/bin/jar cf0 classes.jar com/herbivore/Stegosaurus.class
  mkdir -p res/layout
  cat > res/layout/my_view.xml <<EOF
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout />
EOF
  zip -0 $test_aar AndroidManifest.xml classes.jar res/layout/my_view.xml
  sha256=$(sha256sum $test_aar | cut -f 1 -d ' ')
  # OS X doesn't have sha1sum, so use openssl.
  sha1=$(openssl sha1 $test_aar | cut -f 2 -d ' ')
  cd -
}

# Serves a redirection from localhost:$redirect_port to $1. Sets the following variables:
#   * redirect_port - the port nc is listening on.
#   * redirect_log - the path to nc's log.
#   * redirect_pid - the PID of nc.
function serve_redirect() {
  # Assign random_port to nc_port if not already set.
  echo ${redirect_port:=$(pick_random_unused_tcp_port)} > /dev/null
  redirect_log=$TEST_TMPDIR/redirect.log
  local response=$(cat <<EOF
HTTP/1.0 301 Moved Permanently
Location: $1

EOF
)
  nc_l $redirect_port >& $redirect_log <<<"$response" &
  redirect_pid=$!
}

# Serves a HTTP 404 Not Found response with an optional parameter for the
# response body.
function serve_not_found() {
  RESPONSE_BODY=${1:-}
  http_response=$TEST_TMPDIR/http_response
  cat > $http_response <<EOF
HTTP/1.0 404 Not Found

$RESPONSE_BODY
EOF
  nc_port=$(pick_random_unused_tcp_port) || exit 1
  nc_l $nc_port < $http_response &
  nc_pid=$!
}

# Waits for the SimpleHTTPServer to actually start up before the test is run.
# Otherwise the entire test can run before the server starts listening for
# connections, which causes flakes.
function wait_for_server_startup() {
  touch some-file
  while ! curl localhost:$fileserver_port/some-file; do
    echo "waiting for server, exit code: $?"
    sleep 1
  done
  echo "done waiting for server, exit code: $?"
  rm some-file
}


function create_artifact() {
  local group_id=$1
  local artifact_id=$2
  local version=$3
  local packaging=${4:-jar}
  # TODO(davido): This is unused for now.
  # Finalize the implementation once the underlying tests are fixed.
  local classifier=${5:-jar}
  if [ $packaging == "aar" ]; then
    make_test_aar
    local artifact=$test_aar
  else
    make_test_jar
    local artifact=$test_jar
    local srcjar_artifact=$test_srcjar
  fi
  maven_path=$PWD/$(echo $group_id | sed 's/\./\//g')/$artifact_id/$version
  mkdir -p $maven_path
  openssl sha1 $artifact > $maven_path/$artifact_id-$version.$packaging.sha1
  mv $artifact $maven_path/$artifact_id-$version.$packaging

  # srcjar_artifact is not created for AARs.
  if [ ! -z "${srcjar_artifact+x}" ]; then
    openssl sha1 $srcjar_artifact > $maven_path/$artifact_id-$version-sources.$packaging.sha1
    mv $srcjar_artifact $maven_path/$artifact_id-$version-sources.$packaging
  fi
}

function serve_artifact() {
  startup_server $PWD
  create_artifact $1 $2 $3 ${4:-jar}
}

function startup_server() {
  fileserver_root=$1
  cd $fileserver_root
  fileserver_port=$(pick_random_unused_tcp_port) || exit 1
  python $python_server $fileserver_port &
  fileserver_pid=$!
  wait_for_server_startup
  cd -
}

function startup_auth_server() {
  fileserver_port=$(pick_random_unused_tcp_port) || exit 1
  python $python_server $fileserver_port auth &
  fileserver_pid=$!
  wait_for_server_startup
}

function shutdown_server() {
  # Try to kill nc, otherwise the test will time out if Bazel has a bug and
  # didn't make a request to it.
  [ -z "${fileserver_pid:-}" ] || kill $fileserver_pid || true
  [ -z "${redirect_pid:-}" ] || kill $redirect_pid || true
  [ -z "${nc_log:-}" ] || cat $nc_log
  [ -z "${redirect_log:-}" ] || cat $redirect_log
}

function kill_nc() {
  shutdown_server
}
