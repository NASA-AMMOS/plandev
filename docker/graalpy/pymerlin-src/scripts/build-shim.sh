#!/usr/bin/env bash
#
# Rebuild the pymerlin-shim JAR and copy it to pymerlin/_internal/jars/.
#
# Run this after changing anything under java/pymerlin-shim/src, then commit the
# rebuilt JAR. Python-only changes don't affect the shim.
#
# The JAR is committed to git so `pip install pymerlin` needs no JDK or Gradle --
# `pymerlin package` just copies the committed JAR into the model JAR it produces.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

(cd "${REPO_ROOT}/java" && ./gradlew assemble)
cp "${REPO_ROOT}/java/pymerlin-shim/build/libs/pymerlin-shim.jar" \
   "${REPO_ROOT}/pymerlin/_internal/jars/"

echo "shim JAR rebuilt -- commit it if it changed"
