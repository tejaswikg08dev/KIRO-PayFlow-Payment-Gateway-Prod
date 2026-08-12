FROM maven:3.9-eclipse-temurin-17 AS builder
WORKDIR /app
COPY ../pom.xml ./pom.xml
COPY ../common-lib ./common-lib
COPY identity-service ./identity-service
RUN mvn clean package -pl identity-service -am -DskipTests

FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY --from=builder /app/identity-service/target/*.jar app.jar
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "app.jar"]
