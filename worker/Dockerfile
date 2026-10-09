# The remote worker's image: a Java 17 runtime and Robocode, nothing of ours (docs/build-and-run.md, "Remote
# worker"). Our code and the opponents' jars are mounted at run time; worker/run.sh is the entry point.
# Build context: the bundle's worker/ directory, which also holds the Robocode setup jar.
# Base images pinned by digest (Temurin 17.0.20.1+1, Ubuntu 26.04, pulled 2026-10-09).

FROM eclipse-temurin:17-jdk@sha256:5d6042fb8cdc14d614e4e421f52e3211fc5aeadddce44cbf9de8ed37c791f824 AS robocode
ARG ROBOCODE_VERSION
ARG ROBOCODE_SHA256
COPY robocode-${ROBOCODE_VERSION}-setup.jar /tmp/setup.jar
# Unpacked like :harness:installRobocode does it, after the same checksum.
RUN echo "${ROBOCODE_SHA256}  /tmp/setup.jar" | sha256sum -c - \
    && mkdir /opt/robocode && cd /opt/robocode && jar xf /tmp/setup.jar && rm -rf META-INF net

FROM eclipse-temurin:17-jre@sha256:207ecae0b2b104dfc6dfa763d1e9cd2041cb344800a180e24cb1a28ed533ff90
COPY --from=robocode /opt/robocode /opt/robocode
# uid 1000 (the image's "ubuntu" user) owns the mounted output directories on the desktop.
USER 1000:1000
ENTRYPOINT ["/bin/bash", "/ogi/bundle/worker/run.sh"]
