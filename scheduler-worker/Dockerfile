FROM alpine:3.15 AS extractor

COPY build/distributions/*.tar /usr/src/app/server.tar
RUN mkdir -p /usr/src/app/extracted \
    && tar --strip-components 1 -xf /usr/src/app/server.tar -C /usr/src/app/extracted

FROM node:24.18.0-bookworm-slim AS dsl-node-builder

WORKDIR /opt/scheduling-dsl-compiler

# build-essential and python3 are needed to build isolated-vm dependency
RUN apt-get update \
    && apt-get install --no-install-recommends -y build-essential python3 \
    && rm -rf /var/lib/apt/lists/*

# copy scheduling-dsl-compiler node app package metadata first, so dependency installation can be cached.
COPY scheduling-dsl-compiler/package.json scheduling-dsl-compiler/package-lock.json ./
# Install dependencies without lifecycle scripts, then only build deps approved by the package's allowScripts policy.
RUN npm ci --ignore-scripts \
    && npm rebuild --strict-allow-scripts
# copy DSL Compiler *code* and build inside the container - node_modules and build files are .dockerignore'd
COPY scheduling-dsl-compiler/ ./
RUN npm run build && npm prune --omit=dev

FROM eclipse-temurin:21-jre-noble

COPY --from=extractor /usr/src/app/extracted /usr/src/app
COPY --from=dsl-node-builder /usr/local/bin/node /usr/local/bin/node
COPY --from=dsl-node-builder /opt/scheduling-dsl-compiler /usr/src/app/scheduling-dsl-compiler

RUN ldd /usr/local/bin/node \
    && node --version \
    && test -f /usr/src/app/scheduling-dsl-compiler/build/main.js

ENV SCHEDULING_DSL_COMPILER_ROOT="/usr/src/app/scheduling-dsl-compiler/"
ENV SCHEDULING_DSL_COMPILER_COMMAND="./build/main.js"
ENV NODE_PATH="/usr/local/bin/node"

# CVE-2026-114938 (Java4Shell): disable the HotSpot Base64 intrinsic until the base image ships a patched JDK.
# JAVA_TOOL_OPTIONS (not JAVA_OPTS) so docker-compose `JAVA_OPTS:` overrides cannot drop it. Arch-safe (x86-64 and ARM64).
ENV JAVA_TOOL_OPTIONS="-XX:-UseBASE64Intrinsics"

WORKDIR /usr/src/app
ENTRYPOINT ["/usr/src/app/bin/scheduler-worker"]
