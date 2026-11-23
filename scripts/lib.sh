#!/usr/bin/env bash
# Shared helpers for the Linux4.1 build scripts.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=pins.env
. "$ROOT/scripts/pins.env"

OUT="${OUT:-$ROOT/out}"
CACHE="${CACHE:-$ROOT/.cache}"
JOBS="${JOBS:-$(getconf _NPROCESSORS_ONLN 2>/dev/null || echo 4)}"

log() { printf '==> %s\n' "$*" >&2; }
die() { printf 'error: %s\n' "$*" >&2; exit 1; }

# fetch URL DEST SHA256 -- download once, verify every time.
fetch() {
  local url="$1" dest="$2" sha="$3"
  mkdir -p "$(dirname "$dest")"
  if [ ! -f "$dest" ]; then
    log "downloading $url"
    curl -fL --retry 3 --connect-timeout 20 -o "$dest.part" "$url"
    mv "$dest.part" "$dest"
  fi
  local got
  got="$(sha256sum "$dest" | cut -d' ' -f1)"
  [ "$got" = "$sha" ] || die "sha256 mismatch for $dest: $got != $sha"
  log "verified $(basename "$dest")"
}

need() { command -v "$1" >/dev/null 2>&1 || die "missing command: $1"; }

# Extract a tarball once.
extract() {
  local archive="$1" dir="$2"
  if [ ! -d "$dir" ]; then
    log "extracting $(basename "$archive")"
    mkdir -p "$dir"
    tar -C "$dir" --strip-components=1 -xf "$archive"
  fi
}
