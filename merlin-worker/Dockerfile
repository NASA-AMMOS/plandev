FROM alpine:3.15 AS extractor

COPY plandev/merlin-worker/build/distributions/*.tar /usr/src/app/server.tar
RUN mkdir /usr/src/app/extracted
RUN cd /usr/src/app && tar --strip-components 1 -xf server.tar -C extracted

FROM ghcr.io/graalvm/jdk-community:21

# Oracle Linux 9's `python3` package aliases to 3.9 by default (unlike
# eclipse-temurin:21-jre-jammy, which was Ubuntu Jammy and defaulted to 3.10).
# pymerlin requires >=3.10, so pull the python3.11 AppStream package explicitly
# and symlink it over python3/pip3 so `python3 -m pymerlin._server` still
# resolves the way the shim expects.
RUN microdnf install -y findutils python3.11 python3.11-pip \
    && microdnf clean all \
    && ln -sf /usr/bin/python3.11 /usr/bin/python3 \
    && ln -sf /usr/bin/pip3.11 /usr/bin/pip3

COPY pymerlin /usr/src/pymerlin

# --- CPython subprocess path (the CURRENT shim transport) ------------------------------
#
# Roadmap §4.2 says to *replace* this block with the GraalPy venv below. Doing that
# now would be premature, so it stays for the moment -- deliberately, not by
# oversight:
#
#   * Phase 2 (§5) selects the transport at runtime via `pymerlin.bridge=graal|subprocess`
#     and its exit criterion is that demo/model.py "produces byte-identical simulation
#     results under both bridges". SubprocessBridge wraps PythonProcess, which execs
#     `python3 -m pymerlin._server`. Delete python3 here and the JSON path cannot run
#     in the worker, so the regression oracle -- the entire justification for Phase 2's
#     shape -- does not exist, and neither does the rollback switch.
#   * The subprocess transport is retired in Phase 3, not Phase 1: §6.3 lists
#     PythonProcess.java and Protocol.java on the deletion list. This block should be
#     removed in the same change, together with PythonProcess.java's hardcoded
#     site-packages scan (§8).
#
# So: Phase 1 is ADDITIVE. Both runtimes are present, GraalPy is not yet on any hot
# path (the shim only gains compileOnly deps in §4.3), and Phase 1 stays revertible by
# rebuilding the image.
RUN pip3 install --no-cache-dir /usr/src/pymerlin

# --- GraalPy path (roadmap §4.2) ------------------------------------------------------
#
# Builds the external-directory layout GraalPyResources expects:
#   /opt/pymerlin/python-resources/{venv,src}
#
# TARGETARCH is declared so BuildKit populates it; install.sh maps it onto GraalPy's
# arch naming (§11.7 -- the original snippet hardcoded linux-amd64 and broke on arm64
# build hosts). GRAALPY_VERSION must match graalPyVersion in gradle.properties: this
# builds the venv, that puts the matching language jars on the classpath, and a
# mismatch surfaces as a confusing runtime failure rather than a build error.
ARG GRAALPY_VERSION=25.0.2
ARG TARGETARCH
COPY plandev/docker/graalpy /tmp/graalpy
RUN GRAALPY_VERSION="${GRAALPY_VERSION}" TARGETARCH="${TARGETARCH}" bash /tmp/graalpy/install.sh \
    && rm -rf /tmp/graalpy

# Consumed by GraalPyResources.contextBuilder(...) in Phase 2's PyContext (§5.2) and by
# GraalPyPreflight. Set here so the path is image configuration rather than a constant
# recompiled into the shim.
ENV PYMERLIN_RESOURCES=/opt/pymerlin/python-resources

# The app tar changes on every rebuild (new shim/model-loader code); the GraalPy/venv
# layers above do not (same GRAALPY_VERSION, same install.sh) unless install.sh itself
# or GRAALPY_VERSION changes. Keeping this COPY last means an app-only rebuild only
# re-runs this cheap layer instead of invalidating (and re-running, ~340s+ spiceypy
# compile included) everything above it.
COPY --from=extractor /usr/src/app/extracted /usr/src/app

WORKDIR /usr/src/app
ENTRYPOINT ["/usr/src/app/bin/merlin-worker"]
