FROM alpine:3.15 AS extractor

COPY build/distributions/*.tar /usr/src/app/server.tar
RUN mkdir -p /usr/src/app/extracted \
    && tar --strip-components 1 -xf /usr/src/app/server.tar -C /usr/src/app/extracted

FROM eclipse-temurin:21-jre-jammy

ENV NODE_VERSION=24.18.0
ENV NVM_DIR=/usr/src/.nvm
ENV PATH="${NVM_DIR}/versions/node/v${NODE_VERSION}/bin:${PATH}"
# nvm expects bash.
SHELL ["/bin/bash", "-o", "pipefail", "-c"]

RUN apt-get update \
    && apt-get install --no-install-recommends -y ca-certificates curl \
    && mkdir -p "${NVM_DIR}" \
    && curl -fsSL https://raw.githubusercontent.com/nvm-sh/nvm/v0.39.0/install.sh | bash \
    && source "${NVM_DIR}/nvm.sh" \
    && nvm install -b "${NODE_VERSION}" \
    && nvm alias default "${NODE_VERSION}" \
    && node --version \
    && npm --version \
    && rm -rf /var/lib/apt/lists/*

# copy java app
COPY --from=extractor /usr/src/app/extracted /usr/src/app

# copy constraints-dsl-compiler node app package metadata first, so dependency installation can be cached.
WORKDIR /usr/src/app/constraints-dsl-compiler
COPY constraints-dsl-compiler/package.json \
     constraints-dsl-compiler/package-lock.json \
     ./

# install dependencies inside the image so native addons are built for the container's OS & architecture
RUN npm ci
# copy DSL Compiler *code* and build inside the container - node_modules and build files are .dockerignore'd
COPY constraints-dsl-compiler/ ./
RUN npm run build

ENV CONSTRAINTS_DSL_COMPILER_ROOT="/usr/src/app/constraints-dsl-compiler/"
ENV CONSTRAINTS_DSL_COMPILER_COMMAND="./build/main.js"
ENV NODE_PATH="${NVM_DIR}/versions/node/v${NODE_VERSION}/bin/node"

WORKDIR /usr/src/app
ENTRYPOINT ["/usr/src/app/bin/merlin-server"]
