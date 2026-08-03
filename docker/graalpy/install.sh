#!/usr/bin/env bash
#
# Provision GraalPy and the pymerlin `python-resources` venv into an image.
# Roadmap §4.2, with the fixes §3.1 and §11.7 require.
#
# Shared by merlin-worker/Dockerfile and merlin-server/Dockerfile. Both images
# need this for the same reason -- Gate A traced POST /refreshActivityTypes ->
# LocalMissionModelService.refreshActivityTypes() -> MissionModelLoader.loadModelType(),
# so merlin-server loads pymerlin model types in-process exactly as the worker
# does (roadmap §11.1). Keeping it in one script rather than duplicating ~40
# lines of Dockerfile is what stops the two images from drifting apart.
#
# Produces the external-directory layout GraalPyResources.contextBuilder(root)
# expects by convention (roadmap §2):
#
#   ${RESOURCES_ROOT}/venv            <- pymerlin + numpy + spiceypy
#   ${RESOURCES_ROOT}/src             <- stays empty; see the mkdir near the end of this file
#   ${RESOURCES_ROOT}/constraints.txt <- copy of this build's PIP_CONSTRAINT, kept for the
#                                        model-declared installs the shim runs at load time
#
# Env:
#   GRAALPY_VERSION  (required)  e.g. 25.0.2 -- keep in lockstep with
#                                graalPyVersion in gradle.properties
#   TARGETARCH       (optional)  BuildKit-provided; falls back to `uname -m`
#   RESOURCES_ROOT   (optional)  default /opt/pymerlin/python-resources
#   PYMERLIN_GIT_URL (optional)  default the pymerlin repo below; override to
#                                point at a fork or an internal mirror
#   PYMERLIN_REF     (optional)  default a pinned tag (see note at the pip
#                                install call below) -- a tag or commit SHA,
#                                NOT a branch name (branches move; the point of
#                                pinning is that a rebuild is reproducible)
set -euo pipefail

GRAALPY_VERSION="${GRAALPY_VERSION:?GRAALPY_VERSION must be set}"
RESOURCES_ROOT="${RESOURCES_ROOT:-/opt/pymerlin/python-resources}"
PYMERLIN_GIT_URL="${PYMERLIN_GIT_URL:-https://github.com/remy-rabideau/pymerlin.git}"
PYMERLIN_REF="${PYMERLIN_REF:-v0.2.0}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CONSTRAINTS_FILE="${SCRIPT_DIR}/constraints.txt"

log() { echo "[graalpy-install] $*"; }

# --- Architecture (roadmap §11.7) -------------------------------------------------------
#
# §4.2's original snippet hardcoded `linux-amd64`. The base JDK image and microdnf
# resolve multi-arch automatically, but a manual curl to a hardcoded asset name does
# not -- on an arm64 host that fails as `rosetta error: failed to open elf`, which
# does not obviously point at the download. Gate D-1 hit exactly this.
#
# TARGETARCH is auto-populated by BuildKit. It is EMPTY under the legacy builder
# (DOCKER_BUILDKIT=0), so fall back to uname rather than defaulting to amd64 --
# defaulting would silently reintroduce the very bug this maps around.
TARGETARCH="${TARGETARCH:-}"
if [ -z "${TARGETARCH}" ]; then
  case "$(uname -m)" in
    x86_64)  TARGETARCH=amd64 ;;
    aarch64) TARGETARCH=arm64 ;;
    *) log "ERROR: cannot infer architecture from uname -m=$(uname -m)"; exit 1 ;;
  esac
  log "TARGETARCH was empty (legacy builder?); inferred ${TARGETARCH} from uname -m"
fi

# Docker's arch names are not GraalPy's arch names.
case "${TARGETARCH}" in
  amd64) GRAALPY_ARCH=amd64 ;;
  arm64) GRAALPY_ARCH=aarch64 ;;
  *) log "ERROR: unsupported TARGETARCH=${TARGETARCH} (expected amd64 or arm64)"; exit 1 ;;
esac

log "GraalPy ${GRAALPY_VERSION}, TARGETARCH=${TARGETARCH} -> GraalPy arch ${GRAALPY_ARCH}"

