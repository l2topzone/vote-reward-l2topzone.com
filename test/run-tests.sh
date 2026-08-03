#!/usr/bin/env bash
# Runs the vote-reward test suites. No test framework, no downloads — plain javac
# and a JDK-bundled HTTP server, so this works in any environment that can build
# the project.
#
#   ./test/run-tests.sh
#
# Suite 1 (core):      JSON reader, store concurrency + persistence, addItem
#                      overload resolution, IP normalisation, and the full reward
#                      flow against a mock API.
# Suite 2 (universal): boots the universal build against a fake fork whose package
#                      it has never seen, proving runtime detection works.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

echo "==> Compiling core suite"
mkdir -p "$WORK/core"
javac -d "$WORK/core" -encoding UTF-8 -nowarn \
  "$ROOT"/common/*.java \
  "$ROOT"/test/src/l2topzone/CoreSelfTest.java

echo "==> Running core suite"
java -cp "$WORK/core" l2topzone.CoreSelfTest
CORE=$?

echo
echo "==> Compiling universal integration suite"
mkdir -p "$WORK/uni" "$WORK/run"
# shellcheck disable=SC2046
javac -d "$WORK/uni" -encoding UTF-8 -nowarn \
  $(find "$ROOT/test/fake-pack" -name '*.java') \
  "$ROOT"/common/*.java \
  "$ROOT"/packs/universal/*.java \
  "$ROOT"/test/src/UniversalIntegrationTest.java

echo "==> Running universal integration suite"
# The universal build reads ./config/... relative to the working directory.
cd "$WORK/run"
java -cp "$WORK/uni" UniversalIntegrationTest
UNI=$?

echo
if [[ $CORE -eq 0 && $UNI -eq 0 ]]; then
  echo "ALL SUITES PASSED"
else
  echo "FAILURES PRESENT" >&2
  exit 1
fi
