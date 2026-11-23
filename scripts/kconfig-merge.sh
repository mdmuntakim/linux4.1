#!/usr/bin/env bash
# Merge a kernel config fragment into an existing .config.
#
# Uses the kernel's own merge_config.sh when the tree has it and a strict
# fallback otherwise, so the same script works for 4.1 and for newer trees.
set -euo pipefail
config=$1
fragment=$2
[ -f "$config" ] || { echo "missing $config" >&2; exit 1; }
[ -f "$fragment" ] || { echo "missing $fragment" >&2; exit 1; }

if [ -x scripts/kconfig/merge_config.sh ]; then
    scripts/kconfig/merge_config.sh -m -O . "$config" "$fragment" >/dev/null
    exit 0
fi

python3 - "$config" "$fragment" <<'PY'
import sys

config, fragment = sys.argv[1], sys.argv[2]
lines = [l.rstrip("\n") for l in open(config)]
frag = [l.rstrip("\n") for l in open(fragment)]

def key(line):
    if line.startswith("CONFIG_"):
        return line.split("=", 1)[0].split(" ", 1)[0][7:]
    if line.startswith("# CONFIG_"):
        return line.split(" ", 2)[2]
    return None

for fl in frag:
    k = key(fl)
    if not k:
        continue
    out, done = [], False
    for cl in lines:
        if key(cl) == k:
            if not done:
                out.append(fl)
                done = True
            continue
        out.append(cl)
    if not done:
        out.append(fl)
    lines = out
open(config, "w").write("\n".join(lines) + "\n")
PY
