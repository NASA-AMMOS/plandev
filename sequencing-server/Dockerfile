FROM node:24.20.0-bookworm-slim AS base

# Install jemalloc
# Update gnutls to pick up debian security fixes flagged by trivy (may not be needed after base image update)
RUN apt-get update \
    && apt-get install --no-install-recommends -y libjemalloc-dev \
    && rm -rf /var/lib/apt/lists/*

# Set the path for jemalloc
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
RUN npm run build \
    && npm prune --omit=dev --ignore-scripts

FROM base AS runtime
# final docker stage for image, without all the build dependencies from the build stage

ENV NODE_ENV=production

# copy built app files & make them owned by non-root `node` user
COPY --from=build --chown=node:node /app/package.json /app/package-lock.json ./
COPY --from=build --chown=node:node /app/node_modules ./node_modules
COPY --from=build --chown=node:node /app/build ./build

# npm is not required by the running service - remove to reduce vulnerable dependencies
RUN rm -rf /usr/local/lib/node_modules/npm \
    && rm -f /usr/local/bin/npm /usr/local/bin/npx

CMD ["node", "build/app.js"]
