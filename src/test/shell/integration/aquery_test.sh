#!/bin/bash
#
# Copyright 2018 The Bazel Authors. All rights reserved.
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

# --- begin runfiles.bash initialization ---
# Copy-pasted from Bazel's Bash runfiles library (tools/bash/runfiles/runfiles.bash).
set -euo pipefail
if [[ ! -d "${RUNFILES_DIR:-/dev/null}" && ! -f "${RUNFILES_MANIFEST_FILE:-/dev/null}" ]]; then
  if [[ -f "$0.runfiles_manifest" ]]; then
    export RUNFILES_MANIFEST_FILE="$0.runfiles_manifest"
  elif [[ -f "$0.runfiles/MANIFEST" ]]; then
    export RUNFILES_MANIFEST_FILE="$0.runfiles/MANIFEST"
  elif [[ -f "$0.runfiles/bazel_tools/tools/bash/runfiles/runfiles.bash" ]]; then
    export RUNFILES_DIR="$0.runfiles"
  fi
fi
if [[ -f "${RUNFILES_DIR:-/dev/null}/bazel_tools/tools/bash/runfiles/runfiles.bash" ]]; then
  source "${RUNFILES_DIR}/bazel_tools/tools/bash/runfiles/runfiles.bash"
elif [[ -f "${RUNFILES_MANIFEST_FILE:-/dev/null}" ]]; then
  source "$(grep -m1 "^bazel_tools/tools/bash/runfiles/runfiles.bash " \
            "$RUNFILES_MANIFEST_FILE" | cut -d ' ' -f 2-)"
else
  echo >&2 "ERROR: cannot find @bazel_tools//tools/bash/runfiles:runfiles.bash"
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

add_to_bazelrc "build --package_path=%workspace%"

function test_basic_aquery() {
  local pkg="${FUNCNAME[0]}"
  mkdir -p "$pkg" || fail "mkdir -p $pkg"
  cat > "$pkg/BUILD" <<'EOF'
genrule(
    name = "foo",
    srcs = [":bar"],
    outs = ["foo_out.txt"],
    cmd = "cat $(SRCS) > $(OUTS)",
)

genrule(
    name = "bar",
    srcs = ["dummy.txt"],
    outs = ["bar_out.txt"],
    cmd = "echo unused > $(OUTS)",
)
EOF
  echo "hello aquery" > "$pkg/in.txt"

  bazel aquery "//$pkg:foo" > output 2> "$TEST_log" || fail "Expected success"
  assert_contains "//$pkg:foo" output
  assert_not_contains "//$pkg:bar" output

  bazel aquery "deps(//$pkg:foo)" > output 2> "$TEST_log" \
    || fail "Expected success"
  assert_contains "//$pkg:foo" output
  assert_contains "//$pkg:bar" output
}

function test_aquery_text() {
  local pkg="${FUNCNAME[0]}"
  mkdir -p "$pkg" || fail "mkdir -p $pkg"
  cat > "$pkg/BUILD" <<'EOF'
genrule(
    name = "bar",
    srcs = ["dummy.txt"],
    outs = ["bar_out.txt"],
    cmd = "echo unused > $(OUTS)",
)
EOF
  echo "hello aquery" > "$pkg/in.txt"

  bazel aquery --output=text "//$pkg:bar" > output 2> "$TEST_log" \
    || fail "Expected success"
  cat output >> "$TEST_log"
  assert_contains "action 'Executing genrule //$pkg:bar'" output
  assert_contains "Mnemonic: Genrule" output
  assert_contains "Owner: //$pkg:bar" output
  assert_contains "Configuration: .*-fastbuild" output
  # Only check that the inputs/outputs/command line/environment exist, but not
  # their actual contents since that would be too much.
  assert_contains "Inputs: \[" output
  assert_contains "Outputs: \[" output
  if is_windows; then
    assert_contains "Command Line: .*bash\.exe" output
  else
    assert_contains "Command Line: (" output
  fi

  assert_contains "echo unused" output
  bazel aquery --output=text --noinclude_commandline "//$pkg:bar" > output \
    2> "$TEST_log" || fail "Expected success"
  assert_not_contains "echo unused" output
}

function test_aquery_textproto() {
  local pkg="${FUNCNAME[0]}"
  mkdir -p "$pkg" || fail "mkdir -p $pkg"
  cat > "$pkg/BUILD" <<'EOF'
genrule(
    name = "bar",
    srcs = ["dummy.txt"],
    outs = ["bar_out.txt"],
    cmd = "echo unused > $(OUTS)",
)
EOF
  echo "hello aquery" > "$pkg/in.txt"

  bazel aquery --output=textproto "//$pkg:bar" > output 2> "$TEST_log" \
    || fail "Expected success"
  cat output >> "$TEST_log"
  assert_contains "exec_path: \"$pkg/dummy.txt\"" output
  assert_contains "nemonic: \"Genrule\"" output
  assert_contains "mnemonic: \".*-fastbuild\"" output
  assert_contains "echo unused" output

  bazel aquery --output=textproto --noinclude_commandline "//$pkg:bar" > output \
    2> "$TEST_log" || fail "Expected success"
  assert_not_contains "echo unused" output
}