# --- Build-time OS dependencies ---------------------------------------------------------
#
# The base image is Oracle Linux (ghcr.io/graalvm/jdk-community:21), not Debian/Ubuntu.
# Everything below was established empirically by Gates A and D:
#
#   ca-certificates  Oracle Linux's minimal trust store does not validate every
#                    legitimate cert chain we fetch over HTTPS at build time
#                    (§3.1). Needs `update-ca-trust extract` to take effect.
#   curl, tar, gzip  not all preinstalled; needed to fetch/unpack the GraalPy tarball.
#   findutils        Gradle's application-plugin start scripts (bin/merlin-worker,
#                    bin/merlin-server) shell out to xargs to split JAVA_OPTS (Gate A).
#   git              spiceypy's scikit-build-core/CMake build clones the CSPICE
#                    source; without it: "could not find git for clone of
#                    cspice-populate" (Gate D-1 finding 3).
#   gcc..zlib-devel  GraalPy-compatible wheels are not guaranteed to exist for every
#                    package, so `pip install` can silently mean "compile from
#                    source" -- CSPICE does exactly that, ~340s (Gate D-1 findings
#                    2 and 6). A minimal runtime image fails on this.
#
# NOTE (image size): this leaves a full C toolchain in the final image. That is a
# real cost and a known follow-up -- the fix is a builder stage that COPYs the
# finished venv forward. It is deliberately NOT done here: Gate D validated the
# single-stage shape, and Phase 1's exit criterion is "the worker can create a
# Context", not image size. Measure, then decide.
log "installing build dependencies"
microdnf install -y \
  ca-certificates curl tar gzip findutils git \
  gcc gcc-c++ make patch \
  openssl-devel bzip2-devel libffi-devel readline-devel sqlite-devel xz-devel zlib-devel
update-ca-trust extract

# --- GraalPy standalone -----------------------------------------------------------------
#
# This is the build-time CLI used to create the venv and run pip (Gate D-1's path).
# It is distinct from the polyglot jars on the worker classpath, which are what
# actually runs Python at simulation time (Gate D-2's path).
GRAALPY_HOME="/opt/graalpy-community-${GRAALPY_VERSION}-linux-${GRAALPY_ARCH}"
GRAALPY_URL="https://github.com/oracle/graalpython/releases/download/graal-${GRAALPY_VERSION}/graalpy-community-${GRAALPY_VERSION}-linux-${GRAALPY_ARCH}.tar.gz"

log "downloading ${GRAALPY_URL}"
curl -fsSL "${GRAALPY_URL}" | tar xz -C /opt
ln -sf "${GRAALPY_HOME}/bin/graalpy" /usr/local/bin/graalpy

log "graalpy reports: $(graalpy --version 2>&1 | head -1)"

# --- The venv ---------------------------------------------------------------------------
#
# Use the venv's own pip -- GraalPy's patched one, preconfigured with the extra
# graalvm.org wheel repository. NEVER a system pip: GraalPy's C API support extends
# to the API, not the ABI, so prebuilt CPython wheels from pypi.org are not binary
# compatible and must not be installed here.
log "creating venv at ${RESOURCES_ROOT}/venv"
graalpy -m venv "${RESOURCES_ROOT}/venv"

VENV_PIP="${RESOURCES_ROOT}/venv/bin/pip"

# Applied to isolated build environments too, not just this top-level install --
# that is the whole point (see constraints.txt).
export PIP_CONSTRAINT="${CONSTRAINTS_FILE}"
log "PIP_CONSTRAINT=${PIP_CONSTRAINT}"

# pymerlin is installed from a pinned git ref, NOT from a local checkout copied into
# the build context. Earlier this installed `${PYMERLIN_SRC}` (a path populated by
# `COPY pymerlin /usr/src/pymerlin` in the Dockerfile), which meant building this
# image required the pymerlin repo checked out as a sibling of plandev's -- exactly
# the coupling a standalone model-author workflow (`pip install pymerlin`, write a
# model, `pymerlin package`) should not force onto image builders too. `git` is
# already installed above for CSPICE's source clone, so `pip install git+...@ref`
# needs nothing extra here.
#
# PYMERLIN_REF's default is `v0.2.0`, the tag matching pymerlin's own `pyproject.toml`
# `version`. Bump this here every time a new pymerlin version should reach new
# worker-image builds. Treat "this tag" and "the GraalPy version above" as a matched
# pair: bumping one without checking the other risks a shim JAR built against a
# different GraalPy API than what's on this image's classpath (see
# pymerlin-shim/build.gradle's `graalPyVersion` comment, and roadmap.md §8.2's open
# item about this check not being fully automated end-to-end yet).
#
# numpy and spiceypy are named explicitly rather than via pymerlin's `plotting` /
# `spice` extras. `plotting` would also drag in bokeh, which nothing in Gate D
# exercised under GraalPy; this keeps the venv to the set that was actually proven
# (roadmap §4.2, §11.2's "start with a fixed package set").
#
# --- spiceypy: prefer a prebuilt wheel, fall back to source ------------------------------
#
# spiceypy has no prebuilt wheel in GraalVM's own wheel repository (unlike numpy above),
# so it normally means a ~340s from-source CSPICE compile on every image build.
# build-spiceypy-wheel.sh (this directory) can build one ahead of time, natively per-arch
# (QEMU-emulated CSPICE compiles are painfully slow, so this only makes sense built on
# real hardware), committed to wheels/spiceypy/.
#
# The version baked into the glob below comes from THIS SAME constraints.txt pin, the
# same file build-spiceypy-wheel.sh reads it from when building -- so this can never
# silently reuse a stale wheel after the pin bumps. If the pin changes and nobody
# rebuilds the wheels, the glob simply finds nothing (the old wheel's filename still has
# the old version baked in) and this correctly falls back to a fresh compile, not a
# wrong-version install. The wheel is purely a speed optimization; it must never be a
# new way for the build to break or to silently install the wrong version.
SPICEYPY_VERSION="$(grep -E '^spiceypy==' "${CONSTRAINTS_FILE}" | cut -d= -f3)"
case "${GRAALPY_ARCH}" in
  amd64)   SPICEYPY_WHEEL_PLATFORM="x86_64" ;;
  aarch64) SPICEYPY_WHEEL_PLATFORM="aarch64" ;;
