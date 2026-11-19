#!/usr/bin/env bash
# Builds ShareFolder.exe for Windows (64-bit) and a Linux copy for testing. Needs Go 1.22+.
# The icon, version and Windows manifest come from winres/ as rsrc_windows_*.syso; after changing
# winres/winres.json or winres/icon.png, run: go-winres make --arch amd64,386,arm64
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p dist
GOOS=windows GOARCH=amd64 go build -trimpath -ldflags "-s -w -H=windowsgui" -o dist/ShareFolder.exe .
GOOS=linux GOARCH=amd64 go build -trimpath -ldflags "-s -w" -o dist/sharefolder-linux .
ls -l dist