function test_aquery_skylark_env() {
  local pkg="${FUNCNAME[0]}"
  mkdir -p "$pkg" || fail "mkdir -p $pkg"
  cat > "$pkg/rule.bzl" <<'EOF'
def _impl(ctx):
    output = ctx.outputs.out
    input = ctx.file.source
    env = {}
    env["foo"] = "bar"

    ctx.actions.run_shell(
        inputs = [input],
        outputs = [output],
        command = "cat '%s' > '%s'" % (input.path, output.path),
        env = env,
    )

copy = rule(
    implementation = _impl,
    attrs = {"source": attr.label(mandatory = True, allow_single_file = True)},
    outputs = {"out": "%{name}.copy"},
)
EOF

  cat > "$pkg/BUILD" <<'EOF'
load(":rule.bzl", "copy")
copy(
    name = "goo",
    source = "dummy.txt",
)
EOF
  echo "hello aquery" > "$pkg/dummy.txt"

  bazel aquery --output=text "//$pkg:goo" > output 2> "$TEST_log" \
    || fail "Expected success"
  cat output >> "$TEST_log"
  assert_contains "Mnemonic: SkylarkAction" output
  assert_contains "Owner: //$pkg:goo" output
  assert_contains "Environment: \[.*foo=bar" output
}

function test_aquery_aspect() {
  local pkg="${FUNCNAME[0]}"
  mkdir -p "$pkg" || fail "mkdir -p $pkg"
  cat > "$pkg/foobar.bzl" <<'EOF'
DummyProvider = provider(fields = {'dummies' : 'files'})

def _aspect_impl(target, ctx):
    ins = []
    if hasattr(ctx.rule.attr, 'srcs'):
        ins = depset(transitive = [src.files for src in ctx.rule.attr.srcs]).to_list()
    dummy = ctx.actions.declare_file("%s-aspect" % (target.label.name))
    all_dummies = depset([dummy], transitive = [dep[DummyProvider].dummies for dep in ctx.rule.attr.deps])
    ctx.actions.run_shell(inputs = ins, outputs = [dummy], command = "echo {} > {}".format(ctx.attr.bar, dummy.path))
    return [DummyProvider(dummies = all_dummies)]

bar_aspect = aspect(implementation = _aspect_impl,
    attr_aspects = ['deps'],
    attrs = {
        'bar' : attr.string(values = ['one', 'two', 'three']),
    }
)

def _bar_impl(ctx):
    ctx.file_action(content = "hello world", output = ctx.outputs.out)
    return struct(files = depset(transitive = [dep[DummyProvider].dummies for dep in ctx.attr.deps]))

bar_rule = rule(
    implementation = _bar_impl,
    attrs = {
        'deps' : attr.label_list(aspects = [bar_aspect]),
        'bar' : attr.string(default = 'two'),
    },
    outputs = {"out": "%{name}.count"},
)

def _foo_library_impl(ctx):
    ctx.actions.run_shell(
        command = "touch {}".format(ctx.outputs.out.path),
        inputs = ctx.files.srcs,
        outputs = [ctx.outputs.out],
    )

foo_library = rule(
    implementation = _foo_library_impl,
    attrs = {
        "srcs": attr.label_list(allow_files = True),
        "deps": attr.label_list(),
    },
    outputs = {"out": "%{name}.foo_object"},
)
EOF
  cat > "$pkg/BUILD" <<'EOF'
load(":foobar.bzl", "bar_rule", "foo_library")

foo_library(
    name = "a",
    srcs = ["a.foo"],
)

foo_library(
    name = "b",
    srcs = ["b.foo"],
    deps = [":a"],
)

foo_library(
    name = "c",
    srcs = ["c.foo"],
    deps = [":b"],
)

bar_rule(
    name = 'bar',
    deps = [':c'],
    bar = 'three',
)
EOF

  # Test without considering aspects.
  bazel aquery --output=text "//$pkg:a" > output 2> "$TEST_log" \
    || fail "Expected success"
  cat output >> "$TEST_log"
  expect_log_n "^action '" 1 "Expected exactly one action when not considering aspects."
  assert_not_contains "AspectDescriptors" output

  # Test considering aspects but without triggering it.
  bazel aquery --include_aspects --output=text "//$pkg:a" > output 2> "$TEST_log" \
    || fail "Expected success"
  cat output >> "$TEST_log"
  expect_log_n "^action '" 1 "Expected exactly one action without universe scope."
  assert_not_contains "AspectDescriptors" output

  # Trigger the aspect.
  bazel aquery --include_aspects --output=text "//$pkg:a" --universe_scope="//$pkg:bar" \
    > output 2> "$TEST_log" \
    || fail "Expected success"
  cat output >> "$TEST_log"
  expect_log_n "^action '" 2 "Expected exactly two actions."

  assert_contains "AspectDescriptors: \[.*foobar.bzl%bar_aspect.*bar='three'" output
  assert_contains "Outputs: \[.*a.foo_object" output
  assert_contains "Outputs: \[.*a-aspect" output
}

run_suite "${PRODUCT_NAME} action graph query tests"
