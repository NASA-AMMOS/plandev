FROM node:18.20-bookworm-slim

# Install jemalloc
RUN apt-get update \
    && apt-get install --no-install-recommends -y libjemalloc-dev \
    && apt-get install --no-install-recommends -y --only-upgrade libgnutls30 \
    && rm -rf /var/lib/apt/lists/*

# Set the path for jemalloc
RUN echo "/usr/lib/$(uname -m)-linux-gnu/libjemalloc.so" >> /etc/ld.so.preload

# Set working directory
COPY . /app
WORKDIR /app
CMD [ "npm", "start" ]
