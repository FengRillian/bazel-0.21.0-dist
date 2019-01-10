# Copyright 2016 The Bazel Authors. All rights reserved.
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
"""Rules for downloading files and archives over HTTP.

### Setup

To use these rules, load them in your `WORKSPACE` file as follows:

```python
load(
    "@bazel_tools//tools/build_defs/repo:http.bzl",
    "http_archive",
    "http_file",
    "http_jar",
)
```

These rules are improved versions of the native http rules and will eventually
replace the native rules.
"""

load("@bazel_tools//tools/build_defs/repo:utils.bzl", "patch", "workspace_and_buildfile")

def _http_archive_impl(ctx):
    """Implementation of the http_archive rule."""
    if not ctx.attr.url and not ctx.attr.urls:
        fail("At least one of url and urls must be provided")
    if ctx.attr.build_file and ctx.attr.build_file_content:
        fail("Only one of build_file and build_file_content can be provided.")

    all_urls = []
    if ctx.attr.urls:
        all_urls = ctx.attr.urls
    if ctx.attr.url:
        all_urls = [ctx.attr.url] + all_urls

    ctx.download_and_extract(
        all_urls,
        "",
        ctx.attr.sha256,
        ctx.attr.type,
        ctx.attr.strip_prefix,
    )
    patch(ctx)
    workspace_and_buildfile(ctx)

_HTTP_FILE_BUILD = """
package(default_visibility = ["//visibility:public"])

filegroup(
    name = "file",
    srcs = ["{}"],
)
"""

def _http_file_impl(ctx):
    """Implementation of the http_file rule."""
    repo_root = ctx.path(".")
    forbidden_files = [
        repo_root,
        ctx.path("WORKSPACE"),
        ctx.path("BUILD"),
        ctx.path("BUILD.bazel"),
        ctx.path("file/BUILD"),
        ctx.path("file/BUILD.bazel"),
    ]
    downloaded_file_path = ctx.attr.downloaded_file_path
    download_path = ctx.path("file/" + downloaded_file_path)
    if download_path in forbidden_files or not str(download_path).startswith(str(repo_root)):
        fail("'%s' cannot be used as downloaded_file_path in http_file" % ctx.attr.downloaded_file_path)
    ctx.download(
        ctx.attr.urls,
        "file/" + downloaded_file_path,
        ctx.attr.sha256,
        ctx.attr.executable,
    )
    ctx.file("WORKSPACE", "workspace(name = \"{name}\")".format(name = ctx.name))
    ctx.file("file/BUILD", _HTTP_FILE_BUILD.format(downloaded_file_path))

_HTTP_JAR_BUILD = """
package(default_visibility = ["//visibility:public"])

java_import(
  name = 'jar',
  jars = ['downloaded.jar'],
  visibility = ['//visibility:public'],
)

filegroup(
  name = 'file',
  srcs = ['downloaded.jar'],
  visibility = ['//visibility:public'],
)

"""

def _http_jar_impl(ctx):
    """Implementation of the http_jar rule."""
    all_urls = []
    if ctx.attr.urls:
        all_urls = ctx.attr.urls
    if ctx.attr.url:
        all_urls = [ctx.attr.url] + all_urls
    ctx.download(all_urls, "jar/downloaded.jar", ctx.attr.sha256)
    ctx.file("WORKSPACE", "workspace(name = \"{name}\")".format(name = ctx.name))
    ctx.file("jar/BUILD", _HTTP_JAR_BUILD)

_http_archive_attrs = {
    "url": attr.string(),
    "urls": attr.string_list(),
    "sha256": attr.string(),
    "strip_prefix": attr.string(),
    "type": attr.string(),
    "build_file": attr.label(allow_single_file = True),
    "build_file_content": attr.string(),
    "patches": attr.label_list(default = []),
    "patch_tool": attr.string(default = "patch"),
    "patch_args": attr.string_list(default = ["-p0"]),
    "patch_cmds": attr.string_list(default = []),
    "workspace_file": attr.label(allow_single_file = True),
    "workspace_file_content": attr.string(),
}

