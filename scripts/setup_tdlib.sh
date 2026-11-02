#!/usr/bin/env bash
# Copies a finished TDLib Android build into this project.
#
#   scripts/setup_tdlib.sh /path/to/tdlib
#
# "/path/to/tdlib" is the output folder of Telegram's own build (it contains java/ and libs/).
# See README_DIRECT.md for how to build it (Docker one-liner, or Telegram's build scripts).
set -euo pipefail

SRC="${1:-}"
if [ -z "$SRC" ]; then
  echo "usage: $0 /path/to/tdlib" >&2
  exit 1
fi
APP="$(cd "$(dirname "$0")/.." && pwd)"

JAVA_DIR="$SRC/java"
LIBS_DIR="$SRC/libs"

if [ ! -f "$JAVA_DIR/org/drinkless/tdlib/TdApi.java" ] || [ ! -f "$JAVA_DIR/org/drinkless/tdlib/Client.java" ]; then
  echo "Can't find $JAVA_DIR/org/drinkless/tdlib/{Client,TdApi}.java" >&2
  echo "The TDLib build must use the default Java interface (not JSON)." >&2
  exit 1
fi
if [ ! -d "$LIBS_DIR" ]; then
  echo "Can't find $LIBS_DIR (expected one folder per CPU type, each with libtdjni.so)" >&2
  exit 1
fi

mkdir -p "$APP/app/src/main/java" "$APP/app/src/main/jniLibs"
rm -rf "$APP/app/src/main/java/org/drinkless"
cp -r "$JAVA_DIR/org" "$APP/app/src/main/java/"

found=0
for dir in "$LIBS_DIR"/*/; do
  abi="$(basename "$dir")"
  if [ -f "$dir/libtdjni.so" ]; then
    mkdir -p "$APP/app/src/main/jniLibs/$abi"
    cp "$dir/libtdjni.so" "$APP/app/src/main/jniLibs/$abi/"
    found=$((found + 1))
    echo "  native library for $abi"
  fi
done
if [ "$found" -eq 0 ]; then
  echo "No libtdjni.so found under $LIBS_DIR/<cpu>/" >&2
  exit 1
fi

echo "TDLib installed: Java sources + $found native librar$( [ "$found" -eq 1 ] && echo y || echo ies )."
echo "Now open the project in Android Studio and build."
