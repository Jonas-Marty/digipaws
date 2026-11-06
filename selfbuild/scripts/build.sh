#!/usr/bin/env bash
# Builds, signs and verifies digipaws from the pinned upstream commit, entirely
# inside a Docker container. Nothing is installed on the host.
#
#   selfbuild/scripts/build.sh          # build, sign, verify -> selfbuild/out/
#   selfbuild/scripts/build.sh test     # unit tests only
#   selfbuild/scripts/build.sh keygen   # create the release keystore (once)
#   selfbuild/scripts/build.sh bash     # a shell in the build container
#
# This repo is upstream digipaws plus this selfbuild/ directory and our own
# patches. The build is refused unless every file outside selfbuild/ is exactly
# the audited COMMIT in UPSTREAM with selfbuild/patches/*.patch applied, in
# order — so merging upstream, or editing app code without updating the patch
# that describes it, stops the build until it is reviewed. The finished APK is
# rejected if it asks for INTERNET, the property the whole "trust it with
# Accessibility" argument rests on (see AUDIT.md).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"   # selfbuild/
REPO_ROOT="$(cd "$ROOT/.." && pwd)"
# shellcheck source=../UPSTREAM
source "$ROOT/UPSTREAM"

IMAGE="digipaws-android:latest"
GRADLE_VOLUME="digipaws-gradle"
KEYSTORE_DIR="${DIGIPAWS_KEYSTORE_DIR:-$HOME/.keystores}"
KEYSTORE="$KEYSTORE_DIR/digipaws.jks"
KEYPASS_FILE="$KEYSTORE_DIR/digipaws.pass"
KEY_ALIAS="digipaws"

docker build -q \
    --build-arg "USER_UID=$(id -u)" \
    --build-arg "USER_GID=$(id -g)" \
    -t "$IMAGE" \
    "$ROOT/docker" >/dev/null

docker volume inspect "$GRADLE_VOLUME" >/dev/null 2>&1 || docker volume create "$GRADLE_VOLUME" >/dev/null

run_args=(
    --rm -i
    -v "$REPO_ROOT:/workspace"
    -v "$GRADLE_VOLUME:/home/dev/.gradle"
    -w /workspace/selfbuild
)
[ -t 0 ] && run_args+=(-t)
in_container() { docker run "${run_args[@]}" "$@" "$IMAGE" bash -euo pipefail -c "$CMD"; }

