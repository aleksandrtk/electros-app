#!/usr/bin/env bash
# One-time: creates the Electros release signing key, stores its password in the macOS Keychain
# and builds the rotation lineage (old debug key -> new release key) so installed apps keep updating.
# The keystore lives outside the repository. BACK IT UP: without it no further updates can be signed.
set -euo pipefail

SIGN_DIR="${ELECTROS_SIGNING_DIR:-$HOME/.electros-signing}"
KEYSTORE="$SIGN_DIR/electros-release.p12"
LINEAGE="$SIGN_DIR/electros-rotation.lineage"
ALIAS="electros-release"
KEYCHAIN_SERVICE="electros-release-keystore"
DEBUG_KEYSTORE="$HOME/.android/debug.keystore"
APKSIGNER="$(ls -d "$HOME"/Library/Android/sdk/build-tools/*/apksigner | sort -V | tail -1)"

if [[ -e "$KEYSTORE" ]]; then
    echo "Keystore already exists: $KEYSTORE — refusing to overwrite." >&2
    exit 1
fi
[[ -f "$DEBUG_KEYSTORE" ]] || { echo "Debug keystore not found: $DEBUG_KEYSTORE" >&2; exit 1; }
[[ -x "$APKSIGNER" ]] || { echo "apksigner not found in Android SDK build-tools" >&2; exit 1; }

mkdir -p "$SIGN_DIR"
chmod 700 "$SIGN_DIR"

read -rs -p "Пароль для release-ключа (минимум 12 символов): " PASS1; echo
read -rs -p "Повторите пароль: " PASS2; echo
if [[ "$PASS1" != "$PASS2" ]]; then echo "Пароли не совпадают." >&2; exit 1; fi
if (( ${#PASS1} < 12 )); then echo "Пароль короче 12 символов." >&2; exit 1; fi
export ELECTROS_KS_PASS="$PASS1"
unset PASS1 PASS2

keytool -genkeypair \
    -keystore "$KEYSTORE" -storetype PKCS12 \
    -alias "$ALIAS" -keyalg RSA -keysize 4096 -validity 10000 \
    -dname "CN=Electros EV, O=aleksandrtk" \
    -storepass:env ELECTROS_KS_PASS
chmod 600 "$KEYSTORE"

security add-generic-password -U -a "$ALIAS" -s "$KEYCHAIN_SERVICE" \
    -l "Electros release keystore password" -w "$ELECTROS_KS_PASS"

"$APKSIGNER" rotate --out "$LINEAGE" \
    --old-signer --ks "$DEBUG_KEYSTORE" --ks-key-alias androiddebugkey --ks-pass pass:android \
    --new-signer --ks "$KEYSTORE" --ks-key-alias "$ALIAS" --ks-pass env:ELECTROS_KS_PASS
chmod 600 "$LINEAGE"
unset ELECTROS_KS_PASS

echo
echo "Готово."
echo "  Keystore: $KEYSTORE"
echo "  Lineage:  $LINEAGE"
echo "  Пароль сохранён в Связке ключей (служба: $KEYCHAIN_SERVICE)."
echo "  Сделайте резервную копию папки $SIGN_DIR и пароля (например, в менеджер паролей)."
