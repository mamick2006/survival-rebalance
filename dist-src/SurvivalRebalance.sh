#!/bin/sh
# Survival Rebalance: opens the window on Linux and macOS.
# Run it from a terminal: sh SurvivalRebalance.sh
TOOL=$(cd "$(dirname "$0")" && pwd)
GAME=""
PARENT=$(dirname "$TOOL")
if [ -d "$PARENT/mods" ]; then GAME="$PARENT"
elif [ -f "$TOOL/game_dir.txt" ]; then GAME=$(head -n 1 "$TOOL/game_dir.txt" | tr -d '\r"')
fi
JAVA=java
for j in "$GAME/jre/bin/java" "$GAME/jre_linux/bin/java" "$GAME/Contents/Home/bin/java"; do
  if [ -n "$GAME" ] && [ -x "$j" ]; then JAVA="$j"; break; fi
done

jar() {
  if [ -f "$TOOL/app/SurvivalRebalance.jar" ]; then echo "$TOOL/app/SurvivalRebalance.jar"; else echo "$TOOL/SurvivalRebalance.jar"; fi
}

# A downloaded update is complete when update/update.json exists. Copy it in (never the
# player's files), then the new jar checks every file and removes what the new version no
# longer ships.
install() {
  UD="$TOOL/update"
  if [ ! -f "$UD/update.json" ]; then rm -rf "$UD/staged"; return 0; fi
  echo "Installing the downloaded update..."
  (cd "$UD/staged" && find . -type f) | while IFS= read -r f; do
    rel=${f#./}
    case "/$rel" in
      */customize.txt|*/game_dir.txt|*/disabled_changes.txt|*/logs/*) continue ;;
    esac
    mkdir -p "$TOOL/$(dirname "$rel")" && cp -f "$UD/staged/$rel" "$TOOL/$rel"
  done
  "$JAVA" -jar "$(jar)" update --finish
}

while :; do
  install
  if [ -n "$GAME" ]; then "$JAVA" -cp "$(jar)" survivalrebalance.gui.Window --game "$GAME" --launcher
  else "$JAVA" -cp "$(jar)" survivalrebalance.gui.Window --launcher
  fi
  RC=$?
  [ "$RC" -eq 42 ] || exit "$RC"
done
