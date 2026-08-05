#!/usr/bin/env bash
#
# DEV ONLY. Apply local pymerlin changes to this plandev checkout: rebuild the shim JAR,
# stage the pymerlin source into this build context, then rebuild and restart the images
# that carry a Python runtime -- so a dev image runs local pymerlin instead of the pinned
# git ref.
#
# Why staging is needed: merlin-worker/Dockerfile and merlin-server/Dockerfile build with
# `context: .` (plandev/), and Docker cannot COPY from outside the build context. pymerlin
# is checked out as a SIBLING of plandev, so it is unreachable from those builds. This
# copies it to docker/graalpy/pymerlin-src -- inside the context -- where install.dev.sh
# finds it.
#
# The copy is a snapshot, not a live mount: image builds are not live. Re-run this after
# every pymerlin edit you want the next build to pick up.
#
# What this does NOT do: update the mission model JAR. The shim classes that execute during
# a simulation come from the UPLOADED model JAR, not from these images, so a shim change
# also needs a repackage and a re-upload -- see the reminder printed at the end.
#
# Usage:
#   docker/graalpy/apply-pymerlin-changes.sh                        # defaults to ../pymerlin
#   PYMERLIN_SRC=/path/to/pymerlin docker/graalpy/apply-pymerlin-changes.sh
#

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PLANDEV_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"

PYMERLIN_SRC="${PYMERLIN_SRC:-$(cd "${PLANDEV_ROOT}/.." && pwd)/pymerlin}"
DEST="${SCRIPT_DIR}/pymerlin-src"

log() { echo "[apply-pymerlin-changes] $*"; }

if [ ! -f "${PYMERLIN_SRC}/pyproject.toml" ]; then
  log "ERROR: no pymerlin checkout at ${PYMERLIN_SRC} (looked for pyproject.toml)"
  log "       Set PYMERLIN_SRC=/path/to/pymerlin and re-run."
  exit 1
fi

log "source: ${PYMERLIN_SRC}"

# Rebuild the shim JAR before staging. `gradlew assemble` on its own is NOT enough here:
# it writes java/pymerlin-shim/build/libs/, which the rsync below excludes. build-shim.sh
# also copies the JAR to pymerlin/_internal/jars/ -- the copy pip installs and the one
# staged into the image. Skipping this stages a stale JAR with no visible error.
#
# Unconditional because it is cheap: with the Java unchanged, gradle skips the compile and
# only rewrites the JAR.
SHIM_BUILD="${PYMERLIN_SRC}/scripts/build-shim.sh"
if [ ! -x "${SHIM_BUILD}" ]; then
  log "ERROR: no shim build script at ${SHIM_BUILD}"
  exit 1
fi
log "rebuilding shim JAR"
"${SHIM_BUILD}"

STAGE_INFO="staged $(date -u +%Y-%m-%dT%H:%M:%SZ) from ${PYMERLIN_SRC}"
if [ -d "${PYMERLIN_SRC}/.git" ]; then
  # Record exactly what was staged. A dev image built from uncommitted work is easy to
  # lose track of later, so make the provenance explicit rather than a mystery.
  BRANCH="$(git -C "${PYMERLIN_SRC}" rev-parse --abbrev-ref HEAD 2>/dev/null || echo '?')"
  SHA="$(git -C "${PYMERLIN_SRC}" rev-parse --short HEAD 2>/dev/null || echo '?')"
  DIRTY=""
  if ! git -C "${PYMERLIN_SRC}" diff --quiet HEAD 2>/dev/null; then DIRTY=" +uncommitted"; fi
  STAGE_INFO="${STAGE_INFO} (${BRANCH}@${SHA}${DIRTY})"
  log "staging ${BRANCH}@${SHA}${DIRTY}"
fi

rm -rf "${DEST}"
mkdir -p "${DEST}"

# Exclude VCS/build/test detritus. Two of these matter more than they look:
#
#   venv/    the image builds its OWN GraalPy venv, and a host CPython venv is not
#            ABI-compatible with GraalPy -- copying one in would be actively harmful.
#   kernels/ ~1.5G of untracked local SPICE kernel data. It is not in pyproject.toml's
#            sdist `include` (only `pymerlin` is), so pip never needs it, but rsync would
#            happily copy it into the build context and make every `docker build` send
#            1.5G to the daemon. Excluded on size alone.
rsync -a \
  --exclude '.git' \
  --exclude 'venv' \
  --exclude '.venv' \
  --exclude 'kernels' \
  --exclude '__pycache__' \
  --exclude '*.pyc' \
  --exclude '.pytest_cache' \
  --exclude 'dist' \
  --exclude 'build' \
  --exclude '*.egg-info' \
  --exclude '.gradle' \
  --exclude 'java/build' \
  --exclude 'java/*/build' \
  "${PYMERLIN_SRC}/" "${DEST}/"

echo "${STAGE_INFO}" > "${DEST}/.pymerlin-stage-info"

log "staged -> ${DEST}"

# The -f paths are relative to the plandev root, so run from there rather than from
# whatever directory the caller happened to be in.
cd "${PLANDEV_ROOT}"

# Naming the three services rebuilds and recreates only those, leaving the rest of a
# running stack alone. From a cold start it also brings up only postgres (their sole
# depends_on) -- no hasura, gateway, or UI. Run a plain `up -d` after this for those.
log "rebuilding and restarting merlin images"
docker compose -f docker-compose.yml -f docker-compose.dev.yml up aerie_merlin aerie_merlin_worker_1 aerie_merlin_worker_2 --build -d

log "done"
log "if the shim JAR changed, the uploaded mission model is now stale -- repackage and re-upload:"
log "  (cd ${PYMERLIN_SRC} && venv/bin/pymerlin package --model demo/model.py:Mission --out ../model.jar)"