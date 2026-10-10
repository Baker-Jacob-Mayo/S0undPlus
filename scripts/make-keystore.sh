#!/usr/bin/env bash
# Creates the signing key S0undPlus releases are signed with, and prints the GitHub secrets to add.
#
#   scripts/make-keystore.sh                      # prompts for a password
#   KEYSTORE_PASSWORD=... scripts/make-keystore.sh [output.jks] [alias]
#
# KEEP THE .jks FILE AND PASSWORD SAFE AND PRIVATE. If you lose them you can never publish an update that installs
# over existing installs. Never commit the keystore (*.jks is in .gitignore).
set -euo pipefail

OUT="${1:-s0undplus-release.jks}"
ALIAS="${2:-s0undplus}"

if [ -e "$OUT" ]; then
  echo "Refusing to overwrite existing $OUT" >&2
  exit 1
fi
if [ -z "${KEYSTORE_PASSWORD:-}" ]; then
  read -rsp "Choose a keystore password (6+ characters): " KEYSTORE_PASSWORD
  echo
fi
if [ "${#KEYSTORE_PASSWORD}" -lt 6 ]; then
  echo "Password must be at least 6 characters" >&2
  exit 1
fi

keytool -genkeypair -keystore "$OUT" -alias "$ALIAS" -keyalg RSA -keysize 4096 -validity 36500 \
  -storepass "$KEYSTORE_PASSWORD" -keypass "$KEYSTORE_PASSWORD" \
  -dname "CN=S0undPlus, O=S0undPlus" >/dev/null

B64=$(base64 < "$OUT" | tr -d '\n')

cat <<MSG

Created $OUT

Add these as repository secrets (Settings > Secrets and variables > Actions > New repository secret):

  SIGNING_KEYSTORE_BASE64   $(printf '%s' "$B64" | cut -c1-24)...  (the full base64 of $OUT, printed below)
  SIGNING_STORE_PASSWORD    (the password you chose)
  SIGNING_KEY_ALIAS         $ALIAS
  SIGNING_KEY_PASSWORD      (the same password)
  TWITCH_CLIENT_ID          (your Twitch application's client ID)

SIGNING_KEYSTORE_BASE64 value:

$B64

Then publish a release with:  git tag v1.1.0 && git push origin v1.1.0
MSG
