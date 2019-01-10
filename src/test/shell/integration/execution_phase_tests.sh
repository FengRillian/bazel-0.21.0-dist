#!/bin/bash
#
# Copyright 2017 The Bazel Authors. All rights reserved.
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
#
# execution_phase_tests.sh: miscellaneous integration tests of Bazel for
# behaviors that affect the execution phase.
#

# --- begin runfiles.bash initialization ---
set -euo pipefail
if [[ ! -d "${RUNFILES_DIR:-/dev/null}" && ! -f "${RUNFILES_MANIFEST_FILE:-/dev/null}" ]]; then
  if [[ -f "$TEST_SRCDIR/MANIFEST" ]]; then
    export RUNFILES_MANIFEST_FILE="$TEST_SRCDIR/MANIFEST"
  elif [[ -f "$0.runfiles/MANIFEST" ]]; then
    export RUNFILES_MANIFEST_FILE="$0.runfiles/MANIFEST"
  elif [[ -f "$TEST_SRCDIR/bazel_tools/tools/bash/runfiles/runfiles.bash" ]]; then
    export RUNFILES_DIR="$TEST_SRCDIR"
  fi
fi
if [[ -f "${RUNFILES_DIR:-/dev/null}/bazel_tools/tools/bash/runfiles/runfiles.bash" ]]; then
  source "${RUNFILES_DIR}/bazel_tools/tools/bash/runfiles/runfiles.bash"
elif [[ -f "${RUNFILES_MANIFEST_FILE:-/dev/null}" ]]; then
  source "$(grep -m1 "^bazel_tools/tools/bash/runfiles/runfiles.bash " \
            "$RUNFILES_MANIFEST_FILE" | cut -d ' ' -f 2-)"
else
  echo >&2 "ERROR: cannot find //third_party/bazel/tools/bash/runfiles:runfiles.bash"
  exit 1
fi
# --- end runfiles.bash initialization ---

source "$(rlocation "io_bazel/src/test/shell/integration_test_setup.sh")" \
  || { echo "integration_test_setup.sh not found!" >&2; exit 1; }

case "$(uname -s | tr [:upper:] [:lower:])" in
msys*|mingw*|cygwin*)
  declare -r is_windows=true
  ;;
*)
  declare -r is_windows=false
  ;;
esac

if "$is_windows"; then
  export MSYS_NO_PATHCONV=1
  export MSYS2_ARG_CONV_EXCL="*"
fi

#### HELPER FUNCTIONS ##################################################

function set_up() {
    cd ${WORKSPACE_DIR}
}

function tear_down() {
    bazel shutdown
}

# Looks for the last occurrence of a log message in a log file.
#
# This assumes the use of java.util.logging.SimpleFormatter, which splits
# the context of a log entry and the log message itself in two lines.
#
# TODO(jmmv): We should have functionality in unittest.bash to check the
# contents of the Bazel's client log in a way that allows us to test for
# only the messages printed by the last-run command.
function assert_last_log() {
  local context="${1}"; shift
  local message="${1}"; shift
  local log="${1}"; shift
  local fail_message="${1}"; shift

  if ! grep "${context}" "${log}" | grep -q "${message}" ; then
    cat "${log}" >>"${TEST_log}"  # Help debugging when we fail.
    fail "${fail_message}"
  fi
}

# Asserts that the last dump of cache stats in the log matches the given
# metric and value.
function assert_cache_stats() {
  local metric="${1}"; shift
  local exp_value="${1}"; shift

  local java_log
  java_log="$(bazel info server_log 2>/dev/null)" || fail "bazel info failed"
  local last="$(grep "CacheFileDigestsModule" "${java_log}")"
  [ -n "${last}" ] || fail "Could not find cache stats in log"
  if ! echo "${last}" | grep -q "${metric}=${exp_value}"; then
    echo "Last cache stats: ${last}" >>"${TEST_log}"
    fail "${metric} was not ${exp_value}"
  fi
}

#### TESTS #############################################################

