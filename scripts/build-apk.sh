#!/usr/bin/env bash
# Build the debug APK, copy it to a versioned path under dist/, and
# print enough metadata for sideloading (path, size, sha256, adb
# install command). This wrapper runs the full verify command
# (ktlintCheck + testDebugUnitTest + lintDebug + assembleDebug) so a
# single bash scripts/build-apk.sh gives you a known-good APK.

set -euo pipefail

# Resolve the repo root from this script's location so the wrapper
# works regardless of the caller's cwd.
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$REPO_ROOT"

# Source the toolchain env. We do not `set -e` inside env.sh (it is
# meant to be sourced), and we accept its side effects unconditionally.
# shellcheck disable=SC1091
source "$SCRIPT_DIR/env.sh"

# Stop any daemons left over from earlier runs so the verify command
# starts from a clean slate.
./gradlew --stop >/dev/null 2>&1 || true

# Full verify command. assembleDebug rebuilds app-debug.apk; the rest
# is the gate every task must pass.
./gradlew ktlintCheck testDebugUnitTest lintDebug assembleDebug

APK_SRC="app/build/outputs/apk/debug/app-debug.apk"
METADATA="app/build/outputs/apk/debug/output-metadata.json"

if [[ ! -f "$APK_SRC" ]]; then
  echo "build-apk: expected APK at $APK_SRC but it is not there." >&2
  exit 1
fi

if [[ ! -f "$METADATA" ]]; then
  echo "build-apk: expected $METADATA but it is not there." >&2
  exit 1
fi

# Extract versionName without taking a jq dependency.
version_name=$(grep -oP '"versionName"\s*:\s*"\K[^"]+' "$METADATA" || true)
if [[ -z "${version_name:-}" ]]; then
  echo "build-apk: could not read versionName out of $METADATA." >&2
  exit 1
fi

DIST_DIR="$REPO_ROOT/dist"
APK_DEST="$DIST_DIR/convoscribe-${version_name}-debug.apk"

mkdir -p "$DIST_DIR"

# Overwrite any previous copy at the same versioned path.
cp -f "$APK_SRC" "$APK_DEST"

size_bytes=$(stat -c '%s' "$APK_DEST")

if command -v numfmt >/dev/null 2>&1; then
  size_human=$(numfmt --to=iec "$size_bytes")
else
  # awk fallback: report as e.g. 97M, 1.2G, 532K.
  size_human=$(awk -v b="$size_bytes" 'BEGIN {
    split("K M G T", u, " ");
    n = b;
    i = 0;
    while (n >= 1024 && i < 3) {
      n = n / 1024;
      i++;
    }
    if (i == 0) {
      printf "%dB\n", n;
    } else {
      printf "%.1f%s\n", n, u[i];
    }
  }')
fi

sha256=$(sha256sum "$APK_DEST" | awk '{ print $1 }')

cat <<EOF
APK: $APK_DEST
Size: $size_bytes ($size_human)
sha256: $sha256

Install on a connected device with:
    adb install -r $APK_DEST
EOF

# Always stop daemons so the next invocation does not pay a stop-the-
# world penalty on the first Gradle call.
./gradlew --stop >/dev/null 2>&1 || true