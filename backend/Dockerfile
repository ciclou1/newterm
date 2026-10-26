# BuildKit is required (cache mounts). The image deliberately has no
# `# syntax=` directive so air-gapped builders do not fetch a frontend image
# from Docker Hub; Docker's bundled BuildKit frontend supports this file.
#
# Reproducible backend image.
#
# F1 note: the previous build ran `mvn dependency:go-offline` in its own layer.
# That goal resolves every artifact (including plugin and test dependencies) in
# one unbounded download with a quiet log, so a slow Maven Central link looked
# like a hang (observed >20 min; 333 s even on a healthy run). This build does
# a single real `package -DskipTests`, bounds every HTTP read/connect, retries
# transient failures, and keeps ~/.m2 in a BuildKit cache mount so dependency
# downloads survive source changes and layer invalidation.
#
# Base images are pinned by manifest-list digest (multi-arch). Override the
# build args to use a registry mirror without changing the pin.
ARG MAVEN_IMAGE=maven@sha256:6fdc855a6ed81d288ca7ca37ac6ff5e9308b612485c0801d70b25a858c83d237
ARG JRE_IMAGE=eclipse-temurin@sha256:b48e02e0ea88d7bfb305335df94923bdef394905f4147dccd52e873cf17b1088
FROM ${MAVEN_IMAGE} AS build
WORKDIR /workspace

# Optional corporate/regional Maven mirror. Empty = Maven Central.
ARG MAVEN_MIRROR_URL=""

COPY pom.xml ./
COPY backend/pom.xml backend/pom.xml
COPY mcp-server/pom.xml mcp-server/pom.xml
COPY backend/src backend/src

RUN --mount=type=cache,target=/root/.m2,sharing=locked \
    set -e; \
    if [ -n "${MAVEN_MIRROR_URL}" ]; then \
      mkdir -p /root/.m2; \
      printf '%s\n' \
        '<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0">' \
        '  <mirrors><mirror>' \
        '    <id>build-mirror</id><mirrorOf>*</mirrorOf>' \
        "    <url>${MAVEN_MIRROR_URL}</url>" \
        '  </mirror></mirrors>' \
        '</settings>' > /root/.m2/settings.xml; \
    fi; \
    mvn -B -ntp -pl backend -am package -DskipTests \
      -Dmaven.wagon.http.connectionTimeout=10000 \
      -Dmaven.wagon.http.readTimeout=60000 \
      -Dmaven.wagon.http.retryHandler.count=3 \
      -Daether.connector.connectTimeout=10000 \
      -Daether.connector.requestTimeout=60000

# ---- Stage 2: runtime ----
FROM ${JRE_IMAGE}
# Revision of the source this image was built from, recorded with evaluation
# jobs. Compose passes it as a build arg; plain `docker build` keeps "unknown".
ARG GIT_COMMIT=unknown
ENV GIT_COMMIT=${GIT_COMMIT}
RUN addgroup -S chatbi && adduser -S chatbi -G chatbi \
    && apk add --no-cache wget tzdata \
    && cp /usr/share/zoneinfo/Asia/Shanghai /etc/localtime \
    && echo "Asia/Shanghai" > /etc/timezone \
    && rm -rf /var/cache/apk/*
WORKDIR /app
COPY --from=build /workspace/backend/target/chatbi-server.jar app.jar
RUN chown -R chatbi:chatbi /app
USER chatbi
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=5s --start-period=90s --retries=5 \
    CMD wget -qO- http://127.0.0.1:8080/actuator/health >/dev/null || exit 1
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-XX:+ExitOnOutOfMemoryError", "-XX:+UseZGC", "-jar", "/app/app.jar"]
