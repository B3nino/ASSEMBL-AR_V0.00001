"""Optional same-device A/B fixture: compile the committed Phase 5 matcher under
a test-only name and append its DEX to an already prepared candidate archive.
No production sources, learner APK, preferences, or model thresholds are changed.
"""
from pathlib import Path
import argparse
import hashlib
import json
import os
import subprocess
import zipfile

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--revision", default="HEAD", help="Committed matcher revision to compare")
options = parser.parse_args()
root = Path(__file__).resolve().parents[1]
out = root / "app/build/tmp/phase5-comparison"
out.mkdir(parents=True, exist_ok=True)
revision = subprocess.check_output(["git", "rev-parse", "--verify", options.revision], cwd=root, text=True).strip()
relative = "app/src/main/java/dev/chyken/assembl_ar/vision/Phase5ReferenceMatcher.java"
original = subprocess.check_output(["git", "show", revision + ":" + relative], cwd=root, encoding="utf-8")
source = out / "BaselinePhase5ReferenceMatcher.java"
source.write_text(original.replace("Phase5ReferenceMatcher", "BaselinePhase5ReferenceMatcher"), encoding="utf-8")
args = [line[1:-1] for line in (root / "app/build/tmp/diagnostic-host-tests/test-javac.args").read_text().splitlines()]
classpath = args[args.index("-classpath") + 1]
jdk = Path(os.environ.get("JAVA_HOME", "C:/Program Files/Android/Android Studio/jbr")) / "bin"
sdk = Path(os.environ.get("ANDROID_HOME", "C:/Users/Hem Bendijo/AppData/Local/Android/Sdk"))
classes = out / "classes"
classes.mkdir(exist_ok=True)

def run(program, filename, arguments):
    argfile = out / filename
    argfile.write_text("\n".join('"' + str(a).replace("\\", "/") + '"' for a in arguments), encoding="utf-8")
    subprocess.run([str(jdk / program), "@" + str(argfile)], cwd=root, check=True)

run("javac.exe", "compile.args", ["--release", "17", "-proc:none", "-encoding", "UTF-8", "-classpath", classpath, "-d", classes, source])
baseline = out / "baseline.jar"
with zipfile.ZipFile(baseline, "w") as jar:
    for path in classes.rglob("*.class"):
        jar.write(path, path.relative_to(classes).as_posix())
application = out / "application.jar"
compiled = root / "app/build/intermediates/javac/debug/compileDebugJavaWithJavac/classes"
with zipfile.ZipFile(application, "w") as jar:
    for path in compiled.rglob("*.class"):
        jar.write(path, path.relative_to(compiled).as_posix())
d8 = sorted(sdk.glob("build-tools/*/lib/d8.jar"))[-1]
android = sorted(sdk.glob("platforms/*/android.jar"))[-1]
dex = out / "dex"
dex.mkdir(exist_ok=True)
arguments = ["-cp", d8, "com.android.tools.r8.D8", "--min-api", "26", "--lib", android, "--classpath", application, "--output", dex]
for path in classpath.split(os.pathsep):
    if "opencv" in path.lower() and path.endswith(".jar"):
        arguments.extend(["--classpath", path])
arguments.append(baseline)
run("java.exe", "dex.args", arguments)
candidate = root / "app/build/tmp/tablet-audit/candidate-code.zip"
with zipfile.ZipFile(candidate) as archive:
    if "classes250.dex" in archive.namelist():
        raise SystemExit("Prepare a fresh candidate archive before appending comparison code.")
with zipfile.ZipFile(candidate, "a", zipfile.ZIP_DEFLATED) as archive:
    archive.write(dex / "classes.dex", "classes250.dex")
metadata = dict(revision=revision, source_sha256=hashlib.sha256(original.encode()).hexdigest(),
                candidate_sha256=hashlib.sha256(candidate.read_bytes()).hexdigest(),
                scope="Matcher-only comparison; both versions use current shared helpers and identical real pixels")
(out / "provenance.json").write_text(json.dumps(metadata, indent=2))
print(json.dumps(metadata))
