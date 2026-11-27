#!/usr/bin/env bash
# Installs the pinned Maestro CLI (phone UI test flows + its MCP server for Claude).
# Fixed version + checksum on purpose: no curl|bash installer, no auto-updates.
set -euo pipefail
MAESTRO_VERSION="2.12.1"
MAESTRO_SHA256="54dbb0716425626604973cbb374c585ca30272375d9ce15177a1152765ddd9d7"
DEST="${MAESTRO_HOME:-$HOME/.maestro}"
export MAESTRO_CLI_NO_ANALYTICS=1 MAESTRO_DISABLE_UPDATE_CHECK=1

if [ -x "$DEST/bin/maestro" ] && "$DEST/bin/maestro" --version 2>/dev/null | grep -q "$MAESTRO_VERSION"; then
  echo "Maestro $MAESTRO_VERSION already installed in $DEST"; exit 0
fi
tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT
curl -fsSL -o "$tmp/maestro.zip" \
  "https://github.com/mobile-dev-inc/maestro/releases/download/cli-${MAESTRO_VERSION}/maestro.zip"
echo "${MAESTRO_SHA256}  $tmp/maestro.zip" | sha256sum -c -
rm -rf "$DEST"; mkdir -p "$(dirname "$DEST")"
unzip -q "$tmp/maestro.zip" -d "$tmp/x"
mv "$tmp/x/maestro" "$DEST"
echo "Installed Maestro $MAESTRO_VERSION to $DEST/bin (add it to PATH)"
