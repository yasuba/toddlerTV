#!/bin/bash
set -e

DURATION="${1:-45m}"
MOOD="${2:-calm}"

echo "Building session: $DURATION, mood: $MOOD"
scala-cli run . -- build-session --duration "$DURATION" --mood "$MOOD" --output current-session/

echo "Pushing to Pi..."
rsync -av --delete --copy-links current-session/ pi@kidstv.local:~/kidstv-current/

echo "Done. Pi will play on next boot."

