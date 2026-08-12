# Phase 4 Part 1: Infrastructure Setup

## Overview

This part sets up the foundational infrastructure services: parent POM for dependency management, a shared common library, Eureka service registry for discovery, and Spring Cloud Config Server for centralized configuration.

## Parent POM Setup

The parent POM manages all dependency versions and shared plugins across microservices.

### Key Dependencies Managed

| Dependency | Version | Purpose |
|-----------|---------|---------|
| Spring Boot | 3.2.x | Application framework |
| Spring Cloud | 2023.0.x | Distributed patterns |
| Lombok | 1.18.x | Boilerplate reduction |
| MapStruct | 1.5.x | DTO mapping |
| SpringDoc OpenAPI | 2.3.x | API documentation |
| Testcontainers | 1.19.x | Integration testing |

### Plugin Configuration
```xml
<build>
    <plugins>
        <plugin>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-maven-plugin</artifactId>
            <configuration>
                <excludes>
                    <exclude>
                        <groupId>org.projectlombok</groupId>
                        <artifactId>lombok</artifactId>
                    </exclude>
                </excludes>
            </configuration>
        </plugin>
    </plugins>
</build>
```

## Common Library (common-lib)

Shared module with no Spring Boot auto-configuration — just POJOs and utilities.

### Contents
- `ApiResponse<T>` — Standard response envelope
- `ApiError` — Error response structure
- `BaseEntity` — Audited JPA base class
- `PagedResponse<T>` — Pagination wrapper
- Custom exceptions (BusinessException, ResourceNotFoundException)
- Utility classes (DateUtils, JsonUtils)
- Constants (Queues, Exchange names, Header names)

### Usage in Other Modules
```xml
<dependency>
    <groupId>com.payflow</groupId>
    <artifactId>common-lib</artifactId>
    <version>${project.version}</version>
</dependency>
```

## Service Registry (Eureka Server)

### Application Setup
```java
@SpringBootApplication
@EnableEurekaServer
public class ServiceRegistryApplication {
    public static void main(String[] args) {
        SpringApplication.run(ServiceRegistryApplication.class, args);
    }
}
```

### Configuration
```yaml
server:
  port: 8761

eureka:
  client:
    register-with-eureka: false
    fetch-registry: false
  server:
    enable-self-preservation: true
    renewal-percent-threshold: 0.85
```

### Client Configuration (all other services)
```yaml
eureka:
  client:
    service-url:
      defaultZone: http://localhost:8761/eureka/
  instance:
    prefer-ip-address: true
    instance-id: ${spring.application.name}:${random.uuid}
```

## Config Server

### Application Setup
```java
@SpringBootApplication
@EnableConfigServer
public class ConfigServerApplication {
    public static void main(String[] args) {
        SpringApplication.run(ConfigServerApplication.class, args);
    }
}
```

### Configuration
```yaml
server:
  port: 8888

spring:
  cloud:
    config:
      server:
        git:
          uri: file:///config-repo  # Local git repo for dev
          default-label: main
        encrypt:
          enabled: true
```

### Config Client Setup (all services)
```yaml
spring:
  config:
    import: optional:configserver:http://localhost:8888
  cloud:
    config:
      fail-fast: false
      retry:
        max-attempts: 3
```

## Verification Steps

```bash
# 1. Start Config Server
cd backend/config-server && mvn spring-boot:run

# 2. Verify config server
curl http://localhost:8888/actuator/health
# {"status":"UP"}

# 3. Start Service Registry
cd backend/service-registry && mvn spring-boot:run

# 4. Verify Eureka dashboard
open http://localhost:8761
# Should show Eureka dashboard with no registered instances

# 5. Start any service and verify it registers
# Check Eureka dashboard — service should appear within 30s
```
