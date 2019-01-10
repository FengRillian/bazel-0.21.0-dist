#!/bin/bash

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

# shift stderr to stdout.
exec 2>&1

no_echo=
if [[ "$1" = "--no_echo" ]]; then
  # Don't print anything to stdout in this special case.
  # Currently needed for persistent test runner.
  no_echo="true"
  shift
else
  echo 'exec ${PAGER:-/usr/bin/less} "$0" || exit 1'
  echo "Executing tests from ${TEST_TARGET}"
fi

function is_absolute {
  [[ "$1" = /* ]] || [[ "$1" =~ ^[a-zA-Z]:[/\\].* ]]
}

# The original execution root. Usually this script changes directory into the
# runfiles directory, so using $PWD is not a reliable way to find the execution
# root.
EXEC_ROOT="$PWD"

# Bazel sets some environment vars to relative paths to improve caching and
# support remote execution, where the absolute path may not be known to Bazel.
# Convert them to absolute paths here before running the actual test.
is_absolute "$TEST_PREMATURE_EXIT_FILE" ||
  TEST_PREMATURE_EXIT_FILE="$PWD/$TEST_PREMATURE_EXIT_FILE"
is_absolute "$TEST_WARNINGS_OUTPUT_FILE" ||
  TEST_WARNINGS_OUTPUT_FILE="$PWD/$TEST_WARNINGS_OUTPUT_FILE"
is_absolute "$TEST_LOGSPLITTER_OUTPUT_FILE" ||
  TEST_LOGSPLITTER_OUTPUT_FILE="$PWD/$TEST_LOGSPLITTER_OUTPUT_FILE"
is_absolute "$TEST_INFRASTRUCTURE_FAILURE_FILE" ||
  TEST_INFRASTRUCTURE_FAILURE_FILE="$PWD/$TEST_INFRASTRUCTURE_FAILURE_FILE"
is_absolute "$TEST_UNUSED_RUNFILES_LOG_FILE" ||
  TEST_UNUSED_RUNFILES_LOG_FILE="$PWD/$TEST_UNUSED_RUNFILES_LOG_FILE"
is_absolute "$TEST_UNDECLARED_OUTPUTS_DIR" ||
  TEST_UNDECLARED_OUTPUTS_DIR="$PWD/$TEST_UNDECLARED_OUTPUTS_DIR"
is_absolute "$TEST_UNDECLARED_OUTPUTS_MANIFEST" ||
  TEST_UNDECLARED_OUTPUTS_MANIFEST="$PWD/$TEST_UNDECLARED_OUTPUTS_MANIFEST"
is_absolute "$TEST_UNDECLARED_OUTPUTS_ZIP" ||
  TEST_UNDECLARED_OUTPUTS_ZIP="$PWD/$TEST_UNDECLARED_OUTPUTS_ZIP"
is_absolute "$TEST_UNDECLARED_OUTPUTS_ANNOTATIONS" ||
  TEST_UNDECLARED_OUTPUTS_ANNOTATIONS="$PWD/$TEST_UNDECLARED_OUTPUTS_ANNOTATIONS"
is_absolute "$TEST_UNDECLARED_OUTPUTS_ANNOTATIONS_DIR" ||
  TEST_UNDECLARED_OUTPUTS_ANNOTATIONS_DIR="$PWD/$TEST_UNDECLARED_OUTPUTS_ANNOTATIONS_DIR"

is_absolute "$TEST_SRCDIR" || TEST_SRCDIR="$PWD/$TEST_SRCDIR"
is_absolute "$TEST_TMPDIR" || TEST_TMPDIR="$PWD/$TEST_TMPDIR"
is_absolute "$HOME" || HOME="$TEST_TMPDIR"
is_absolute "$XML_OUTPUT_FILE" || XML_OUTPUT_FILE="$PWD/$XML_OUTPUT_FILE"

# Set USER to the current user, unless passed by Bazel via --test_env.
if [[ -z "$USER" ]]; then
  export USER=$(whoami)
fi

# The test shard status file is only set for sharded tests.
if [[ -n "$TEST_SHARD_STATUS_FILE" ]]; then
  is_absolute "$TEST_SHARD_STATUS_FILE" || TEST_SHARD_STATUS_FILE="$PWD/$TEST_SHARD_STATUS_FILE"
  mkdir -p "$(dirname "$TEST_SHARD_STATUS_FILE")"
fi

is_absolute "$RUNFILES_DIR" || RUNFILES_DIR="$PWD/$RUNFILES_DIR"

# TODO(ulfjack): Standardize on RUNFILES_DIR and remove the {JAVA,PYTHON}_RUNFILES vars.
is_absolute "$JAVA_RUNFILES" || JAVA_RUNFILES="$PWD/$JAVA_RUNFILES"
is_absolute "$PYTHON_RUNFILES" || PYTHON_RUNFILES="$PWD/$PYTHON_RUNFILES"

# Create directories for undeclared outputs and their annotations
mkdir -p "$(dirname "$XML_OUTPUT_FILE")" \
    "$TEST_UNDECLARED_OUTPUTS_DIR" \
    "$TEST_UNDECLARED_OUTPUTS_ANNOTATIONS_DIR"

# Create the test temp directory, which may not exist on the remote host when
# doing a remote build.
mkdir -p "$TEST_TMPDIR"

# Unexport environment variables related to undeclared test outputs that are
# only supposed to be used in this script.
export -n TEST_UNDECLARED_OUTPUTS_MANIFEST
export -n TEST_UNDECLARED_OUTPUTS_ZIP
export -n TEST_UNDECLARED_OUTPUTS_ANNOTATIONS

# Tell googletest about Bazel sharding.
if [[ -n "${TEST_TOTAL_SHARDS+x}" ]] && ((TEST_TOTAL_SHARDS != 0)); then
  export GTEST_SHARD_INDEX="${TEST_SHARD_INDEX}"
  export GTEST_TOTAL_SHARDS="${TEST_TOTAL_SHARDS}"
fi
export GTEST_TMP_DIR="${TEST_TMPDIR}"

# TODO(ulfjack): Update Gunit to accept XML_OUTPUT_FILE and drop this env
# variable.
GUNIT_OUTPUT="xml:${XML_OUTPUT_FILE}"

RUNFILES_MANIFEST_FILE="${TEST_SRCDIR}/MANIFEST"

function rlocation() {
  if is_absolute "$1" ; then
    # If the file path is already fully specified, simply return it.
    echo "$1"
  elif [[ -e "$TEST_SRCDIR/$1" ]]; then
    # If the file exists in the $TEST_SRCDIR then just use it.
    echo "$TEST_SRCDIR/$1"
  elif [[ -e "$RUNFILES_MANIFEST_FILE" ]]; then
    # If a runfiles manifest file exists then use it.
    echo "$(grep "^$1 " "$RUNFILES_MANIFEST_FILE" | sed 's/[^ ]* //')"
  fi
}

export -f rlocation
export -f is_absolute
# If RUNFILES_MANIFEST_ONLY is set to 1 and the manifest file does exist,
# then test programs should use manifest file to find runfiles.
if [[ "${RUNFILES_MANIFEST_ONLY:-}" == "1" && -e "${RUNFILES_MANIFEST_FILE:-}" ]]; then
  export RUNFILES_MANIFEST_FILE
  export RUNFILES_MANIFEST_ONLY
fi

DIR="$TEST_SRCDIR"
if [ ! -z "$TEST_WORKSPACE" ]; then
  DIR="$DIR"/"$TEST_WORKSPACE"
fi
[[ -n "$RUNTEST_PRESERVE_CWD" ]] && DIR="$PWD"


# normal commands are run in the exec-root where they have access to
# the entire source tree. By chdir'ing to the runfiles root, tests only
# have direct access to their declared dependencies.
if [ -z "$COVERAGE_DIR" ]; then
  cd "$DIR" || { echo "Could not chdir $DIR"; exit 1; }
fi

# This header marks where --test_output=streamed will start being printed.
if [[ -z "$no_echo" ]]; then
  echo "-----------------------------------------------------------------------------"
fi

# Unused if EXPERIMENTAL_SPLIT_XML_GENERATION is set.
function encode_output_file {
  if [ -f "$1" ]; then
    # Replace invalid XML characters and invalid sequence in CDATA
    # cf. https://stackoverflow.com/a/7774512/4717701
    perl -CSDA -pe's/[^\x9\xA\xD\x20-\x{D7FF}\x{E000}-\x{FFFD}\x{10000}-\x{10FFFF}]+/?/g;' "$1" \
      | sed 's|]]>|]]>]]<![CDATA[>|g'
  fi
}

# Unused if EXPERIMENTAL_SPLIT_XML_GENERATION is set.
# Keep this in sync with generate-xml.sh!
function write_xml_output_file {
  local duration=$(expr $(date +%s) - $start)
  local errors=0
  local error_msg=
  local signal="${1-}"
  local test_name=
  if [ -n "${XML_OUTPUT_FILE-}" -a ! -f "${XML_OUTPUT_FILE-}" ]; then
    # Create a default XML output file if the test runner hasn't generated it
    if [ -n "${signal}" ]; then
      errors=1
      if [ "${signal}" = "SIGTERM" ]; then
        error_msg="<error message=\"Timed out\"></error>"
      else
        error_msg="<error message=\"Terminated by signal ${signal}\"></error>"
      fi
    elif (( $exitCode != 0 )); then
      errors=1
      error_msg="<error message=\"exited with error code $exitCode\"></error>"
    fi
    test_name="${TEST_BINARY#./}"
    # Ensure that test shards have unique names in the xml output.
    if [[ -n "${TEST_TOTAL_SHARDS+x}" ]] && ((TEST_TOTAL_SHARDS != 0)); then
      ((shard_num=TEST_SHARD_INDEX+1))
      test_name="${test_name}"_shard_"$shard_num"/"$TEST_TOTAL_SHARDS"
    fi
    cat <<EOF >${XML_OUTPUT_FILE}
<?xml version="1.0" encoding="UTF-8"?>
<testsuites>
  <testsuite name="$test_name" tests="1" failures="0" errors="${errors}">
    <testcase name="$test_name" status="run" duration="${duration}" time="${duration}">${error_msg}</testcase>
    <system-out><![CDATA[$(encode_output_file "${XML_OUTPUT_FILE}.log")]]></system-out>
  </testsuite>
</testsuites>
EOF
  fi
  rm -f "${XML_OUTPUT_FILE}.log"
}

# The path of this command-line is usually relative to the exec-root,
# but when using --run_under it can be a "/bin/bash -c" command-line.

# If the test is at the top of the tree, we have to add '.' to $PATH,
PATH=".:$PATH"

if [ -z "$COVERAGE_DIR" ]; then
  EXE="${1#./}"
  shift
else
  EXE="${2#./}"
fi

if is_absolute "$EXE"; then
  TEST_PATH="$EXE"
else
  TEST_PATH="$(rlocation $TEST_WORKSPACE/$EXE)"
fi

# TODO(jsharpe): Use --test_env=TEST_SHORT_EXEC_PATH=true to activate this code
# path to workaround a bug with long executable paths when executing remote
# tests on Windows.
if [ ! -z "$TEST_SHORT_EXEC_PATH" ]; then
  QUALIFIER=0
  BASE="${EXEC_ROOT}/t${QUALIFIER}"
  while [[ -e "${BASE}" || -e "${BASE}.exe" || -e "${BASE}.zip" ]]; do
    ((QUALIFIER++))
    BASE="${EXEC_ROOT}/t${QUALIFIER}"
  done

  # Note for the commands below: "ln -s" is equivalent to "cp" on Windows.

  # Needs to be in the same directory for sh_test. Ignore the error when it
  # doesn't exist.
  ln -s "${TEST_PATH%.*}" "${BASE}" 2>/dev/null
  # Needs to be in the same directory for py_test. Ignore the error when it
  # doesn't exist.
  ln -s "${TEST_PATH%.*}.zip" "${BASE}.zip" 2>/dev/null
  # Needed for all tests.
  ln -s "${TEST_PATH}" "${BASE}.exe"
  TEST_PATH="${BASE}.exe"
fi

exitCode=0
signals="$(trap -l | sed -E 's/[0-9]+\)//g')"
for signal in $signals; do
  trap "write_xml_output_file ${signal}" ${signal}
done
start=$(date +%s)

# Check if we have tail --pid option
dummy=1 &
pid=$!
has_tail=true
tail -fq --pid $pid -s 0.001 /dev/null &> /dev/null || has_tail=false

if [[ "${EXPERIMENTAL_SPLIT_XML_GENERATION}" == "1" ]]; then
  if [ -z "$COVERAGE_DIR" ]; then
    "${TEST_PATH}" "$@" 2>&1 || exitCode=$?
  else
    "$1" "$TEST_PATH" "${@:3}" 2>&1 || exitCode=$?
  fi
elif [ "$has_tail" == true ] && [  -z "$no_echo" ]; then
  touch "${XML_OUTPUT_FILE}.log"
  if [ -z "$COVERAGE_DIR" ]; then
    ("${TEST_PATH}" "$@" &>"${XML_OUTPUT_FILE}.log") &
    pid=$!
  else
    ("$1" "$TEST_PATH" "${@:3}" &> "${XML_OUTPUT_FILE}.log") &
    pid=$!
  fi
  tail -fq --pid $pid -s 0.001 "${XML_OUTPUT_FILE}.log"
  wait $pid
  exitCode=$?
else
  if [ -z "$COVERAGE_DIR" ]; then
    "${TEST_PATH}" "$@" 2> >(tee -a "${XML_OUTPUT_FILE}.log" >&2) 1> >(tee -a "${XML_OUTPUT_FILE}.log") 2>&1 || exitCode=$?
  else
    "$1" "$TEST_PATH" "${@:3}" 2> >(tee -a "${XML_OUTPUT_FILE}.log" >&2) 1> >(tee -a "${XML_OUTPUT_FILE}.log") 2>&1 || exitCode=$?
  fi
fi

for signal in $signals; do
  trap - ${signal}
done
if [[ "${EXPERIMENTAL_SPLIT_XML_GENERATION}" != "1" ]]; then
  write_xml_output_file
fi

# Add all of the files from the undeclared outputs directory to the manifest.
if [[ -n "$TEST_UNDECLARED_OUTPUTS_DIR" && -n "$TEST_UNDECLARED_OUTPUTS_MANIFEST" ]]; then
  undeclared_outputs="$(find -L "$TEST_UNDECLARED_OUTPUTS_DIR" -type f | sort)"
  # Only write the manifest if there are any undeclared outputs.
  if [[ ! -z "$undeclared_outputs" ]]; then
    # For each file, write a tab-separated line with name (relative to
    # TEST_UNDECLARED_OUTPUTS_DIR), size, and mime type to the manifest. e.g.
    # foo.txt	9	text/plain
    while read -r undeclared_output; do
      rel_path="${undeclared_output#$TEST_UNDECLARED_OUTPUTS_DIR/}"
      # stat has different flags for different systems. -c is supported by GNU,
      # and -f by BSD (and thus OSX). Try both.
      file_size="$(stat -f%z "$undeclared_output" 2>/dev/null || stat -c%s "$undeclared_output" 2>/dev/null || echo "Could not stat $undeclared_output")"
      file_type="$(file -L -b --mime-type "$undeclared_output")"

      printf "$rel_path\t$file_size\t$file_type\n"
    done <<< "$undeclared_outputs" \
      > "$TEST_UNDECLARED_OUTPUTS_MANIFEST"
    if [[ ! -s "$TEST_UNDECLARED_OUTPUTS_MANIFEST" ]]; then
      rm "$TEST_UNDECLARED_OUTPUTS_MANIFEST"
    fi
  fi
fi

# Add all of the custom manifest entries to the annotation file.
if [[ -n "$TEST_UNDECLARED_OUTPUTS_ANNOTATIONS" && \
      -n "$TEST_UNDECLARED_OUTPUTS_ANNOTATIONS_DIR" && \
      -d "$TEST_UNDECLARED_OUTPUTS_ANNOTATIONS_DIR" ]]; then
  (
   shopt -s failglob
   cat "$TEST_UNDECLARED_OUTPUTS_ANNOTATIONS_DIR"/*.part > "$TEST_UNDECLARED_OUTPUTS_ANNOTATIONS"
  ) 2> /dev/null
fi

# Zip up undeclared outputs.
if [[ -n "$TEST_UNDECLARED_OUTPUTS_ZIP" ]] && cd "$TEST_UNDECLARED_OUTPUTS_DIR"; then
  (
   shopt -s dotglob failglob
   zip -qr "$TEST_UNDECLARED_OUTPUTS_ZIP" -- *
  ) 2> /dev/null
fi

exit $exitCode
