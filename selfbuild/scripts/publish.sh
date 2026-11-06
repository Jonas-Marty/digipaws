#!/usr/bin/env bash
# Builds digipaws and publishes it on the APK host the phone already uses for
# FinReader (https://apk.wi-wo.ch, behind Authentik), then sends a Gotify link.
#
#   selfbuild/scripts/publish.sh            # build, publish, notify
#   selfbuild/scripts/publish.sh --quiet    # build and publish, no notification
#   selfbuild/scripts/publish.sh --no-build # publish what is in out/ (skips the build)
#
# The host, its nginx and the index generator belong to fin-reader-app; see
# /work/fin-reader-app/deploy/apk-host.compose.yml and scripts/apk-index.sh.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PUBLISH_DIR="${DIGIPAWS_PUBLISH_DIR:-$HOME/finreader-apk}"
BASE_URL="${DIGIPAWS_APK_URL:-https://apk.wi-wo.ch}"
INDEX_SCRIPT="${DIGIPAWS_INDEX_SCRIPT:-/work/fin-reader-app/scripts/apk-index.sh}"
GOTIFY_URL="${DIGIPAWS_GOTIFY_URL:-https://gotify.wi-wo.ch}"
# The FinReader Gotify app token is reused; the title says which app it is.
GOTIFY_TOKEN_FILE="${DIGIPAWS_GOTIFY_TOKEN_FILE:-$HOME/.config/finreader/gotify-token}"
KEEP="${DIGIPAWS_KEEP_BUILDS:-5}"

notify=true build=true
for arg in "$@"; do
    case "$arg" in
        --quiet) notify=false ;;
        --no-build) build=false ;;
        *) echo "unknown option: $arg" >&2; exit 2 ;;
    esac
done

[ "$build" = true ] && "$ROOT/scripts/build.sh"

NAME="$(cat "$ROOT/out/LATEST" 2>/dev/null || true)"
[ -n "$NAME" ] && [ -f "$ROOT/out/$NAME" ] || { echo "error: no built APK in out/" >&2; exit 1; }
# build.sh already refuses this, but out/ may hold something older than that check.
if grep -qE 'android\.permission\.INTERNET$' "$ROOT/out/permissions.txt"; then
    echo "error: out/$NAME requests INTERNET — not publishing" >&2
    exit 1
fi

mkdir -p "$PUBLISH_DIR"
cp "$ROOT/out/$NAME" "$PUBLISH_DIR/$NAME"
chmod 644 "$PUBLISH_DIR/$NAME"
ln -sfn "$NAME" "$PUBLISH_DIR/digipaws-latest.apk"
DIGEST="$(sha256sum "$PUBLISH_DIR/$NAME" | cut -d' ' -f1)"
SIZE="$(du -h "$PUBLISH_DIR/$NAME" | cut -f1)"

ls -t "$PUBLISH_DIR"/digipaws-*-*.apk 2>/dev/null | tail -n "+$((KEEP + 1))" | while read -r old; do
    [ -L "$old" ] && continue
    rm -f "$old"
done

"$INDEX_SCRIPT" "$PUBLISH_DIR"

echo "==> published $NAME ($SIZE)"
echo "    $BASE_URL/$NAME"
echo "    sha256 $DIGEST"

[ "$notify" = true ] || exit 0
if [ ! -r "$GOTIFY_TOKEN_FILE" ]; then
    echo "warning: no Gotify token at $GOTIFY_TOKEN_FILE — skipping notification" >&2
    exit 0
fi
python3 - "$GOTIFY_URL" "$(cat "$GOTIFY_TOKEN_FILE")" "$NAME" "$BASE_URL/$NAME" "$SIZE" "$BASE_URL/#digipaws" <<'PY' || echo "warning: notification failed" >&2
import json, sys, urllib.request
url, token, name, link, size, index = sys.argv[1:7]
body = json.dumps({
    "title": f"Digipaws {name.removeprefix('digipaws-').removesuffix('.apk')}",
    "message": f"[Download {name}]({link}) — {size}\n\nTap the file to install. All builds: {index}",
    "priority": 5,
    "extras": {
        "client::display": {"contentType": "text/markdown"},
        "client::notification": {"click": {"url": link}},
    },
}).encode()
req = urllib.request.Request(
    f"{url}/message", data=body,
    headers={"Content-Type": "application/json", "X-Gotify-Key": token},
)
urllib.request.urlopen(req, timeout=15).read()
print("==> notified via Gotify")
PY
