#!/bin/bash
# Launch the cpvpbot Minecraft 26.2 Fabric client directly (no Prism GUI),
# exactly like Prism does internally: java + full library classpath +
# fabric-loader's KnotClient entrypoint + vanilla game args.
set -u

WS=/run/media/configg_/HDD/0_Projects/CPVPBOT2.0
# load secrets (BOT_PASSWORD etc.) from gitignored .env if present
if [ -f "$WS/.env" ]; then set -a; . "$WS/.env"; set +a; fi
JAVA=/home/configg_/.local/share/PrismLauncher/java/java-runtime-epsilon/bin/java
GAMEDIR=/run/media/configg_/HDD/MinecraftInstances/instances/cpvpbot/.minecraft
ASSETS=/home/configg_/.local/share/PrismLauncher/assets
CP=$(cat /tmp/cpvpbot-classpath.txt)

# offline-mode UUID for the name cpvpbot
UUID=$(python3 -c "
import uuid
print(str(uuid.uuid3(uuid.NAMESPACE_DNS, 'OfflinePlayer:cpvpbot')))")

export DISPLAY=:0
export WAYLAND_DISPLAY=wayland-0
export XDG_RUNTIME_DIR=/run/user/1000
export XAUTHORITY=/run/user/1000/.mutter-Xwaylandauth.000NX3

cd "$GAMEDIR"

exec "$JAVA" \
  -Xms1024m -Xmx4096m \
  -Duser.language=en \
  -Djava.library.path="$GAMEDIR/natives" \
  -cp "$CP" \
  net.fabricmc.loader.impl.launch.knot.KnotClient \
  --username cpvpbot \
  --version 26.2 \
  --gameDir "$GAMEDIR" \
  --assetsDir "$ASSETS" \
  --assetIndex 32 \
  --uuid "$UUID" \
  --accessToken 0 \
  --userType legacy \
  --versionType release \
  --width 1280 --height 720 \
  "$@"
