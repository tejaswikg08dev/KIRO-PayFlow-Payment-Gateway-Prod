# Stage 1: Build
FROM maven:3.9-eclipse-temurin-17 AS builder
WORKDIR /app
COPY pom.xml .
COPY common-lib ./common-lib
COPY config-server ./config-server
RUN mvn clean package -pl config-server -am -DskipTests -B

# Stage 2: Runtime
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
RUN addgroup -S payflow && adduser -S payflow -G payflow
USER payflow
COPY --from=builder /app/config-server/target/*.jar app.jar
EXPOSE 8888
HEALTHCHECK --interval=15s --timeout=10s --retries=5 --start-period=30s \
    CMD wget -qO- http://localhost:8888/actuator/health || exit 1
ENTRYPOINT ["java", "-jar", "app.jar"]
