FROM alpine:3.15 AS extractor

COPY plandev/merlin-server/build/distributions/*.tar /usr/src/app/server.tar
RUN mkdir /usr/src/app/extracted
RUN cd /usr/src/app && tar --strip-components 1 -xf server.tar -C extracted

FROM ghcr.io/graalvm/jdk-community:21

ENV NODE_VERSION=20.12.1
ENV NVM_DIR=/usr/src/.nvm
ENV PATH="${NVM_DIR}/versions/node/v${NODE_VERSION}/bin:${PATH}"
# See merlin-worker/Dockerfile for why python3.11 is pulled explicitly and
# symlinked over python3/pip3 (Oracle Linux 9's default python3 is 3.9;
# pymerlin requires >=3.10).
RUN microdnf install -y curl gzip findutils python3.11 python3.11-pip \
    && microdnf clean all \
    && ln -sf /usr/bin/python3.11 /usr/bin/python3 \
    && ln -sf /usr/bin/pip3.11 /usr/bin/pip3 \
    && mkdir -p $NVM_DIR \
    && curl -o- https://raw.githubusercontent.com/nvm-sh/nvm/v0.39.0/install.sh | bash \
    &&. "$NVM_DIR/nvm.sh" \
    && nvm install -b ${NODE_VERSION} \
    && nvm use v${NODE_VERSION} \
    && nvm alias default v${NODE_VERSION} \
    && node --version \
    && npm --version

COPY --from=extractor /usr/src/app/extracted /usr/src/app

COPY plandev/merlin-server/constraints-dsl-compiler /usr/src/app/constraints-dsl-compiler

COPY pymerlin /usr/src/pymerlin

# CPython subprocess path. See merlin-worker/Dockerfile for why this stays through
# Phase 2 and is removed in Phase 3 (§6.3) rather than here.
RUN pip3 install --no-cache-dir /usr/src/pymerlin

# --- GraalPy path (roadmap §4.2, §11.1) -----------------------------------------------
#
# merlin-server needs this for the same reason merlin-worker does, and it is not
# speculative: Gate A traced POST /refreshActivityTypes ->
# LocalMissionModelService.refreshActivityTypes() -> MissionModelLoader.loadModelType(),
# so the server loads pymerlin model types in-process at model upload through the same
# loader the worker uses. That this Dockerfile already carried its own independent
# `pip3 install pymerlin` is corroboration, not coincidence.
#
# This is the second copy of the same Python dependency. §11.1's recommendation --
# move activity-type registration wholly worker-side so only one image carries a
# Python runtime -- is worth settling before this divergence gets more expensive;
# the shared install.sh keeps the two in sync until then.
ARG GRAALPY_VERSION=25.0.2
ARG TARGETARCH
COPY plandev/docker/graalpy /tmp/graalpy
RUN GRAALPY_VERSION="${GRAALPY_VERSION}" TARGETARCH="${TARGETARCH}" bash /tmp/graalpy/install.sh \
    && rm -rf /tmp/graalpy

ENV PYMERLIN_RESOURCES=/opt/pymerlin/python-resources
ENV CONSTRAINTS_DSL_COMPILER_ROOT="/usr/src/app/constraints-dsl-compiler/"
ENV CONSTRAINTS_DSL_COMPILER_COMMAND="./build/main.js"
ENV NODE_PATH="${NVM_DIR}/versions/node/v${NODE_VERSION}/bin/node"

WORKDIR /usr/src/app
ENTRYPOINT ["/usr/src/app/bin/merlin-server"]
