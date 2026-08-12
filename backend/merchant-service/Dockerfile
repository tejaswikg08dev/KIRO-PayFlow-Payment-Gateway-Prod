FROM maven:3.9-eclipse-temurin-17 AS builder
WORKDIR /app
COPY ../pom.xml ./pom.xml
COPY ../common-lib ./common-lib
COPY merchant-service ./merchant-service
RUN mvn clean package -pl merchant-service -am -DskipTests

FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY --from=builder /app/merchant-service/target/*.jar app.jar
EXPOSE 8082
ENTRYPOINT ["java", "-jar", "app.jar"]