# Tree of COMMIT + patches vs. tree of the working copy, both outside
# selfbuild/. Temporary indexes keep the real index and working tree untouched;
# `git add` honours .gitignore, so build output never counts.
check_source() {
    local patches=("$ROOT"/patches/*.patch) tmp expected actual
    [ -e "${patches[0]}" ] || patches=()
    echo "==> source must equal $REPO @ ${COMMIT:0:7} + ${#patches[@]} patch(es)"
    git -C "$REPO_ROOT" cat-file -e "$COMMIT^{commit}" 2>/dev/null || git -C "$REPO_ROOT" fetch -q upstream
    tmp="$(mktemp -d)"
    GIT_INDEX_FILE="$tmp/expected" git -C "$REPO_ROOT" read-tree "$COMMIT"
    for patch in "${patches[@]}"; do
        if ! GIT_INDEX_FILE="$tmp/expected" git -C "$REPO_ROOT" apply --cached "$patch"; then
            echo "error: $(basename "$patch") no longer applies to ${COMMIT:0:7}; rebase it." >&2
            rm -rf "$tmp"; exit 1
        fi
    done
    expected="$(GIT_INDEX_FILE="$tmp/expected" git -C "$REPO_ROOT" write-tree)"
    GIT_INDEX_FILE="$tmp/actual" git -C "$REPO_ROOT" read-tree "$COMMIT"
    GIT_INDEX_FILE="$tmp/actual" git -C "$REPO_ROOT" add -A -- . ':(exclude)selfbuild'
    actual="$(GIT_INDEX_FILE="$tmp/actual" git -C "$REPO_ROOT" write-tree)"
    rm -rf "$tmp"
    if [ "$expected" != "$actual" ]; then
        echo "error: the app source is not ${COMMIT:0:7} + selfbuild/patches. Difference:" >&2
        git -C "$REPO_ROOT" diff --stat "$expected" "$actual" >&2
        echo "Audit the change (AUDIT.md); then update COMMIT in UPSTREAM or regenerate the patch." >&2
        exit 1
    fi
}

flavor_cap="$(tr '[:lower:]' '[:upper:]' <<<"${FLAVOR:0:1}")${FLAVOR:1}"

case "${1:-}" in
    test)
        check_source
        echo "==> gradle test${flavor_cap}DebugUnitTest"
        CMD="cd .. && ./gradlew --no-daemon -q test${flavor_cap}DebugUnitTest && echo 'unit tests passed'"
        in_container
        exit 0
        ;;
    bash)
        exec docker run "${run_args[@]}" "$IMAGE" bash
        ;;
    keygen)
        if [ -f "$KEYSTORE" ]; then
            echo "keystore already exists: $KEYSTORE — refusing to overwrite." >&2
            echo "A new key means the phone must uninstall before it accepts the next build." >&2
            exit 1
        fi
        mkdir -p "$KEYSTORE_DIR" && chmod 700 "$KEYSTORE_DIR"
        (umask 077; head -c 32 /dev/urandom | base64 | tr -d '/+=\n' > "$KEYPASS_FILE")
        CMD="keytool -genkeypair -keystore /keys/digipaws.jks -alias $KEY_ALIAS \
                -keyalg RSA -keysize 4096 -validity 10000 -dname CN=digipaws-selfbuild \
                -storepass:file /keys/digipaws.pass -keypass:file /keys/digipaws.pass"
        in_container -v "$KEYSTORE_DIR:/keys"
        chmod 600 "$KEYSTORE"
        echo "==> created $KEYSTORE (password in $KEYPASS_FILE). Back both up."
        exit 0
        ;;
    "") ;;
    *) echo "unknown command: $1" >&2; exit 2 ;;
esac

[ -f "$KEYSTORE" ] && [ -f "$KEYPASS_FILE" ] || {
    echo "error: no keystore at $KEYSTORE — run scripts/build.sh keygen first" >&2
    exit 1
}

check_source

echo "==> gradle assemble${flavor_cap}Release (first run downloads a lot)"
CMD="cd .. && ./gradlew --no-daemon -q clean assemble${flavor_cap}Release"
in_container

echo "==> sign and verify"
mkdir -p "$ROOT/out"
CMD="
unsigned=\$(find ../app/build/outputs/apk/$FLAVOR/release -name '*.apk' | head -1)
[ -n \"\$unsigned\" ] || { echo 'no unsigned APK produced' >&2; exit 1; }
zipalign -c -p 4 \"\$unsigned\" || { zipalign -f -p 4 \"\$unsigned\" /tmp/aligned.apk; unsigned=/tmp/aligned.apk; }
apksigner sign --v4-signing-enabled false --ks /keys/digipaws.jks --ks-key-alias $KEY_ALIAS \
    --ks-pass file:/keys/digipaws.pass \
    --out out/digipaws-signed.apk \"\$unsigned\"
apksigner verify out/digipaws-signed.apk
aapt2 dump permissions out/digipaws-signed.apk | sed -n 's/^uses-permission: name=.\(.*\).\$/\1/p' | sort > out/permissions.txt
aapt2 dump badging out/digipaws-signed.apk | sed -n 1p > out/badging.txt
apksigner verify --print-certs out/digipaws-signed.apk | grep -i 'SHA-256' > out/cert.txt
"
in_container -v "$KEYSTORE_DIR:/keys:ro"

# The INTERNET check: no network permission, no way to phone home on its own.
if grep -qE 'android\.permission\.INTERNET$' "$ROOT/out/permissions.txt"; then
    echo "error: the built APK requests INTERNET — upstream changed; re-audit before using." >&2
    rm -f "$ROOT/out/digipaws-signed.apk"
    exit 1
fi

version_name="$(sed -n "s/.*versionName='\([^']*\)'.*/\1/p" "$ROOT/out/badging.txt")"
version_code="$(sed -n "s/.*versionCode='\([^']*\)'.*/\1/p" "$ROOT/out/badging.txt")"
# Upstream commit, then this repo's commit: the second one names the patches.
fork="$(git -C "$REPO_ROOT" rev-parse --short=7 HEAD)"
[ -n "$(git -C "$REPO_ROOT" status --porcelain)" ] && fork="$fork-dirty"
NAME="digipaws-${version_name}-${version_code}-${COMMIT:0:7}-${fork}.apk"
mv "$ROOT/out/digipaws-signed.apk" "$ROOT/out/$NAME"
sha256sum "$ROOT/out/$NAME" | cut -d' ' -f1 > "$ROOT/out/$NAME.sha256"

echo
echo "==> $ROOT/out/$NAME"
echo "    sha256 $(cat "$ROOT/out/$NAME.sha256")"
echo "    signer $(cat "$ROOT/out/cert.txt")"
echo "    permissions (no INTERNET):"
sed 's/^/      /' "$ROOT/out/permissions.txt"
echo "$NAME" > "$ROOT/out/LATEST"
