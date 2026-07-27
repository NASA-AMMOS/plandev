#!/usr/bin/env bash
#
# Builds a prebuilt GraalPy-native spiceypy wheel for the CURRENT machine's architecture
# and writes it to wheels/spiceypy/. Run this once per supported architecture (amd64,
# aarch64), each on real hardware of that architecture -- QEMU-emulated CSPICE compiles
# are painfully slow, which is the whole reason this exists.
#
# Meant to be run INSIDE a container based on the same image install.sh's real callers
# (merlin-worker/Dockerfile, merlin-server/Dockerfile) use, so the wheel is guaranteed
# glibc/distro-compatible with what actually ships -- not just "some Linux on this arch".
# Invoke it like this from the repo root (or anywhere -- paths below are absolute inside
# the container):
#
#   docker run --rm \
#     -v "$(pwd)/docker/graalpy:/graalpy" \
#     -e GRAALPY_VERSION=25.0.2 \
#     ghcr.io/graalvm/jdk-community:21 \
#     bash /graalpy/build-spiceypy-wheel.sh
#
# The spiceypy version to build is read from constraints.txt's own `spiceypy==X.Y.Z`
# pin, not a separate argument -- so this script can never silently drift from the
# version the pin (and therefore the rest of the build) actually expects. Bump the pin,
# re-run this on each arch, done.
set -euo pipefail

GRAALPY_VERSION="${GRAALPY_VERSION:?GRAALPY_VERSION must be set}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CONSTRAINTS_FILE="${SCRIPT_DIR}/constraints.txt"
OUT_DIR="${SCRIPT_DIR}/wheels/spiceypy"

SPICEYPY_VERSION="$(grep -E '^spiceypy==' "${CONSTRAINTS_FILE}" | cut -d= -f3)"
if [ -z "${SPICEYPY_VERSION}" ]; then
  echo "ERROR: no 'spiceypy==X.Y.Z' pin found in ${CONSTRAINTS_FILE}" >&2
  exit 1
fi

log() { echo "[build-spiceypy-wheel] $*"; }

# --- Architecture -- same fallback logic install.sh uses, since a plain `docker run`
# (not a BuildKit build) never populates TARGETARCH.
case "$(uname -m)" in
  x86_64)  GRAALPY_ARCH=amd64 ;;
  aarch64) GRAALPY_ARCH=aarch64 ;;
  *) log "ERROR: unsupported architecture $(uname -m)"; exit 1 ;;
esac

log "building spiceypy==${SPICEYPY_VERSION} for GraalPy ${GRAALPY_VERSION} / ${GRAALPY_ARCH}"

# --- Same build-time OS deps install.sh installs, for the same reasons (see install.sh's
# own comments for the full rationale per package) -- this has to be byte-for-byte the
# same toolchain, or a wheel built here could differ subtly from what install.sh's own
# from-source path would have produced.
microdnf install -y \
  ca-certificates curl tar gzip findutils git \
  gcc gcc-c++ make patch \
  openssl-devel bzip2-devel libffi-devel readline-devel sqlite-devel xz-devel zlib-devel
update-ca-trust extract

GRAALPY_HOME="/opt/graalpy-community-${GRAALPY_VERSION}-linux-${GRAALPY_ARCH}"
GRAALPY_URL="https://github.com/oracle/graalpython/releases/download/graal-${GRAALPY_VERSION}/graalpy-community-${GRAALPY_VERSION}-linux-${GRAALPY_ARCH}.tar.gz"
log "downloading ${GRAALPY_URL}"
curl -fsSL "${GRAALPY_URL}" | tar xz -C /opt
ln -sf "${GRAALPY_HOME}/bin/graalpy" /usr/local/bin/graalpy

BUILD_VENV="/tmp/spiceypy-wheel-venv"
graalpy -m venv "${BUILD_VENV}"

# PIP_CONSTRAINT matters here for exactly the reason constraints.txt's own comment
# explains for numpy: spiceypy declares numpy as a BUILD-time dependency, and pip's
# isolated build environment re-resolves it independently. Without this, building the
# spiceypy wheel could trigger its OWN from-source numpy compile as a side effect,
# instead of using GraalVM's prebuilt numpy==2.2.4 wheel.
export PIP_CONSTRAINT="${CONSTRAINTS_FILE}"
log "PIP_CONSTRAINT=${PIP_CONSTRAINT}"

mkdir -p "${OUT_DIR}"
log "building wheel (expect ~340s for the CSPICE source build)"
# --no-deps: we want exactly one wheel out of this -- spiceypy's own. Its build-time
# deps (numpy, cython) still get resolved/installed into pip's isolated build env as
# needed to actually compile it; --no-deps just stops pip from ALSO writing wheels for
# them into our output directory.
"${BUILD_VENV}/bin/pip" wheel "spiceypy==${SPICEYPY_VERSION}" --no-deps -w "${OUT_DIR}"

log "done. wheel(s) in ${OUT_DIR}:"
ls -la "${OUT_DIR}"
