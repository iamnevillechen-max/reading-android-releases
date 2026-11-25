#!/usr/bin/env python3
"""Run source-only Kotlin/JUnit fixtures without Android SDK/network resolution.

Defaults reuse this workspace's installed Gradle/JDK and cached JUnit jars.
Optional overrides: JAVA_HOME, KOTLIN_LIB (Gradle lib directory), GRADLE_USER_HOME.
This validates the pure merge/identity/aggregation policies and worker isolation; it does not replace
Android build, instrumentation, TalkBack, or real-source acceptance tests.
"""
from pathlib import Path
import hashlib
import os
import re
import subprocess
import sys

project = Path(__file__).resolve().parents[1]
repo = project / "upstream"
shared = project.parent / "sightmate-android" / ".toolchain"
java_home = Path(os.environ.get("JAVA_HOME", str(shared / "jdk-17.0.21+9" / "Contents" / "Home")))
java = java_home / "bin" / "java"
lib_options = list((project / ".toolchain").glob("gradle-*/lib")) + list(shared.glob("gradle-*/lib"))
lib = Path(os.environ["KOTLIN_LIB"]) if os.environ.get("KOTLIN_LIB") else next(
    (p for p in sorted(lib_options, reverse=True) if list(p.glob("kotlin-compiler-embeddable-*.jar"))), None)
cache_options = [Path(os.environ["GRADLE_USER_HOME"]) / "caches" / "modules-2" / "files-2.1"] if os.environ.get("GRADLE_USER_HOME") else []
cache_options += [shared / "gradle-home" / "caches" / "modules-2" / "files-2.1", Path.home() / ".gradle" / "caches" / "modules-2" / "files-2.1"]

def dependency(pattern):
    for cache in cache_options:
        match = next(cache.glob(pattern), None)
        if match:
            return match
    sys.exit("Missing cached dependency: " + pattern + ". Run tools/build.sh once or set GRADLE_USER_HOME.")

if lib is None or not java.is_file():
    sys.exit("Kotlin/JDK unavailable. Set JAVA_HOME and KOTLIN_LIB to an installed JDK and Gradle lib directory.")
stdlib = next(lib.glob("kotlin-stdlib-[0-9]*.jar"))
coroutines = next(lib.glob("kotlinx-coroutines-core-jvm-*.jar"))
classpath = os.pathsep.join(map(str, [stdlib, coroutines,
    dependency("junit/junit/4.13.2/**/*.jar"), dependency("org.hamcrest/hamcrest-core/1.3/**/*.jar"),
    dependency("org.nanohttpd/nanohttpd/2.3.1/**/*.jar")]))
source_root = repo / "app/src/main/java/io/legado/app/model"
test_root = repo / "app/src/test/java/io/legado/app/model"
sources = sorted((source_root / "multisource/core").glob("*.kt"))
sources += [source_root / "aggregation" / name for name in (
    "AggregationPolicy.kt", "LocalCategoryPolicy.kt", "AggregateLoader.kt", "AggregateLoadingPolicy.kt"
)]
sources += [p for p in [source_root / "aggregation/AggregateRouteCache.kt", source_root / "multisource/SourceRequestGate.kt"] if p.is_file()]
tests = sorted((test_root / "multisource/core").glob("*Test.kt"))
for name in ["AggregateLoaderTest.kt", "AggregationPolicyTest.kt", "AggregateRouteCacheTest.kt"]:
    policy_test = test_root / "aggregation" / name
    if policy_test.is_file():
        tests.append(policy_test)
classes = []
for test in tests:
    body = test.read_text()
    package = re.search(r"^package\s+([\w.]+)", body, re.M).group(1)
    classes += [package + "." + name for name in re.findall(r"^class\s+(\w+Test)\b", body, re.M)]
output = repo / "build/core-jvm-tests"
output.mkdir(parents=True, exist_ok=True)
fingerprint = hashlib.sha256(b"".join(p.read_bytes() for p in sources + tests)).hexdigest()
print("Compiling", len(sources), "policy files and", len(tests), "test files; source SHA-256", fingerprint, flush=True)
subprocess.run([str(java), "-cp", str(lib / "*"), "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
    "-no-stdlib", "-no-reflect", "-jvm-target", "17", "-classpath", classpath,
    "-d", str(output)] + list(map(str, sources + tests)), check=True)
result = subprocess.run([str(java), "-cp", str(output) + os.pathsep + classpath,
    "org.junit.runner.JUnitCore"] + classes, text=True, capture_output=True)
report = "Source SHA-256: " + fingerprint + "\n" + result.stdout + result.stderr
(output / "result.txt").write_text(report)
print(result.stdout + result.stderr)
sys.exit(result.returncode)
