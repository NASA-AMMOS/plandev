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
RUN pip3 install --no-cache-dir /usr/src/pymerlin
ENV CONSTRAINTS_DSL_COMPILER_ROOT="/usr/src/app/constraints-dsl-compiler/"
ENV CONSTRAINTS_DSL_COMPILER_COMMAND="./build/main.js"
ENV NODE_PATH="${NVM_DIR}/versions/node/v${NODE_VERSION}/bin/node"

WORKDIR /usr/src/app
ENTRYPOINT ["/usr/src/app/bin/merlin-server"]
