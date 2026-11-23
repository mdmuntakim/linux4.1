#!/usr/bin/env bash
# Fetch every upstream tarball and verify its SHA-256.
set -euo pipefail
. "$(dirname "$0")/lib.sh"

mkdir -p "$DL"

fetch() {
    local url=$1 sha=$2 file
    file="$DL/$(basename "$url")"
    if [ -f "$file" ] && echo "$sha  $file" | sha256sum -c --quiet - 2>/dev/null; then
        log "have $(basename "$file")"
        return
    fi
    log "downloading $(basename "$file")"
    curl -fsSL --retry 3 -o "$file.part" "$url"
    echo "$sha  $file.part" | sha256sum -c --quiet - ||
        die "checksum mismatch for $url"
    mv "$file.part" "$file"
}

fetch "$KERNEL_URL" "$KERNEL_SHA256"
fetch "$MUSL_URL" "$MUSL_SHA256"
fetch "$BUSYBOX_URL" "$BUSYBOX_SHA256"
fetch "$QEMU_URL" "$QEMU_SHA256"

log "sources in $DL"
sha256sum "$DL"/*.tar.* | sed 's#^#    #'
