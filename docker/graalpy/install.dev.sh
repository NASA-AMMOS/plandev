#!/usr/bin/env bash
#
# DEV ONLY. Provision GraalPy + the pymerlin venv from a LOCAL pymerlin checkout instead
# of the pinned git ref that install.sh uses.
#
# Why this file exists separately from install.sh: install.sh installs pymerlin from a
# pinned git TAG on purpose -- that is what makes an image rebuild reproducible, for CI
# and for everyone else. Testing in-progress pymerlin work (possibly uncommitted, as with
# feature/cell-evolution) needs the opposite property. Rather than teach the production
# script a mode that could silently prefer whatever is on a developer's disk, this wraps
# it: the real install.sh still does all the work, and this only swaps out the pymerlin
# install afterward. Nothing a normal `docker compose build` reads is modified.
#
# Env:
#   PYMERLIN_LOCAL_SRC (optional)  path (inside the build context) to the staged pymerlin
#                                  source; default /tmp/graalpy/pymerlin-src, where
#                                  stage-local-pymerlin.sh puts it
#   ... plus everything install.sh takes (GRAALPY_VERSION, TARGETARCH, RESOURCES_ROOT).
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RESOURCES_ROOT="${RESOURCES_ROOT:-/opt/pymerlin/python-resources}"
PYMERLIN_LOCAL_SRC="${PYMERLIN_LOCAL_SRC:-${SCRIPT_DIR}/pymerlin-src}"

log() { echo "[graalpy-install-dev] $*"; }

if [ ! -f "${PYMERLIN_LOCAL_SRC}/pyproject.toml" ]; then
  log "ERROR: no pymerlin source at ${PYMERLIN_LOCAL_SRC} (looked for pyproject.toml)"
  log "       Run docker/graalpy/stage-local-pymerlin.sh on the host before building."
  exit 1
fi

# Run the real thing first. It builds the venv, installs GraalPy, numpy, spiceypy, and a
# pymerlin from the pinned ref -- which the next step then replaces. The redundant install
# costs little (it is a small pure-Python package, and the expensive part of this script is
# GraalPy + CSPICE) and keeps this wrapper from having to duplicate any of install.sh's
# hard-won architecture/wheel/constraints logic.
log "delegating to install.sh (pinned-ref pymerlin will be replaced below)"
bash "${SCRIPT_DIR}/install.sh"

VENV_PIP="${RESOURCES_ROOT}/venv/bin/pip"
export PIP_CONSTRAINT="${SCRIPT_DIR}/constraints.txt"

log "DEV: reinstalling pymerlin from local source ${PYMERLIN_LOCAL_SRC}"
if [ -f "${PYMERLIN_LOCAL_SRC}/.pymerlin-stage-info" ]; then
  log "staged provenance: $(cat "${PYMERLIN_LOCAL_SRC}/.pymerlin-stage-info")"
fi

# --force-reinstall --no-deps: replace ONLY pymerlin. Without --no-deps this can drag
# numpy/spiceypy back off pypi.org, and CPython wheels from there are not ABI-compatible
# with GraalPy -- which would undo the careful work install.sh just did.
"${VENV_PIP}" install --no-cache-dir --force-reinstall --no-deps "${PYMERLIN_LOCAL_SRC}"

# --- Verify ------------------------------------------------------------------------------
#
# A dev build whose local source silently failed to take effect is hard to distinguish
# from a working one at runtime: it simply simulates with no cell evolution and produces
# wrong numbers. Fail the BUILD instead.
log "verifying local pymerlin took effect"
"${RESOURCES_ROOT}/venv/bin/python" -c "
import pymerlin
print('[graalpy-install-dev] pymerlin', pymerlin.__file__)

from pymerlin._internal import _server
missing = [n for n in ('_parse_value',) if not hasattr(_server, n)]
if not hasattr(_server._ModelState, 'get_evolution_functions'):
    missing.append('_ModelState.get_evolution_functions')
if missing:
    raise SystemExit(
        '[graalpy-install-dev] ERROR: staged pymerlin lacks cell-evolution entry points: '
        + ', '.join(missing)
        + '\n  The local source did not take effect, or predates the cell-evolution work.'
        + '\n  Re-run docker/graalpy/stage-local-pymerlin.sh and rebuild.')
print('[graalpy-install-dev] cell-evolution entry points present OK')
"

log "done (DEV build: pymerlin from local source)"
