# Stage 1: Build
FROM maven:3.9-eclipse-temurin-17 AS builder
WORKDIR /app
COPY pom.xml .
COPY common-lib ./common-lib
COPY notification-service ./notification-service
RUN mvn clean package -pl notification-service -am -DskipTests -B

# Stage 2: Runtime
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
RUN addgroup -S payflow && adduser -S payflow -G payflow
USER payflow
COPY --from=builder /app/notification-service/target/*.jar app.jar
EXPOSE 8087
HEALTHCHECK --interval=15s --timeout=10s --retries=5 --start-period=30s \
    CMD wget -qO- http://localhost:8087/actuator/health || exit 1
ENTRYPOINT ["java", "-jar", "app.jar"]
