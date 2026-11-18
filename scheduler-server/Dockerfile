FROM alpine:3.15 AS extractor

COPY build/distributions/*.tar /usr/src/app/server.tar
RUN mkdir /usr/src/app/extracted
RUN cd /usr/src/app && tar --strip-components 1 -xf server.tar -C extracted

FROM eclipse-temurin:21-jre-jammy

COPY --from=extractor /usr/src/app/extracted /usr/src/app

# CVE-2026-114938 (Java4Shell): disable the HotSpot Base64 intrinsic until the base image ships a patched JDK.
# JAVA_TOOL_OPTIONS (not JAVA_OPTS) so docker-compose `JAVA_OPTS:` overrides cannot drop it. Arch-safe (x86-64 and ARM64).
ENV JAVA_TOOL_OPTIONS="-XX:-UseBASE64Intrinsics"

WORKDIR /usr/src/app
ENTRYPOINT ["/usr/src/app/bin/scheduler-server"]
