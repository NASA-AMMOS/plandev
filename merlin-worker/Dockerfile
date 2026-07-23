FROM alpine:3.15 AS extractor

COPY merlin-worker/build/distributions/*.tar /usr/src/app/server.tar
RUN mkdir /usr/src/app/extracted
RUN cd /usr/src/app && tar --strip-components 1 -xf server.tar -C extracted

FROM ghcr.io/graalvm/jdk-community:21

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
COPY docker/graalpy /tmp/graalpy
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