function test_cache_computed_file_digests_behavior() {
  local -r pkg="${FUNCNAME}"
  mkdir -p "$pkg" || fail "could not create \"$pkg\""

  mkdir -p $pkg/package || fail "mkdir failed"
  cat >$pkg/package/BUILD <<EOF
genrule(
  name = "foo",
  srcs = ["foo.in"],
  outs = ["foo.out"],
  cmd = "cat \$(location foo.in) >\$@",
)

genrule(
  name = "bar",
  srcs = ["bar.in", ":foo"],
  outs = ["bar.out"],
  cmd = "cat \$(location bar.in) \$(location :foo) >\$@",
)
EOF
  touch $pkg/package/foo.in $pkg/package/bar.in

  bazel build $pkg/package:bar >>"${TEST_log}" 2>&1 || fail "Should build"
  # We cannot make any robust assertions on the first run because of implicit
  # dependencies we have no control about.

  # Rebuilding without changes should yield hits for everything.  Run this
  # multiple times to ensure the reported statistics are not accumulated.
  for run in 1 2 3; do
    bazel build $pkg/package:bar >>"${TEST_log}" 2>&1 || fail "Should build"
    assert_cache_stats "hit count" 1  # stable-status.txt
    assert_cache_stats "miss count" 1  # volatile-status.txt
  done

  # Throw away the in-memory Skyframe state by flipping a flag.  We expect hits
  # for the previous outputs, which are used to query the action cache.
  bazel build --nocheck_visibility $pkg/package:bar >>"${TEST_log}" 2>&1 \
      || fail "Should build"
  assert_cache_stats "hit count" 3  # stable-status.txt foo.out bar.out
  assert_cache_stats "miss count" 1  # volatile-status.txt

  # Change the size of the cache and retry the same build.  We expect no hits
  # because resizing the cache invalidates all of its contents.
  bazel build --cache_computed_file_digests=100 $pkg/package:bar \
      >>"${TEST_log}" 2>&1 || fail "Should build"
  assert_cache_stats "hit count" 0
  assert_cache_stats "miss count" 4  # {stable,volatile}-status* {foo,bar}.out

  # Run a non-build command, which should not interfere with the cache.
  bazel info >>"${TEST_log}" 2>&1 || fail "Should run"
  assert_cache_stats "hit count" 0  # Same as previous command; unmodified.
  assert_cache_stats "miss count" 4  # Same as previous command; unmodified.

  # Rebuild without changes one more time with the new size of the cache to
  # ensure the cache is not reset across runs with the flag override.
  bazel build --nocheck_visibility --cache_computed_file_digests=100 \
      $pkg/package:bar >>"${TEST_log}" 2>&1 || fail "Should build"
  assert_cache_stats "hit count" 3  # stable-status.txt foo.out bar.out
  assert_cache_stats "miss count" 1  # volatile-status.txt
}

function DISABLED_test_cache_computed_file_digests_uncaught_changes() {
  # Does not work on Windows, https://github.com/bazelbuild/bazel/issues/6098
  local timestamp=201703151112.13  # Fixed timestamp to mark our file with.

  mkdir -p package || fail "mkdir failed"
  cat >package/BUILD <<EOF
genrule(
  name = "foo",
  srcs = ["foo.in"],
  outs = ["foo.out"],
  cmd = "echo foo >\$@ && touch -t ${timestamp} \$@",
)
EOF
  touch package/foo.in

  # Build the target once to populate the action cache, then update a file to a
  # known timestamp, and rebuild the target to recompute our internal digests
  # cache.
  bazel build package:foo >>"${TEST_log}" 2>&1 || fail "Should build"
  local output_file="$(find bazel-out/ -name foo.out)"
  touch -t "${timestamp}" "${output_file}"
  bazel build package:foo >>"${TEST_log}" 2>&1 || fail "Should build"

  # Modify the content of a file in the action cache in a way that bypasses the
  # logic to cache file digests: replace the file's content with new contents of
  # the same length; avoid modifying the inode number; and respect the previous
  # timestamp.
  function log_metadata_for_test_debugging() {
      echo "${1} ${2} modifying it in place:"
      stat "${output_file}"
      if which md5sum >/dev/null; then  # macOS and possibly others.
          md5sum "${output_file}"
      elif which md5 >/dev/null; then  # Linux and possibly others.
          md5 "${output_file}"
      fi
  }
  log_metadata_for_test_debugging "${output_file}" before >>"${TEST_log}"
  chmod +w "${output_file}"
  echo bar >"${output_file}"  # Contents must match length in genrule.
  chmod -w "${output_file}"
  touch -t "${timestamp}" "${output_file}"
  log_metadata_for_test_debugging "${output_file}" after >>"${TEST_log}"

  # Assert all hits after discarding the in-memory Skyframe state while
  # modifying the on-disk state in a way that bypasses the digests cache
  # functionality.
  bazel build --nocheck_visibility package:foo >>"${TEST_log}" 2>&1 \
      || fail "Should build"
  [[ "$(cat "${output_file}")" == bar ]] \
      || fail "External change to action cache misdetected"

  # For completeness, make the changes to the same output file visibile and
  # ensure Blaze notices them.  This is to sanity-check that we actually
  # modified the right output file above.
  touch "${output_file}"
  bazel build package:foo >>"${TEST_log}" 2>&1 || fail "Should build"
  [[ "$(cat "${output_file}")" == foo ]] \
      || fail "External change to action cache not detected"
}

