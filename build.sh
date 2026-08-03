#!/usr/bin/env bash
# Build a single L2Topzone vote-reward JAR for a given L2j pack.
#
# Usage:  ./build.sh <pack>
#   where <pack> is one of: universal acis mobius unity frozen reunion l2jserver
#
# Output: dist/l2topzone-vote-reward-<pack>.jar
#
# Requires a JDK matching the pack target (see table below). The CI matrix
# selects the right JDK; locally you can override with JAVA_HOME.
set -euo pipefail

PACKS=(universal acis mobius unity frozen reunion l2jserver)

PACK="${1:-}"
if [[ -z "$PACK" ]]; then
  echo "Usage: $0 <$(IFS='|'; echo "${PACKS[*]}")|all>" >&2
  exit 2
fi

# Convenience: build everything in one go.
if [[ "$PACK" == "all" ]]; then
  for p in "${PACKS[@]}"; do "$0" "$p"; done
  exit 0
fi

case "$PACK" in
  # The universal build imports nothing from any pack, so it targets the oldest
  # JVM we support and runs on every one of them.
  universal)JAVA_TARGET=8  ;;
  acis)     JAVA_TARGET=17 ;;
  mobius)   JAVA_TARGET=17 ;;
  unity)    JAVA_TARGET=11 ;;
  frozen)   JAVA_TARGET=8  ;;
  reunion)  JAVA_TARGET=8  ;;
  l2jserver)JAVA_TARGET=8  ;;
  *) echo "Unknown pack: $PACK" >&2; exit 2 ;;
esac

ROOT="$(cd "$(dirname "$0")" && pwd)"
STUBS="$ROOT/build/stubs/$PACK"
PACK_SRC="$ROOT/packs/$PACK"
COMMON_SRC="$ROOT/common"
OUT="$ROOT/build/classes/$PACK"
DIST="$ROOT/dist"

if [[ ! -d "$PACK_SRC" ]]; then
  echo "Missing pack directory: $PACK_SRC" >&2; exit 1
fi

# The universal build has no compile-time dependency on any pack, so it has no stubs.
SRC_DIRS=("$COMMON_SRC" "$PACK_SRC")
if [[ -d "$STUBS" ]]; then
  SRC_DIRS+=("$STUBS")
elif [[ "$PACK" != "universal" ]]; then
  echo "Missing stubs directory: $STUBS" >&2; exit 1
fi

rm -rf "$OUT"
mkdir -p "$OUT" "$DIST"

# Collect sources
SOURCES=()
while IFS= read -r f; do SOURCES+=("$f"); done < <(find "${SRC_DIRS[@]}" -name '*.java' -type f)

echo "==> Compiling l2topzone for pack '$PACK' (target Java $JAVA_TARGET)"
echo "    Sources: ${#SOURCES[@]} files"

# release flag controls both source + target + bootclasspath
javac --release "$JAVA_TARGET" -d "$OUT" -encoding UTF-8 -Xlint:none "${SOURCES[@]}"

# Package ONLY our classes (l2topzone.**) — strip stubs so runtime uses real pack classes.
JAR_FILE="$DIST/l2topzone-vote-reward-$PACK.jar"
rm -f "$JAR_FILE"

# Copy template properties next to the jar for convenience
cp "$COMMON_SRC/L2TopzoneVoteReward.properties" "$DIST/L2TopzoneVoteReward.properties.template" 2>/dev/null || true

(cd "$OUT" && jar cf "$JAR_FILE" l2topzone)

echo "==> Built: $JAR_FILE"
ls -lh "$JAR_FILE"
echo "==> Contents:"
jar tf "$JAR_FILE" | sed 's/^/    /'
