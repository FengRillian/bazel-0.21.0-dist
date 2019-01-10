// Copyright 2017 The Bazel Authors. All rights reserved.
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

#include <windows.h>

#include <algorithm>
#include <memory>
#include <string>

#include "src/main/cpp/blaze_util.h"
#include "src/main/cpp/blaze_util_platform.h"
#include "src/main/cpp/util/file.h"
#include "src/main/cpp/util/strings.h"
#include "googletest/include/gtest/gtest.h"

namespace blaze {

using std::string;
using std::unique_ptr;
using std::wstring;

// Asserts that the envvar named `key` is unset.
// Exercises GetEnvironmentVariable{A,W}, both with `key` and its lower case
// version, to make sure that envvar retrieval is case-insensitive (envvar names
// are case-insensitive on Windows).
//
// This is a macro so the assertions will have the correct line number.
#define ASSERT_ENVVAR_UNSET(/* const char* */ key)                            \
  {                                                                           \
    ASSERT_EQ(::GetEnvironmentVariableA(key, NULL, 0), (DWORD)0);             \
    ASSERT_EQ(                                                                \
        ::GetEnvironmentVariableA(blaze_util::AsLower(key).c_str(), NULL, 0), \
        (DWORD)0);                                                            \
    ASSERT_EQ(::GetEnvironmentVariableW(                                      \
                  blaze_util::CstringToWstring(key).get(), NULL, 0),          \
              (DWORD)0);                                                      \
    ASSERT_EQ(::GetEnvironmentVariableW(blaze_util::CstringToWstring(         \
                                            blaze_util::AsLower(key).c_str()) \
                                            .get(),                           \
                                        NULL, 0),                             \
              (DWORD)0);                                                      \
  }

// Asserts that the envvar named `key` is set to the `expected` value.
// Exercises GetEnvironmentVariable{A,W}, both with `key` and its lower case
// version, to make sure that envvar retrieval is case-insensitive (envvar names
// are case-insensitive on Windows).
//
// This is a macro so the assertions will have the correct line number.
#define ASSERT_ENVVAR(/* const (char* | string&) */ _key,                    \
                      /* const (char* | string&) */ _expected)               \
  {                                                                          \
    string key(_key);                                                        \
    string expected(_expected);                                              \
    DWORD size = ::GetEnvironmentVariableA(key.c_str(), NULL, 0);            \
    ASSERT_GT(size, (DWORD)0);                                               \
    unique_ptr<char[]> buf(new char[size]);                                  \
                                                                             \
    /* Assert that GetEnvironmentVariableA can retrieve the value. */        \
    ASSERT_EQ(::GetEnvironmentVariableA(key.c_str(), buf.get(), size),       \
              size - 1);                                                     \
    ASSERT_EQ(string(buf.get()), expected);                                  \
                                                                             \
    /* Assert that envvar keys are case-insensitive. */                      \
    string lkey(blaze_util::AsLower(key));                                   \
    ASSERT_EQ(::GetEnvironmentVariableA(lkey.c_str(), buf.get(), size),      \
              size - 1);                                                     \
    ASSERT_EQ(string(buf.get()), expected);                                  \
                                                                             \
    /* Assert that GetEnvironmentVariableW can retrieve the value. */        \
    wstring wkey(blaze_util::CstringToWstring(key.c_str()).get());           \
    wstring wexpected(blaze_util::CstringToWstring(expected.c_str()).get()); \
    size = ::GetEnvironmentVariableW(wkey.c_str(), NULL, 0);                 \
    ASSERT_GT(size, (DWORD)0);                                               \
    unique_ptr<WCHAR[]> wbuf(new WCHAR[size]);                               \
    ASSERT_EQ(::GetEnvironmentVariableW(wkey.c_str(), wbuf.get(), size),     \
              size - 1);                                                     \
    ASSERT_EQ(wstring(wbuf.get()), wexpected);                               \
                                                                             \
    /* Assert that widechar envvar keys are case-insensitive. */             \
    wstring wlkey(blaze_util::CstringToWstring(lkey.c_str()).get());         \
    ASSERT_EQ(::GetEnvironmentVariableW(wlkey.c_str(), wbuf.get(), size),    \
              size - 1);                                                     \
    ASSERT_EQ(wstring(wbuf.get()), wexpected);                               \
  }

TEST(BlazeUtilWindowsTest, TestGetEnv) {
  ASSERT_ENVVAR_UNSET("DOES_not_EXIST");

  string actual(GetEnv("TEST_SRCDIR"));
  ASSERT_NE(actual, "");

  std::replace(actual.begin(), actual.end(), '/', '\\');
  ASSERT_NE(actual.find(":\\"), string::npos);

  ASSERT_ENVVAR_UNSET("Bazel_TEST_Key1");
  ASSERT_TRUE(::SetEnvironmentVariableA("Bazel_TEST_Key1", "some_VALUE"));
  ASSERT_ENVVAR("Bazel_TEST_Key1", "some_VALUE");
  ASSERT_TRUE(::SetEnvironmentVariableA("Bazel_TEST_Key1", NULL));

  string long_string(MAX_PATH, 'a');
  string long_key = string("Bazel_TEST_Key2_") + long_string;
  string long_value = string("Bazel_TEST_Value2_") + long_string;

  ASSERT_ENVVAR_UNSET(long_key.c_str());
  ASSERT_TRUE(::SetEnvironmentVariableA(long_key.c_str(), long_value.c_str()));
  ASSERT_ENVVAR(long_key, long_value);
  ASSERT_TRUE(::SetEnvironmentVariableA(long_key.c_str(), NULL));
}

TEST(BlazeUtilWindowsTest, TestSetEnv) {
  ASSERT_ENVVAR_UNSET("Bazel_TEST_Key1");
  SetEnv("Bazel_TEST_Key1", "some_VALUE");
  ASSERT_ENVVAR("Bazel_TEST_Key1", "some_VALUE");
  SetEnv("Bazel_TEST_Key1", "");
  ASSERT_ENVVAR_UNSET("Bazel_TEST_Key1");

  string long_string(MAX_PATH, 'a');
  string long_key = string("Bazel_TEST_Key2_") + long_string;
  string long_value = string("Bazel_TEST_Value2_") + long_string;

  ASSERT_ENVVAR_UNSET(long_key.c_str());
  SetEnv(long_key, long_value);
  ASSERT_ENVVAR(long_key.c_str(), long_value.c_str());
  SetEnv(long_key, "");
  ASSERT_ENVVAR_UNSET(long_key.c_str());
}

TEST(BlazeUtilWindowsTest, TestUnsetEnv) {
  ASSERT_ENVVAR_UNSET("Bazel_TEST_Key1");
  SetEnv("Bazel_TEST_Key1", "some_VALUE");
  ASSERT_ENVVAR("Bazel_TEST_Key1", "some_VALUE");
  UnsetEnv("Bazel_TEST_Key1");
  ASSERT_ENVVAR_UNSET("Bazel_TEST_Key1");

  string long_string(MAX_PATH, 'a');
  string long_key = string("Bazel_TEST_Key2_") + long_string;
  string long_value = string("Bazel_TEST_Value2_") + long_string;

  ASSERT_ENVVAR_UNSET(long_key.c_str());
  SetEnv(long_key, long_value);
  ASSERT_ENVVAR(long_key.c_str(), long_value.c_str());
  UnsetEnv(long_key);
  ASSERT_ENVVAR_UNSET(long_key.c_str());
}

}  // namespace blaze
