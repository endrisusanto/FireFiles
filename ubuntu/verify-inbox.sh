#!/usr/bin/env bash
set -euo pipefail

INBOX="${1:-/home/endri/firmware-inbox}"
find "$INBOX" -maxdepth 1 -type f -name '*.tar.md5' -printf '%f %s bytes\n' | sort
