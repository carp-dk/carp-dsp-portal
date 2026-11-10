#!/bin/sh
# Copies the environments built into the image onto the state volume, then
# starts the portal.
#
# Runs look for environments under $HOME/.carp-dsp/envs, on the volume. A volume
# keeps what was first put in it, so environments added by a newer image would
# never reach it on their own. Each is copied once, when missing, to the path it
# was built at, so its absolute paths still hold.
set -e

SEED=/opt/carp-dsp/envs
TARGET="$HOME/.carp-dsp/envs"

if [ -d "$SEED" ]; then
  for dir in "$SEED"/*/*/; do
    [ -f "$dir/carp-env.json" ] || continue
    rel=${dir#"$SEED"/}
    rel=${rel%/}
    if [ ! -f "$TARGET/$rel/carp-env.json" ]; then
      echo "Copying prebuilt environment $rel"
      mkdir -p "$(dirname "$TARGET/$rel")"
      rm -rf "$TARGET/$rel.partial"
      cp -a "$dir" "$TARGET/$rel.partial"
      rm -rf "$TARGET/$rel"
      mv "$TARGET/$rel.partial" "$TARGET/$rel"
    fi
  done
fi

exec /app/bin/server "$@"
