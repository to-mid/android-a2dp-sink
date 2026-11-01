#!/bin/sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)
mkdir -p "$ROOT/.local"
ASKPASS=${A2DP_SSH_ASKPASS:-"$ROOT/.local/ssh-askpass.sh"}
if [ -f "$ASKPASS" ]; then
    export SSH_ASKPASS="$ASKPASS"
    export SSH_ASKPASS_REQUIRE=force
fi
exec ssh -p "${A2DP_BUILD_PORT:-7778}" \
    -o ConnectTimeout=10 -o ServerAliveInterval=30 -o ServerAliveCountMax=4 \
    -o ControlMaster=auto -o ControlPersist=120 -o ControlPath="$ROOT/.local/c-%h-%p-%r" \
    -o StrictHostKeyChecking=accept-new \
    -o UserKnownHostsFile="$ROOT/.local/build-known-hosts" \
    "${A2DP_BUILD_USER:-tomid}@${A2DP_BUILD_HOST:-127.0.0.1}" "$@"
