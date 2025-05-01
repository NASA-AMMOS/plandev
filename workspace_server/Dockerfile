FROM alpine:3.15 AS extractor

COPY build/distributions/workspace_server.tar /usr/src/app/server.tar
RUN mkdir /usr/src/app/extracted
RUN cd /usr/src/app && tar --strip-components 1 -xf server.tar -C extracted

FROM eclipse-temurin:21-jre-jammy

COPY --from=extractor /usr/src/app/extracted /usr/src/app

HEALTHCHECK --interval=30s --timeout=2s --start-period=2s --retries=15 \
CMD curl --sf http://localhost:$PORT/health || exit 1

WORKDIR /usr/src/app
ENTRYPOINT ["/usr/src/app/bin/workspace_server"]
