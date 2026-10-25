"""Compile and execute every repository JVM test; build the debug app first."""
from pathlib import Path
import os
import subprocess

root = Path(__file__).resolve().parents[1]
out = root / "app/build/tmp/diagnostic-host-tests"
out.mkdir(parents=True, exist_ok=True)
cache = Path(os.environ.get("GRADLE_USER_HOME", str(Path.home() / ".gradle"))) / "caches"
sdk = Path(os.environ.get("ANDROID_HOME", str(Path.home() / "AppData/Local/Android/Sdk")))
android = sorted((sdk / "platforms").glob("*/android.jar"))[-1]
compiled = root / "app/build/intermediates/javac/debug/compileDebugJavaWithJavac/classes"
if not compiled.is_dir():
    raise SystemExit("Build the debug app first: powershell -File tools/build_tablet_debug.ps1")
jars = (list(cache.glob("*/transforms/**/classes.jar"))
        + list((cache / "modules-2/files-2.1").rglob("*.jar"))
        + list((root / "app/build/intermediates").glob("**/R.jar")))
jars = [p for p in jars if not p.name.endswith("-sources.jar")]
cp = os.pathsep.join(map(str, [compiled, android] + jars))
test_root = root / "app/src/test/java"
tests = sorted(test_root.rglob("*Test.java"))
if not tests:
    raise SystemExit("No host test classes discovered")
classes = [".".join(p.relative_to(test_root).with_suffix("").parts) for p in tests]
print(f"Discovered {len(classes)} host test classes", flush=True)
jdk = Path(os.environ.get("JAVA_HOME", "C:/Program Files/Android/Android Studio/jbr")) / "bin"


def run_java_argfile(program, filename, args, cwd=None):
    argfile = out / filename
    argfile.write_text("\n".join('"' + str(a).replace("\\", "/") + '"' for a in args), encoding="utf-8")
    result = subprocess.run([str(jdk / program), "@" + str(argfile)], cwd=cwd, text=True, capture_output=True)
    print(result.stdout + result.stderr, flush=True)
    if result.returncode:
        raise SystemExit(result.returncode)


run_java_argfile("javac.exe", "test-javac.args", ["-encoding", "UTF-8", "-proc:none", "-classpath", cp,
                 "-d", str(out / "tests")] + list(map(str, tests)))
(out / "test-temp").mkdir(exist_ok=True)
run_java_argfile("java.exe", "test-java.args", ["-Djava.io.tmpdir=" + str(out / "test-temp"),
                 "-classpath", str(out / "tests") + os.pathsep + cp, "org.junit.runner.JUnitCore"] + classes,
                 root / "app")
