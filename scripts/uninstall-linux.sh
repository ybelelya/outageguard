#!/usr/bin/env bash
# Stops OutageGuard, removes the systemd user service and deletes ~/.outageguard.
# Usage: bash scripts/uninstall-linux.sh [--keep-config]
#   --keep-config  keep config.properties (your group and settings) for a later reinstall
set -euo pipefail
dir="$HOME/.outageguard"
unit="$HOME/.config/systemd/user/outageguard.service"

systemctl --user disable --now outageguard 2>/dev/null || true
if [[ -f "$unit" ]]; then
    rm "$unit"
    systemctl --user daemon-reload
    echo "Removed systemd user service."
fi
pkill -f 'outageguard\.jar' 2>/dev/null || true

if [[ -d "$dir" ]]; then
    if [[ "${1:-}" == "--keep-config" ]]; then
        find "$dir" -mindepth 1 -maxdepth 1 ! -name config.properties -exec rm -rf {} +
        echo "Deleted $dir (kept config.properties)."
    else
        rm -rf "$dir"
        echo "Deleted $dir."
    fi
fi
echo "OutageGuard uninstalled."
