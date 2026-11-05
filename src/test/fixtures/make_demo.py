"""Builds a demo project (default: ../hunch-demo next to this repo) to try the plugin with runIde.

Usage: python make_demo.py <python with hunch installed> [output folder]

Records a week of made-up experiments with the real hunch CLI, editing train.py between runs
so diffs are real, then spreads start times over days so the tree reads like real work.
"""

import json
import shutil
import subprocess
import sys
from datetime import datetime, timedelta
from pathlib import Path

PYTHON = sys.argv[1]
OUT = Path(sys.argv[2]) if len(sys.argv) > 2 else Path(__file__).resolve().parents[4] / "hunch-demo"

TRAIN = '''import argparse, math, random, sys
import hunch

parser = argparse.ArgumentParser()
parser.add_argument("--lr", type=float, default=3e-4)
parser.add_argument("--batch", type=int, default=256)
args = parser.parse_args()

WARMUP = {warmup}
SCHEDULE = "{schedule}"
LABEL_SMOOTHING = {smoothing}
FINAL = {final}
DIVERGE = {diverge}

hunch.init(config={{"lr": args.lr, "batch": args.batch, "warmup": WARMUP, "schedule": SCHEDULE,
                   "label_smoothing": LABEL_SMOOTHING}})
random.seed(args.batch + int(args.lr * 1e5))
for step in range(0, 6000, 50):
    loss = FINAL + 1.6 * math.exp(-step / 900) + random.uniform(-0.02, 0.02)
    if DIVERGE and step > 3000:
        loss += (step - 3000) / 300
    hunch.log({{"train_loss": loss - 0.15, "val_loss": loss}}, step=step)
if DIVERGE:
    print("loss is nan at step 3100", file=sys.stderr)
    sys.exit(1)
'''

# (why, parent why, code, args, verdict, note, days after start, minutes it took)
RUNS = [
    ("baseline", None, dict(warmup=0, schedule="constant", smoothing=0.0, final=2.45, diverge=False), ["--lr", "3e-4"], None, None, 0, 44),
    ("warmup 1k steps", "baseline", dict(warmup=1000, schedule="constant", smoothing=0.0, final=2.31, diverge=False), ["--lr", "3e-4"], "keep", "warmup removes the spike at 2k", 1.1, 46),
    ("warmup 2k, lr 6e-4", "warmup 1k steps", dict(warmup=2000, schedule="constant", smoothing=0.0, final=2.4, diverge=True), ["--lr", "6e-4"], "drop", "too hot, diverged", 1.9, 23),
    ("warmup + cosine decay", "warmup 1k steps", dict(warmup=1000, schedule="cosine", smoothing=0.0, final=2.29, diverge=False), ["--lr", "3e-4"], "keep", "cosine helps a bit", 2.8, 47),
    ("label smoothing 0.1", "warmup + cosine decay", dict(warmup=1000, schedule="cosine", smoothing=0.1, final=2.33, diverge=False), ["--lr", "3e-4"], "drop", "worse on val", 3.7, 47),
    ("bigger batch 512", "warmup + cosine decay", dict(warmup=1000, schedule="cosine", smoothing=0.0, final=2.24, diverge=False), ["--lr", "3e-4", "--batch", "512"], "keep", "best so far", 4.6, 39),
    ("batch 512, lr 4e-4", "bigger batch 512", dict(warmup=1000, schedule="cosine", smoothing=0.0, final=2.25, diverge=False), ["--lr", "4e-4", "--batch", "512"], None, None, 5.5, 38),
]


def hunch(*args):
    subprocess.run([PYTHON, "-m", "hunch", *args], cwd=OUT, check=True, capture_output=True)


shutil.rmtree(OUT, ignore_errors=True)
OUT.mkdir(parents=True)
ids = {}
start = datetime.now().astimezone().replace(hour=10, minute=0, second=0, microsecond=0) - timedelta(days=7)
for why, parent, code, args, verdict, note, day, minutes in RUNS:
    (OUT / "train.py").write_text(TRAIN.format(**code), encoding="utf-8")
    command = [PYTHON, "-m", "hunch", "run", "-m", why]
    if parent:
        command += ["--from", ids[parent][-6:]]
    subprocess.run([*command, "--", PYTHON, "train.py", *args], cwd=OUT, capture_output=True)
    run_dir = max((OUT / ".hunch" / "runs").iterdir(),
                  key=lambda p: json.loads((p / "run.json").read_text(encoding="utf-8"))["started_at"])
    ids[why] = run_dir.name
    if verdict:
        hunch("note", run_dir.name[-6:], *([note] if note else []), f"--{verdict}")
    # Make it look like a week of real training runs.
    started = start + timedelta(days=day)
    meta = json.loads((run_dir / "run.json").read_text(encoding="utf-8"))
    meta["started_at"] = started.isoformat(timespec="milliseconds")
    (run_dir / "run.json").write_text(json.dumps(meta, indent=2) + "\n", encoding="utf-8")
    end = json.loads((run_dir / "end.json").read_text(encoding="utf-8"))
    end["duration"] = minutes * 60.0
    end["ended_at"] = (started + timedelta(minutes=minutes)).isoformat(timespec="milliseconds")
    (run_dir / "end.json").write_text(json.dumps(end, indent=2) + "\n", encoding="utf-8")

print("demo project:", OUT)
