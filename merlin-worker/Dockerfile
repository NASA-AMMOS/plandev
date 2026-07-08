FROM alpine:3.15 AS extractor

COPY plandev/merlin-worker/build/distributions/*.tar /usr/src/app/server.tar
RUN mkdir /usr/src/app/extracted
RUN cd /usr/src/app && tar --strip-components 1 -xf server.tar -C extracted

FROM eclipse-temurin:21-jre-jammy

RUN apt update && apt install --no-install-recommends -y python3 python3-pip \
    && rm -rf /var/lib/apt/lists/*

COPY --from=extractor /usr/src/app/extracted /usr/src/app

COPY pymerlin /usr/src/pymerlin
RUN pip3 install --no-cache-dir /usr/src/pymerlin

WORKDIR /usr/src/app
ENTRYPOINT ["/usr/src/app/bin/merlin-worker"]
