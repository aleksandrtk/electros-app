#!/usr/bin/env bash
# Builds the release APK and signs it with the Electros release key. The rotation lineage
# (old debug key -> release key) is embedded, so installs signed with the old key accept the update.
# Usage: scripts/sign-release.sh [output.apk]
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SIGN_DIR="${ELECTROS_SIGNING_DIR:-$HOME/.electros-signing}"
KEYSTORE="$SIGN_DIR/electros-release.p12"
LINEAGE="$SIGN_DIR/electros-rotation.lineage"
ALIAS="electros-release"
KEYCHAIN_SERVICE="electros-release-keystore"
BUILD_TOOLS="$(ls -d "$HOME"/Library/Android/sdk/build-tools/* | sort -V | tail -1)"
UNSIGNED="$ROOT/app/build/outputs/apk/release/app-release-unsigned.apk"
ALIGNED="$ROOT/app/build/outputs/apk/release/app-release-aligned.apk"
OUT="${1:-$ROOT/app/build/outputs/apk/release/app-release-signed.apk}"

for f in "$KEYSTORE" "$LINEAGE"; do
    [[ -f "$f" ]] || { echo "Missing $f (run scripts/create-release-keystore.sh first)" >&2; exit 1; }
done

# Build without Gradle signing, and before the password is read, so Gradle and its plugins never see it.
(cd "$ROOT" && env -u ELECTROS_KEYSTORE ./gradlew --offline clean assembleRelease --console=plain)

"$BUILD_TOOLS/zipalign" -P 16 -f 4 "$UNSIGNED" "$ALIGNED"

ELECTROS_KS_PASS="$(security find-generic-password -s "$KEYCHAIN_SERVICE" -a "$ALIAS" -w)"
export ELECTROS_KS_PASS
# minSdk 37 verifies v3 natively, so the new key plus the lineage file is enough (v2 would need the old key).
"$BUILD_TOOLS/apksigner" sign \
    --v1-signing-enabled false --v2-signing-enabled false --v3-signing-enabled true \
    --ks "$KEYSTORE" --ks-key-alias "$ALIAS" --ks-pass env:ELECTROS_KS_PASS \
    --lineage "$LINEAGE" \
    --out "$OUT" "$ALIGNED"
unset ELECTROS_KS_PASS
rm -f "$ALIGNED"

"$BUILD_TOOLS/apksigner" verify -v --print-certs "$OUT" | grep -E "Verified using|Signer #|SHA-256 digest" || true
echo "Signed APK: $OUT"
