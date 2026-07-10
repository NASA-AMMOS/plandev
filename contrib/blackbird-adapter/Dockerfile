# Blackbird external-model backend for PlanDev — container image.
#
# Two stages:
#   1. builder  — builds jplTime + Blackbird from source (Maven, JDK 21 targeting --release 8).
#   2. runtime  — a slim JRE + Python 3 that runs bb_service.py (the /simulate HTTP backend).
#
# Blackbird and jplTime are Apache-2.0 and cloned at build time (not vendored here).
# JNISpice is a *compile-time* dependency of jpl_time and is NOT on Maven Central; you must
# provide its jar in ./vendor (see README). The native SPICE library is only needed at RUNTIME
# if your Blackbird model actually uses the Spice class (exampleAdaptation's core activities do not).

# ---------- Stage 1: build Blackbird + jplTime ----------
FROM maven:3.9-eclipse-temurin-21 AS builder

# JNISpice jar (v2022-05 matches jplTime 2025-10a). Place it under ./vendor before building.
ARG JNISPICE_JAR=JNISpice-v2022-05.jar
COPY vendor/${JNISPICE_JAR} /tmp/jnispice.jar
RUN mvn -q -B install:install-file \
      -Dfile=/tmp/jnispice.jar \
      -DgroupId=gov.nasa.jpl.spice -DartifactId=jnispice -Dversion=v2022-05 -Dpackaging=jar

WORKDIR /src

# jplTime — Blackbird pins gov.nasa.jpl:jpl_time:2025-10a (the repo default version differs, so set it).
RUN git clone --depth 1 https://github.com/nasa-jpl/jplTime.git \
 && cd jplTime \
 && mvn -q -B versions:set -DnewVersion=2025-10a \
 && mvn -q -B -DskipTests install

# Blackbird itself.
RUN git clone --depth 1 https://github.com/nasa-jpl/Blackbird.git \
 && cd Blackbird \
 && mvn -q -B -DskipTests package \
 && mvn -q -B dependency:copy-dependencies -DoutputDirectory=/out/lib \
 && cp -r target/classes /out/classes

# ---------- Stage 2: runtime ----------
FROM eclipse-temurin:21-jre-jammy
RUN apt-get update \
 && apt-get install -y --no-install-recommends python3 \
 && rm -rf /var/lib/apt/lists/*

COPY --from=builder /out /opt/blackbird
COPY bb_service.py /opt/adapter/bb_service.py

# Java expands the trailing /* wildcard on the classpath itself.
ENV BLACKBIRD_CP="/opt/blackbird/classes:/opt/blackbird/lib/*"
ENV BLACKBIRD_MAIN="gov.nasa.jpl.Blackbird"
# If your model uses SPICE, copy the native lib (libJNISpice.so) into the image and point here:
# ENV JPLTIME_LIB="/opt/blackbird/nativelib"

EXPOSE 5001
ENTRYPOINT ["python3", "/opt/adapter/bb_service.py", "5001"]
