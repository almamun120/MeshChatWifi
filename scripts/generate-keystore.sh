#!/usr/bin/env bash
# Creates a release keystore and prints the values to put in GitHub Secrets.
# Run ONCE, keep release.jks + passwords in a password manager. If you lose them you can never update the app
# for people who installed it (Android refuses updates signed with a different key).
set -euo pipefail
ALIAS="${1:-meshchat}"
OUT="${2:-release.jks}"
read -r -s -p "Keystore password (min 6 chars): " SP; echo
read -r -s -p "Key password (Enter = same): " KP; echo
KP="${KP:-$SP}"
keytool -genkeypair -v -keystore "$OUT" -alias "$ALIAS" -keyalg RSA -keysize 4096 -validity 10000 \
  -storepass "$SP" -keypass "$KP" -dname "CN=MeshChat, O=MeshChat, C=BD"
echo
echo "Add these four repository secrets (Settings > Secrets and variables > Actions):"
echo "  MESHCHAT_KEYSTORE_PASSWORD = (the keystore password you typed)"
echo "  MESHCHAT_KEY_ALIAS         = $ALIAS"
echo "  MESHCHAT_KEY_PASSWORD      = (the key password)"
echo "  MESHCHAT_KEYSTORE_BASE64   = contents of ${OUT}.base64 (written now)"
base64 -w0 "$OUT" > "${OUT}.base64" 2>/dev/null || base64 "$OUT" | tr -d '\n' > "${OUT}.base64"
echo "Do NOT commit $OUT or ${OUT}.base64 (they are in .gitignore)."
