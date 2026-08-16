#!/usr/bin/env -S jq -ef
def exact_keys($expected):
  type == "object" and ((keys | sort) == ($expected | sort));
def nonblank_string:
  type == "string" and test("\\S");
def lowercase_hex($expected_length):
  type == "string" and (length == $expected_length) and test("^[0-9a-f]+$");
def nonnegative_integer:
  type == "number" and . >= 0 and floor == .;

exact_keys([
  "schema", "targetId", "gitCommit", "gitTree", "dirty", "gradleVersion",
  "wrapperSha256", "jdk", "classpath"
]) and
.schema == "revoman-target-manifest/v1" and
(.targetId | nonblank_string) and
(.gitCommit | lowercase_hex(40)) and
(.gitTree | lowercase_hex(40)) and
(.dirty | type == "boolean") and
(.gradleVersion | nonblank_string) and
(.wrapperSha256 | lowercase_hex(64)) and
(.jdk | exact_keys(["distribution", "vendor", "fullVersion", "javaHome", "jvmFlags"])) and
(.jdk.distribution | nonblank_string) and
(.jdk.vendor | nonblank_string) and
(.jdk.fullVersion | nonblank_string) and
(.jdk.javaHome | nonblank_string) and
(.jdk.jvmFlags | type == "array" and length > 0) and
all(.jdk.jvmFlags[]; nonblank_string) and
(.classpath | type == "array" and length > 0) and
all(.classpath[];
  exact_keys(["logicalId", "executionPath", "sizeBytes", "sha256"]) and
  (.logicalId | nonblank_string) and
  (.executionPath | nonblank_string) and
  (.sizeBytes | nonnegative_integer) and
  (.sha256 | lowercase_hex(64))
) and
([.classpath[].logicalId] | length == (unique | length))
