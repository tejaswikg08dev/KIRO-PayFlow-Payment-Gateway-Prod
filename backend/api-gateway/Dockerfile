FROM maven:3.9-eclipse-temurin-17 AS builder
WORKDIR /app
COPY ../pom.xml ./pom.xml
COPY ../common-lib ./common-lib
COPY api-gateway ./api-gateway
RUN mvn clean package -pl api-gateway -am -DskipTests

FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY --from=builder /app/api-gateway/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