esac

SPICEYPY_TARGET="spiceypy"
if [ -n "${SPICEYPY_VERSION}" ]; then
  SPICEYPY_WHEELS_DIR="${SCRIPT_DIR}/wheels/spiceypy"
  shopt -s nullglob
  # No literal separator hardcoded between `*` and the platform tag: pip's own wheel
  # naming puts it as e.g. `..._native-linux_aarch64.whl` (underscore before the arch,
  # not hyphen) -- verified directly against a real built wheel before trusting this.
  SPICEYPY_WHEEL_MATCHES=("${SPICEYPY_WHEELS_DIR}"/spiceypy-"${SPICEYPY_VERSION}"-*"${SPICEYPY_WHEEL_PLATFORM}".whl)
  shopt -u nullglob

  if [ "${#SPICEYPY_WHEEL_MATCHES[@]}" -eq 1 ]; then
    SPICEYPY_TARGET="${SPICEYPY_WHEEL_MATCHES[0]}"
    log "using prebuilt spiceypy wheel: ${SPICEYPY_TARGET}"
  elif [ "${#SPICEYPY_WHEEL_MATCHES[@]}" -gt 1 ]; then
    SPICEYPY_TARGET="$(ls -t "${SPICEYPY_WHEEL_MATCHES[@]}" | head -1)"
    log "WARNING: ${#SPICEYPY_WHEEL_MATCHES[@]} spiceypy==${SPICEYPY_VERSION} wheels matched ${SPICEYPY_WHEEL_PLATFORM}; using newest (${SPICEYPY_TARGET}) -- clean up ${SPICEYPY_WHEELS_DIR}"
  else
    log "no prebuilt spiceypy==${SPICEYPY_VERSION} wheel for ${SPICEYPY_WHEEL_PLATFORM} in ${SPICEYPY_WHEELS_DIR} -- falling back to source build"
  fi
fi

log "installing pymerlin@${PYMERLIN_REF} + numpy + spiceypy (source build, if triggered, is ~340s for CSPICE)"
"${VENV_PIP}" install --no-cache-dir \
  "git+${PYMERLIN_GIT_URL}@${PYMERLIN_REF}" \
  numpy \
  "${SPICEYPY_TARGET}"

# ${root}/src is on the Python path by GraalPyResources convention, and contextBuilder(root)
# needs it to exist -- so create it even though it stays empty. Model sources do NOT land
# here: the shim extracts the .py out of the uploaded JAR into its own /tmp/pymerlin-model-*
# directory and puts that on sys.path directly, then deletes it when the simulation ends.
# Moving the source in here instead would only be necessary if filesystem access were
# sandboxed, which would stop the shim reading an arbitrary temp path.
mkdir -p "${RESOURCES_ROOT}/src"

# Keep the constraints alongside the venv they constrain. A model JAR can declare its own
# Python packages, which the shim pip-installs into this venv at model-load time -- and
# that install needs the same PIP_CONSTRAINT this build used, or a model asking for numpy
# unpinned re-triggers the ~15-minute from-source compile the pin above exists to prevent.
# This file cannot be read from its build location at runtime: the image build COPYs this
# directory to /tmp/graalpy and deletes it afterwards.
cp "${CONSTRAINTS_FILE}" "${RESOURCES_ROOT}/constraints.txt"
log "constraints persisted to ${RESOURCES_ROOT}/constraints.txt (for model-declared installs)"

# --- Verify ------------------------------------------------------------------------------
#
# Fail the BUILD, not the first simulation. This is the venv half of what
# GraalPyPreflight re-checks from the JVM side at CI time.
log "verifying venv imports"
"${RESOURCES_ROOT}/venv/bin/python" -c "
import pymerlin, numpy, spiceypy
print('[graalpy-install] pymerlin  OK')
print('[graalpy-install] numpy    ', numpy.__version__)
print('[graalpy-install] spiceypy ', spiceypy.__version__)
"

microdnf clean all
rm -rf /var/cache/dnf /var/cache/yum

log "done: ${RESOURCES_ROOT}"
