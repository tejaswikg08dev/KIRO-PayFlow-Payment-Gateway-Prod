# Stage 1: Build
FROM maven:3.9-eclipse-temurin-17 AS builder
WORKDIR /app

# Copy parent pom and all modules (Maven reactor requires all to be present)
COPY pom.xml .
COPY common-lib ./common-lib
COPY service-registry ./service-registry
COPY config-server ./config-server
COPY api-gateway ./api-gateway
COPY identity-service ./identity-service
COPY merchant-service ./merchant-service
COPY payment-service ./payment-service
COPY routing-service ./routing-service
COPY settlement-service ./settlement-service
COPY webhook-service ./webhook-service
COPY notification-service ./notification-service
COPY bank-simulator ./bank-simulator

# Build only the target service and its dependencies
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