function test_cache_computed_file_digests_ui() {
  local -r pkg="${FUNCNAME}"
  mkdir -p "$pkg" || fail "could not create \"$pkg\""

  mkdir -p $pkg/package || fail "mkdir failed"
  echo "cc_library(name = 'foo', srcs = ['foo.cc'])" >$pkg/package/BUILD
  echo "int foo(void) { return 0; }" >$pkg/package/foo.cc

  local java_log
  java_log="$(bazel info server_log 2>/dev/null)" || fail "bazel info failed"

  bazel build $pkg/package:foo >>"${TEST_log}" 2>&1 || fail "Should build"
  assert_last_log "CacheFileDigestsModule" "Cache stats" "${java_log}" \
    "Digests cache not enabled by default"

  bazel build --cache_computed_file_digests=0 $pkg/package:foo >>"${TEST_log}" 2>&1 \
      || fail "Should build"
  assert_last_log "CacheFileDigestsModule" "Disabled cache" "${java_log}" \
      "Digests cache not disabled as requested"

  bazel build $pkg/package:foo >>"${TEST_log}" 2>&1 || fail "Should build"
  assert_last_log "CacheFileDigestsModule" "Cache stats" "${java_log}" \
      "Digests cache not reenabled"
}

function do_threading_default_auto_test() {
  local context="${1}"; shift
  local flag_name="${1}"; shift

  local -r pkg="${FUNCNAME}"
  mkdir -p "$pkg" || fail "could not create \"$pkg\""

  mkdir -p $pkg/package || fail "mkdir failed"
  echo "cc_library(name = 'foo', srcs = ['foo.cc'])" >$pkg/package/BUILD
  echo "int foo(void) { return 0; }" >$pkg/package/foo.cc

  # The default value of the flag under test is only read if it is not set
  # explicitly.  Do not use a bazelrc here as it would break the test.
  local java_log
  java_log="$(bazel --nomaster_bazelrc --bazelrc=/dev/null info server_log \
      2>/dev/null)" || fail "bazel info should work"
  bazel --nomaster_bazelrc --bazelrc=/dev/null build $pkg/package:foo \
      >>"${TEST_log}" 2>&1 || fail "Should build"

  assert_last_log \
      "${context}" \
      "Flag \"${flag_name}\" was set to \"auto\"" \
      "${java_log}" \
      "--${flag_name} was not set to auto by default"
}

function test_jobs_default_auto() {
  do_threading_default_auto_test "BuildRequest" "jobs"
}

function test_loading_phase_threads_default_auto() {
  do_threading_default_auto_test "LoadingPhaseThreadsOption" \
      "loading_phase_threads"
}

function test_analysis_warning_cached() {
  mkdir -p "foo" "bar" || fail "Could not create directories"
  cat > foo/BUILD <<'EOF' || fail "foo/BUILD"
cc_library(
    name = 'foo',
    deprecation = 'foo warning',
    srcs = ['foo.cc'],
    visibility = ['//visibility:public']
)
EOF
  cat > bar/BUILD <<'EOF' || fail "bar/BUILD"
cc_library(name = 'bar', srcs = ['bar.cc'], deps = ['//foo:foo'])
EOF
  touch foo/foo.cc bar/bar.cc || fail "Couldn't touch"
  bazel build --nobuild //bar:bar >& "$TEST_log" || fail "Expected success"
  expect_log "WARNING: .*: foo warning"
  bazel build //bar:bar >& "$TEST_log" || fail "Expected success"
  expect_log "WARNING: .*: foo warning"
  echo "// comment" >> bar/bar.cc || fail "Couldn't change contents"
  bazel build //bar:bar >& "$TEST_log" || fail "Expected success"
  expect_log "WARNING: .*: foo warning"
}


run_suite "Integration tests of ${PRODUCT_NAME} using the execution phase."