http_archive = repository_rule(
    implementation = _http_archive_impl,
    attrs = _http_archive_attrs,
)
"""Downloads a Bazel repository as a compressed archive file, decompresses it,
and makes its targets available for binding.

It supports the following file extensions: `"zip"`, `"jar"`, `"war"`, `"tar"`,
`"tar.gz"`, `"tgz"`, `"tar.xz"`, and `tar.bz2`.

Examples:
  Suppose the current repository contains the source code for a chat program,
  rooted at the directory `~/chat-app`. It needs to depend on an SSL library
  which is available from http://example.com/openssl.zip. This `.zip` file
  contains the following directory structure:

  ```
  WORKSPACE
  src/
    openssl.cc
    openssl.h
  ```

  In the local repository, the user creates a `openssl.BUILD` file which
  contains the following target definition:

  ```python
  cc_library(
      name = "openssl-lib",
      srcs = ["src/openssl.cc"],
      hdrs = ["src/openssl.h"],
  )
  ```

  Targets in the `~/chat-app` repository can depend on this target if the
  following lines are added to `~/chat-app/WORKSPACE`:

  ```python
  http_archive(
      name = "my_ssl",
      urls = ["http://example.com/openssl.zip"],
      sha256 = "03a58ac630e59778f328af4bcc4acb4f80208ed4",
      build_file = "//:openssl.BUILD",
  )
  ```

  Then targets would specify `@my_ssl//:openssl-lib` as a dependency.

Args:
  name: A unique name for this repository.
  build_file: The file to use as the `BUILD` file for this repository.

    Either `build_file` or `build_file_content` can be specified.

    This attribute is an absolute label (use '@//' for the main repo). The file
    does not need to be named `BUILD`, but can be (something like
    `BUILD.new-repo-name` may work well for distinguishing it from the
    repository's actual `BUILD` files.

  build_file_content: The content for the BUILD file for this repository.

    Either `build_file` or `build_file_content` can be specified.
  workspace_file: The file to use as the `WORKSPACE` file for this repository.

    Either `workspace_file` or `workspace_file_content` can be specified, or
    neither, but not both.
  workspace_file_content: The content for the WORKSPACE file for this repository.

    Either `workspace_file` or `workspace_file_content` can be specified, or
    neither, but not both.
  sha256: The expected SHA-256 of the file downloaded.

    This must match the SHA-256 of the file downloaded. _It is a security risk
    to omit the SHA-256 as remote files can change._ At best omitting this
    field will make your build non-hermetic. It is optional to make development
    easier but should be set before shipping.
  strip_prefix: A directory prefix to strip from the extracted files.

    Many archives contain a top-level directory that contains all of the useful
    files in archive. Instead of needing to specify this prefix over and over
    in the `build_file`, this field can be used to strip it from all of the
    extracted files.

    For example, suppose you are using `foo-lib-latest.zip`, which contains the
    directory `foo-lib-1.2.3/` under which there is a `WORKSPACE` file and are
    `src/`, `lib/`, and `test/` directories that contain the actual code you
    wish to build. Specify `strip_prefix = "foo-lib-1.2.3"` to use the
    `foo-lib-1.2.3` directory as your top-level directory.

    Note that if there are files outside of this directory, they will be
    discarded and inaccessible (e.g., a top-level license file). This includes
    files/directories that start with the prefix but are not in the directory
    (e.g., `foo-lib-1.2.3.release-notes`). If the specified prefix does not
    match a directory in the archive, Bazel will return an error.
  type: The archive type of the downloaded file.

    By default, the archive type is determined from the file extension of the
    URL. If the file has no extension, you can explicitly specify one of the
    following: `"zip"`, `"jar"`, `"war"`, `"tar"`, `"tar.gz"`, `"tgz"`,
    `"tar.xz"`, or `tar.bz2`.
  url: A URL to a file that will be made available to Bazel.

    This must be a file, http or https URL. Redirections are followed.
    Authentication is not supported.

    This parameter is to simplify the transition from the native http_archive
    rule. More flexibility can be achieved by the urls parameter that allows
    to specify alternative URLs to fetch from.
  urls: A list of URLs to a file that will be made available to Bazel.

    Each entry must be a file, http or https URL. Redirections are followed.
    Authentication is not supported.
  patches: A list of files that are to be applied as patches after extracting
    the archive.
  patch_tool: the patch(1) utility to use.
  patch_args: arguments given to the patch tool, defaults to ["-p0"]
  patch_cmds: sequence of commands to be applied after patches are applied.
"""

http_file = repository_rule(
    implementation = _http_file_impl,
    attrs = {
        "executable": attr.bool(),
        "downloaded_file_path": attr.string(default = "downloaded"),
        "sha256": attr.string(),
        "urls": attr.string_list(mandatory = True),
    },
)
"""Downloads a file from a URL and makes it available to be used as a file
group.

Examples:
  Suppose you need to have a debian package for your custom rules. This package
  is available from http://example.com/package.deb. Then you can add to your
  WORKSPACE file:

  ```python
  http_file(
      name = "my_deb",
      urls = ["http://example.com/package.deb"],
      sha256 = "03a58ac630e59778f328af4bcc4acb4f80208ed4",
  )
  ```

  Targets would specify `@my_deb//file` as a dependency to depend on this file.

Args:
  name: A unique name for this repository.
  executable: If the downloaded file should be made executable. Defaults to
    False.
  downloaded_file_path: Path assigned to the file downloaded.
  sha256: The expected SHA-256 of the file downloaded.

    This must match the SHA-256 of the file downloaded. _It is a security risk
    to omit the SHA-256 as remote files can change._ At best omitting this
    field will make your build non-hermetic. It is optional to make development
    easier but should be set before shipping.
  urls: A list of URLs to a file that will be made available to Bazel.

    Each entry must be a file, http or https URL. Redirections are followed.
    Authentication is not supported.
"""

http_jar = repository_rule(
    implementation = _http_jar_impl,
    attrs = {
        "sha256": attr.string(),
        "url": attr.string(),
        "urls": attr.string_list(),
    },
)
"""Downloads a jar from a URL and makes it available as java_import

Downloaded files must have a .jar extension.

Examples:
  Suppose the current repository contains the source code for a chat program, rooted at the
  directory `~/chat-app`. It needs to depend on an SSL library which is available from
  `http://example.com/openssl-0.2.jar`.

  Targets in the `~/chat-app` repository can depend on this target if the following lines are
  added to `~/chat-app/WORKSPACE`:

  ```python
  http_jar(
      name = "my_ssl",
      url = "http://example.com/openssl-0.2.jar",
      sha256 = "03a58ac630e59778f328af4bcc4acb4f80208ed4",
  )
  ```

  Targets would specify <code>@my_ssl//jar</code> as a dependency to depend on this jar.

  You may also reference files on the current system (localhost) by using "file:///path/to/file"
  if you are on Unix-based systems. If you're on Windows, use "file:///c:/path/to/file". In both
  examples, note the three slashes (`/`) -- the first two slashes belong to `file://` and the third
  one belongs to the absolute path to the file.


Args:
  name: A unique name for this repository.
  sha256: The expected SHA-256 of the file downloaded.
  url: The URL to fetch the jar from. It must end in `.jar`.
  urls: A list of URLS the jar can be fetched from. They have to end in `.jar`.
"""
