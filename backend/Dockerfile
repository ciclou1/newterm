# ---- Stage 1: build ----
FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /workspace

COPY pom.xml .
COPY backend/pom.xml backend/pom.xml
COPY mcp-server/pom.xml mcp-server/pom.xml
RUN mvn -B -q -pl backend -am dependency:go-offline

COPY backend/src backend/src
RUN mvn -B -q -pl backend -am package -DskipTests

# ---- Stage 2: runtime ----
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S chatbi && adduser -S chatbi -G chatbi \
    && apk add --no-cache wget tzdata \
    && cp /usr/share/zoneinfo/Asia/Shanghai /etc/localtime \
    && echo "Asia/Shanghai" > /etc/timezone
WORKDIR /app
COPY --from=build /workspace/backend/target/chatbi-server.jar app.jar
RUN chown -R chatbi:chatbi /app
USER chatbi
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-XX:+UseZGC", "-jar", "/app/app.jar"]
