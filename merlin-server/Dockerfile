FROM alpine:3.15 AS extractor

COPY build/distributions/*.tar /usr/src/app/server.tar
RUN mkdir -p /usr/src/app/extracted \
    && tar --strip-components 1 -xf /usr/src/app/server.tar -C /usr/src/app/extracted

FROM node:24.18.0-bullseye-slim AS dsl-node-builder

WORKDIR /opt/constraints-dsl-compiler

# copy constraints-dsl-compiler node app package metadata first, so dependency installation can be cached.
COPY constraints-dsl-compiler/package.json constraints-dsl-compiler/package-lock.json ./
# Install dependencies without lifecycle scripts, then only build deps approved by the package's allowScripts policy.
RUN npm ci --ignore-scripts \
    && npm rebuild --strict-allow-scripts
# copy DSL Compiler *code* and build inside the container - node_modules and build files are .dockerignore'd
COPY constraints-dsl-compiler/ ./
RUN npm run build && npm prune --omit=dev

FROM eclipse-temurin:21-jre-jammy

COPY --from=extractor /usr/src/app/extracted /usr/src/app
COPY --from=dsl-node-builder /usr/local/bin/node /usr/local/bin/node
COPY --from=dsl-node-builder /opt/constraints-dsl-compiler /usr/src/app/constraints-dsl-compiler

RUN ldd /usr/local/bin/node \
    && node --version \
    && test -f /usr/src/app/constraints-dsl-compiler/build/main.js

ENV CONSTRAINTS_DSL_COMPILER_ROOT="/usr/src/app/constraints-dsl-compiler/"
ENV CONSTRAINTS_DSL_COMPILER_COMMAND="./build/main.js"
ENV NODE_PATH="/usr/local/bin/node"

WORKDIR /usr/src/app
ENTRYPOINT ["/usr/src/app/bin/merlin-server"]
