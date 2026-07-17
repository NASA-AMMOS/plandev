FROM alpine:3.15 AS extractor

COPY plandev/merlin-worker/build/distributions/*.tar /usr/src/app/server.tar
RUN mkdir /usr/src/app/extracted
RUN cd /usr/src/app && tar --strip-components 1 -xf server.tar -C extracted

FROM ghcr.io/graalvm/jdk-community:21

# Oracle Linux 9's `python3` package aliases to 3.9 by default (unlike
# eclipse-temurin:21-jre-jammy, which was Ubuntu Jammy and defaulted to 3.10).
# pymerlin requires >=3.10, so pull the python3.11 AppStream package explicitly
# and symlink it over python3/pip3 so `python3 -m pymerlin._server` still
# resolves the way the shim expects.
RUN microdnf install -y findutils python3.11 python3.11-pip \
    && microdnf clean all \
    && ln -sf /usr/bin/python3.11 /usr/bin/python3 \
    && ln -sf /usr/bin/pip3.11 /usr/bin/pip3

COPY --from=extractor /usr/src/app/extracted /usr/src/app

COPY pymerlin /usr/src/pymerlin
RUN pip3 install --no-cache-dir /usr/src/pymerlin

WORKDIR /usr/src/app
ENTRYPOINT ["/usr/src/app/bin/merlin-worker"]
