#!/usr/bin/env bash
set -euo pipefail

readonly upstream_repository="https://github.com/lightpanda-io/browser.git"
readonly upstream_revision="bbcc2f795d23d891dbbcd84f48773ea9be07fb63"
readonly upstream_pull_request="3096"

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source_directory="${LIGHTPANDA_SOURCE_DIR:-${HOME}/.cache/diglet/lightpanda-c-api}"

if [[ "$(uname -s)" != "Linux" || "$(uname -m)" != "x86_64" ]]; then
    printf 'The embedded Lightpanda build is currently verified only on Linux x86_64.\n' >&2
    exit 1
fi

for tool in cargo curl git make readelf zig; do
    if ! command -v "$tool" >/dev/null 2>&1; then
        printf 'Required tool is missing: %s\n' "$tool" >&2
        exit 1
    fi
done

if [[ ! -d "${source_directory}/.git" ]]; then
    if [[ -e "$source_directory" ]]; then
        printf 'Native build path exists but is not a Git checkout: %s\n' "$source_directory" >&2
        exit 1
    fi
    mkdir -p "$(dirname "$source_directory")"
    git clone --filter=blob:none --no-checkout "$upstream_repository" "$source_directory" >&2
fi

git -C "$source_directory" fetch --no-tags --depth=1 origin "refs/pull/${upstream_pull_request}/head" >&2
resolved_revision="$(git -C "$source_directory" rev-parse FETCH_HEAD)"
if [[ "$resolved_revision" != "$upstream_revision" ]]; then
    printf 'Upstream PR #%s moved: expected %s, received %s. Review and update the pinned revision.\n' \
        "$upstream_pull_request" "$upstream_revision" "$resolved_revision" >&2
    exit 1
fi

git -C "$source_directory" checkout --detach "$upstream_revision" >&2
mkdir -p "${ZIG_GLOBAL_CACHE_DIR:-${HOME}/.cache/zig}/tmp"

(
    cd "$source_directory"
    make download-v8 >&2
    zig build lib -Ddev_fast=false -Doptimize=ReleaseFast >&2
)

library_path="${source_directory}/zig-out/lib/liblightpanda.so"
if [[ ! -f "$library_path" ]]; then
    printf 'The upstream build did not produce %s\n' "$library_path" >&2
    exit 1
fi

if readelf -d "$library_path" | grep -q 'STATIC_TLS'; then
    printf 'The native library uses static TLS and cannot be late-loaded into the JVM.\n' >&2
    exit 1
fi
if readelf -r "$library_path" | grep -q 'TPOFF64'; then
    printf 'The native library contains an initial-exec TLS relocation and cannot be late-loaded into the JVM.\n' >&2
    exit 1
fi

printf '%s\n' "$library_path"
