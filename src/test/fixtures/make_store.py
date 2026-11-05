"""Builds src/test/testData/store with the real hunch CLI, so the plugin's reader is
tested against what hunch actually writes.

Usage: python make_store.py <path to a python that has hunch installed>

The runs: a baseline, a kept child with a note, a milestone covering those two, then a
quick crash and a finished sibling.
Machine-specific strings (user folder, host name) are scrubbed afterwards.
"""

import json
import os
import shutil
import socket
import subprocess
import sys
import tempfile
from pathlib import Path

PYTHON = sys.argv[1]
OUT = Path(__file__).resolve().parent.parent / "testData" / "store"

TRAIN = """import sys, hunch
lr = float(sys.argv[1]) if len(sys.argv) > 1 else 0.1
hunch.init(config={"lr": lr})
for step in range(5):
    hunch.log({"loss": 1.0 / (step + 1), "val_loss": 2.0 / (step + 1) * lr * 10})
if len(sys.argv) > 2:
    sys.exit(3)
"""


def hunch(project, *args):
    subprocess.run([PYTHON, "-m", "hunch", *args], cwd=project, check=True, capture_output=True)


def run(project, why, *script_args, check=True):
    proc = subprocess.run([PYTHON, "-m", "hunch", "run", "-m", why, "--", PYTHON, "train.py", *script_args],
                          cwd=project, capture_output=True)
    if check and proc.returncode != 0:
        raise SystemExit(proc.stderr.decode())


def newest(project):
    # Folder names sort by minute only, so use the start time inside run.json.
    runs = (project / ".hunch" / "runs").iterdir()
    return max(runs, key=lambda p: json.loads((p / "run.json").read_text(encoding="utf-8"))["started_at"]).name


with tempfile.TemporaryDirectory() as temp:
    project = Path(temp) / "project"
    project.mkdir()
    (project / "train.py").write_bytes(TRAIN.encode())
    run(project, "baseline", "0.1")
    baseline = newest(project)
    (project / "train.py").write_bytes(TRAIN.replace("step + 1", "step + 2").encode())
    run(project, "warmup", "0.1")
    warmup = newest(project)
    hunch(project, "note", warmup[-6:], "warmup helps, приятно", "--keep")
    hunch(project, "milestone", "warmup beats baseline", "-m", "the spike is gone")
    run(project, "typo", "0.1", "fail", check=False)
    run(project, "higher lr", "0.3")
    sibling = newest(project)
    hunch(project, "link", sibling[-6:], "--from", warmup[-6:])

    shutil.rmtree(OUT, ignore_errors=True)
    shutil.copytree(project / ".hunch", OUT / ".hunch")
    (OUT / ".hunch" / ".gitignore").unlink()  # it ignores everything, which would hide the fixture from git

home = str(Path.home())
host = socket.gethostname()
for path in OUT.rglob("*.json*"):
    text = path.read_text(encoding="utf-8")
    text = text.replace(json.dumps(home)[1:-1], "C:\\\\Users\\\\user").replace(home, "C:\\Users\\user").replace(host, "host")
    text = text.replace(json.dumps(temp)[1:-1], "C:\\\\work").replace(temp, "C:\\work")
    text = text.replace(Path.home().name, "user")  # forward-slash and short-name paths too
    path.write_text(text, encoding="utf-8")
for path in OUT.rglob("output.log"):
    path.write_text("done\n", encoding="utf-8")
print("wrote", OUT, sorted(p.name for p in (OUT / ".hunch" / "runs").iterdir()), file=sys.stderr)
