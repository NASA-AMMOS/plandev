FROM node:24.18.0-bookworm-slim AS base

# Install jemalloc.
# Update gnutls to pick up debian security fixes flagged by trivy (may not be needed after base image update)
RUN apt-get update \
    && apt-get install --no-install-recommends -y libjemalloc-dev \
#    && apt-get install --no-install-recommends -y --only-upgrade libgnutls30 \
    && rm -rf /var/lib/apt/lists/*

RUN echo "/usr/lib/$(uname -m)-linux-gnu/libjemalloc.so" >> /etc/ld.so.preload

WORKDIR /app

FROM base AS build

# copy node app package metadata first, so dependency installation can be cached.
COPY package.json package-lock.json ./
# Install without lifecycle scripts, then run only scripts approved by allowScripts.
RUN npm ci --ignore-scripts \
    && npm rebuild --strict-allow-scripts
# copy app *code* and build inside the container - node_modules and build files are .dockerignore'd
COPY . .
RUN npm run build-quiet \
    && npm prune --omit=dev --ignore-scripts

FROM base AS runtime
# final docker stage for image, without all the build dependencies from the build stage

ENV NODE_ENV=production

COPY --from=build /app/package.json /app/package-lock.json ./
COPY --from=build /app/node_modules ./node_modules
COPY --from=build /app/build ./build

CMD ["node", "build/server.js"]
