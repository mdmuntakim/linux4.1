#!/usr/bin/env bash
# Build a static aarch64 QEMU (x86_64 guest) for the Android app.
# Runs the real build inside an arm64 Alpine container; on an arm64 host the
# container is native, on the x86_64 CI runner it uses binfmt (setup-qemu).
set -euo pipefail
. "$(dirname "$0")/lib.sh"

need docker
need sha256sum

if [ "$(uname -m)" = "aarch64" ]; then
  log "arm64 host: building natively"
  sh "$ROOT/scripts/qemu-android-inner.sh"
  exit 0
fi

log "x86_64 host: running the build in an arm64 Alpine container"
docker run --rm --platform linux/arm64 \
  -v "$ROOT:/w" -w /w \
  -e OUT=/w/out -e CACHE=/w/.cache -e JOBS="${JOBS}" \
  alpine:3.20 /bin/sh /w/scripts/qemu-android-inner.sh
