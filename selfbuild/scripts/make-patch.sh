#!/usr/bin/env bash
# Writes the app changes not yet covered by earlier patches into one patch file.
#
#   selfbuild/scripts/make-patch.sh 0002-active-status-and-end-now
#
# Base = UPSTREAM's COMMIT with every OTHER patch in selfbuild/patches applied in
# order; the patch is the difference from there to the working copy (outside
# selfbuild/, untracked files included, .gitignore honoured). Use it for a new,
# highest-numbered patch, or to refresh the newest one after editing its code.
# Rewriting an older patch this way would fold later patches into it.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPO_ROOT="$(cd "$ROOT/.." && pwd)"
# shellcheck source=../UPSTREAM
source "$ROOT/UPSTREAM"

name="${1:?usage: make-patch.sh NNNN-short-name}"
name="${name%.patch}"
target="$ROOT/patches/$name.patch"
mkdir -p "$ROOT/patches"

for p in "$ROOT"/patches/*.patch; do
    [ -e "$p" ] || continue
    [ "$p" = "$target" ] && continue
    if [[ "$(basename "$p")" > "$name.patch" ]]; then
        echo "error: $(basename "$p") sorts after $name.patch; only the newest patch can be (re)made." >&2
        exit 1
    fi
done

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
GIT_INDEX_FILE="$tmp/base" git -C "$REPO_ROOT" read-tree "$COMMIT"
for p in "$ROOT"/patches/*.patch; do
    [ -e "$p" ] && [ "$p" != "$target" ] || continue
    GIT_INDEX_FILE="$tmp/base" git -C "$REPO_ROOT" apply --cached "$p"
done
base="$(GIT_INDEX_FILE="$tmp/base" git -C "$REPO_ROOT" write-tree)"

GIT_INDEX_FILE="$tmp/actual" git -C "$REPO_ROOT" read-tree "$COMMIT"
GIT_INDEX_FILE="$tmp/actual" git -C "$REPO_ROOT" add -A -- . ':(exclude)selfbuild'
actual="$(GIT_INDEX_FILE="$tmp/actual" git -C "$REPO_ROOT" write-tree)"

if [ "$base" = "$actual" ]; then
    echo "nothing to put in $name.patch: the code equals the other patches." >&2
    exit 1
fi
git -C "$REPO_ROOT" diff --binary "$base" "$actual" > "$target"
echo "==> wrote selfbuild/patches/$name.patch"
git -C "$REPO_ROOT" diff --stat "$base" "$actual"
