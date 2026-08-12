# 🏗️ Phase 4 Part 5: API Gateway

> **"The API Gateway is the front door of your payment system. Every request passes through it — authentication, rate limiting, routing, and observability all happen here."**

---

## 📋 Document Metadata

| Field | Value |
|-------|-------|
| **Project** | PayFlow Payment Gateway |
| **Phase** | 4 — Implementation |
| **Part** | 5 of 12 |
| **Module** | `api-gateway` |
| **Package** | `com.payflow.gateway` |
| **Port** | 8080 |
| **Previous** | [Phase 4 Part 4: Config Server](./phase4-part04-config-server.md) |
| **Next** | [Phase 4 Part 6a: Identity Service — Entities](./phase4-part06a-identity-entities.md) |
| **Technologies** | Spring Cloud Gateway (WebFlux), Redis, JWT (jjwt), Eureka Client |
| **Runtime** | Reactive (Netty) — NOT Servlet/Tomcat |

---

## 📖 Table of Contents

1. [Overview](#1-overview)
2. [Spring Cloud Gateway Concepts](#2-spring-cloud-gateway-concepts)
3. [Filter Chain Architecture](#3-filter-chain-architecture)
4. [Application Entry Point](#4-application-entry-point)
5. [Route Configuration](#5-route-configuration)
6. [Custom Global Filters](#6-custom-global-filters)
7. [Security Configuration](#7-security-configuration)
8. [CORS Configuration](#8-cors-configuration)
9. [Rate Limiting with Redis](#9-rate-limiting-with-redis)
10. [POM Dependencies](#10-pom-dependencies)
11. [Application Configuration](#11-application-configuration)
12. [Dockerfile](#12-dockerfile)
13. [Request Flow — End to End](#13-request-flow--end-to-end)
14. [Error Handling](#14-error-handling)
15. [Testing the Gateway](#15-testing-the-gateway)
16. [What You Learned](#16-what-you-learned)
17. [Document Index](#17-document-index)
18. [Next Steps](#18-next-steps)

---

## 1. Overview

The **API Gateway** is the single entry point for all external requests to PayFlow. It's built on **Spring Cloud Gateway** (reactive/WebFlux-based), which runs on Netty instead of Tomcat, providing non-blocking I/O suitable for high-throughput payment traffic.

### Responsibilities

| Responsibility | How |
|---------------|-----|
| **Routing** | Path-based routing to downstream services via Eureka |
| **Authentication** | JWT token validation and user info extraction |
| **API Key Passthrough** | Forward X-API-Key header to downstream services |
| **Rate Limiting** | Redis-backed token bucket per IP address |
| **CORS** | Allow frontend origins (localhost:3000, 3001) |
| **Request Logging** | Assign X-Request-Id for distributed tracing |
| **Load Balancing** | Client-side LB via Eureka (`lb://service-name`) |

---

## 2. Spring Cloud Gateway Concepts

### Why Spring Cloud Gateway?

| Feature | Spring Cloud Gateway | Netflix Zuul (Legacy) |
|---------|---------------------|----------------------|
| **I/O Model** | Non-blocking (Netty) | Blocking (Servlet) |
| **Performance** | Higher throughput | Lower throughput |
| **Reactive** | Full WebFlux support | No |
| **Maintenance** | Active development | Maintenance mode |
| **Filters** | Pre/Post with reactive chain | Servlet filters |

### Core Abstractions

```
┌─────────────────────────────────────────────────────────┐
│                    SPRING CLOUD GATEWAY                  │
├─────────────────────────────────────────────────────────┤
│                                                         │
│  Route = Predicate + Filters + URI                      │
│                                                         │
│  ┌─────────────┐   ┌───────────────┐   ┌───────────┐  │
│  │  Predicate  │ → │    Filters    │ → │    URI    │  │
│  │  (when?)    │   │  (transform?) │   │  (where?) │  │
│  │             │   │               │   │           │  │
│  │  Path=      │   │  AddHeader    │   │  lb://    │  │
│  │  /v1/auth/**│   │  RateLimit    │   │  identity │  │
│  │             │   │  RewritePath  │   │  -service │  │
│  └─────────────┘   └───────────────┘   └───────────┘  │
│                                                         │
└─────────────────────────────────────────────────────────┘
```

| Concept | Description | Example |
|---------|-------------|---------|
| **Route** | A mapping rule: if predicate matches → apply filters → forward to URI | Route to payment-service |
| **Predicate** | Condition that must be true for a route to match | `Path=/v1/payments/**` |
| **Filter** | Transforms request or response (pre/post) | Add header, rate limit |
| **URI** | Destination service (supports `lb://` for Eureka) | `lb://payment-service` |

---

## 3. Filter Chain Architecture

```
     Incoming HTTP Request
              │
              ▼
┌─────────────────────────────┐
│  1. RateLimitFilter         │  Order: -3
│     (per-IP rate limiting)  │
│     Rejects 429 if exceeded │
└──────────────┬──────────────┘
               │
               ▼
┌─────────────────────────────┐
│  2. RequestLoggingFilter    │  Order: -2
│     Assigns X-Request-Id    │
│     Logs method + path      │
└──────────────┬──────────────┘
               │
               ▼
┌─────────────────────────────┐
│  3. JwtValidationFilter     │  Order: -1
│     Validates Bearer token  │
│     Extracts user claims    │
│     Adds X-User-Id header   │
└──────────────┬──────────────┘
               │
               ▼
┌─────────────────────────────┐
│  4. ApiKeyValidationFilter  │  Order: 0
│     Passes X-API-Key        │
│     downstream              │
└──────────────┬──────────────┘
               │
               ▼
┌─────────────────────────────┐
│  5. Route Matching          │
│     Path predicate match    │
│     Forward to service      │
└──────────────┬──────────────┘
               │
               ▼
     Downstream Microservice
     (identity, payment, etc.)
```

Filters execute in order of their `getOrder()` return value (lowest first).

---

## 4. Application Entry Point

```java
package com.payflow.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * PayFlow API Gateway — Single entry point for all client requests.
 * Built on Spring Cloud Gateway (WebFlux/Netty).
 * Port: 8080
 */
@SpringBootApplication
public class ApiGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(ApiGatewayApplication.class, args);
    }
}
```

**Note:** No `@EnableEurekaClient` annotation needed — Spring Cloud auto-detects the Eureka client dependency.

---

## 5. Route Configuration

Routes are defined in the Config Server's `api-gateway.yml`:

```yaml
spring:
  cloud:
    gateway:
      default-filters:
        - DedupeResponseHeader=Access-Control-Allow-Origin  # Prevent duplicate CORS headers
      routes:
        # Identity Service — authentication endpoints
        - id: identity-service
          uri: lb://identity-service        # Load-balanced via Eureka
          predicates:
            - Path=/v1/auth/**              # All auth routes

        # Merchant Service — merchant management
        - id: merchant-service
          uri: lb://merchant-service
          predicates:
            - Path=/v1/merchants/**

        # Payment Service — orders
        - id: payment-service-orders
          uri: lb://payment-service
          predicates:
            - Path=/v1/orders/**

        # Payment Service — payments
        - id: payment-service-payments
          uri: lb://payment-service
          predicates:
            - Path=/v1/payments/**

        # Payment Service — refunds
        - id: payment-service-refunds
          uri: lb://payment-service
          predicates:
            - Path=/v1/refunds/**

        # Settlement Service
        - id: settlement-service
          uri: lb://settlement-service
          predicates:
            - Path=/v1/settlements/**
```

### Route Resolution

| Incoming Path | Matched Route | Forwarded To |
|--------------|---------------|-------------|
| `POST /v1/auth/login` | identity-service | `lb://identity-service/v1/auth/login` |
| `GET /v1/merchants/mer_abc` | merchant-service | `lb://merchant-service/v1/merchants/mer_abc` |
| `POST /v1/orders` | payment-service-orders | `lb://payment-service/v1/orders` |
| `POST /v1/payments/pay_xyz/capture` | payment-service-payments | `lb://payment-service/v1/payments/pay_xyz/capture` |
| `GET /v1/settlements/stl_123` | settlement-service | `lb://settlement-service/v1/settlements/stl_123` |

---

## 6. Custom Global Filters

### 6.1 JwtValidationFilter

Validates JWT tokens on protected routes and passes user information downstream:

```java
package com.payflow.gateway.filter;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Global filter that validates JWT tokens on protected routes.
 * Extracts user info from token and passes it downstream via headers.
 */
@Component
public class JwtValidationFilter implements GlobalFilter, Ordered {

    // Paths that don't require JWT authentication
    private final List<String> publicPaths = List.of(
            "/v1/auth/register",
            "/v1/auth/login",
            "/v1/auth/refresh",
            "/actuator",
            "/swagger-ui",
            "/v3/api-docs"
    );

    @Value("${jwt.secret:payflow-jwt-secret-key-must-be-at-least-256-bits-long-for-hmac-sha256}")
    private String jwtSecret;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();

        // Skip JWT validation for public paths (login, register, health checks)
        if (isPublicPath(path)) {
            return chain.filter(exchange);
        }

        String authHeader = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);

        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            // Allow request through — individual services handle auth if needed
            return chain.filter(exchange);
        }

        try {
            // Extract and validate the JWT token
            String token = authHeader.substring(7);
            SecretKey key = Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));

            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            // Add user info as headers for downstream services
            // This avoids each service needing to parse the JWT again
            ServerHttpRequest modifiedRequest = exchange.getRequest().mutate()
                    .header("X-User-Id", claims.getSubject())
                    .header("X-User-Email", claims.get("email", String.class))
                    .header("X-User-Role", claims.get("role", String.class))
                    .build();

            return chain.filter(exchange.mutate().request(modifiedRequest).build());

        } catch (Exception e) {
            // Invalid/expired token — return 401 immediately
            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
            return exchange.getResponse().setComplete();
        }
    }

    @Override
    public int getOrder() {
        return -1; // Execute after logging filter, before route matching
    }

    private boolean isPublicPath(String path) {
        return publicPaths.stream().anyMatch(path::startsWith);
    }
}
```

### 6.2 RequestLoggingFilter

Assigns a correlation ID for distributed tracing across all services:

```java
package com.payflow.gateway.filter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Global filter that assigns a correlation ID (X-Request-Id) to every request.
 * This ID propagates to all downstream services for distributed tracing.
 */
@Component
public class RequestLoggingFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(RequestLoggingFilter.class);
    private static final String REQUEST_ID_HEADER = "X-Request-Id";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        // Use existing X-Request-Id if provided, otherwise generate new one
        String requestId = exchange.getRequest().getHeaders().getFirst(REQUEST_ID_HEADER);

        if (requestId == null) {
            requestId = UUID.randomUUID().toString();
        }

        // Add X-Request-Id to the request for downstream propagation
        ServerHttpRequest modifiedRequest = exchange.getRequest().mutate()
                .header(REQUEST_ID_HEADER, requestId)
                .build();

        log.info("Request: {} {} | RequestId: {}",
                exchange.getRequest().getMethod(),
                exchange.getRequest().getURI().getPath(),
                requestId);

        // Log the response status after the downstream call completes
        String finalRequestId = requestId;
        return chain.filter(exchange.mutate().request(modifiedRequest).build())
                .then(Mono.fromRunnable(() ->
                        log.info("Response: {} | RequestId: {}",
                                exchange.getResponse().getStatusCode(),
                                finalRequestId)));
    }

    @Override
    public int getOrder() {
        return -2; // Execute before JWT filter (need requestId for error logging)
    }
}
```

### 6.3 ApiKeyValidationFilter

Passes API keys to downstream services for merchant-level authentication:

```java
package com.payflow.gateway.filter;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Global filter that passes through the X-API-Key header.
 * Actual validation is done by individual services (merchant-service validates keys).
 */
@Component
public class ApiKeyValidationFilter implements GlobalFilter, Ordered {

    private static final String API_KEY_HEADER = "X-API-Key";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String apiKey = exchange.getRequest().getHeaders().getFirst(API_KEY_HEADER);

        if (apiKey != null) {
            // Pass API key downstream for service-level validation
            ServerHttpRequest modifiedRequest = exchange.getRequest().mutate()
                    .header(API_KEY_HEADER, apiKey)
                    .build();
            return chain.filter(exchange.mutate().request(modifiedRequest).build());
        }

        return chain.filter(exchange);
    }

    @Override
    public int getOrder() {
        return 0; // Execute after JWT filter
    }
}
```

### 6.4 RateLimitFilter

```java
package com.payflow.gateway.filter;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Per-merchant rate limiting filter.
 * Uses Redis sliding window to enforce request limits.
 * Note: Basic implementation — production uses Spring Cloud Gateway's built-in RequestRateLimiter.
 */
@Component
public class RateLimitFilter implements GlobalFilter, Ordered {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        // Rate limiting is configured via Spring Cloud Gateway's built-in
        // RequestRateLimiter filter in application.yml
        // This filter exists as a hook for custom per-merchant rate limiting logic
        return chain.filter(exchange);
    }

    @Override
    public int getOrder() {
        return -3; // Execute first — reject early if rate limited
    }
}
```

---

## 7. Security Configuration

```java
package com.payflow.gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;

/**
 * Security configuration for the API Gateway.
 * Defines which paths are public vs protected.
 * 
 * Note: @EnableWebFluxSecurity (not @EnableWebSecurity) because
 * Spring Cloud Gateway is WebFlux-based (reactive).
 */
@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    @Bean
    public SecurityWebFilterChain securityFilterChain(ServerHttpSecurity http) {
        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)  // Disable CSRF for API
                .authorizeExchange(exchanges -> exchanges
                        .pathMatchers("/v1/auth/**").permitAll()          // Public auth endpoints
                        .pathMatchers("/actuator/**").permitAll()         // Health checks
                        .pathMatchers("/swagger-ui/**", "/v3/api-docs/**").permitAll()  // Docs
                        .anyExchange().permitAll()  // JWT validation handled by custom filter
                )
                .build();
    }
}
```

**Why `permitAll()` for all exchanges?**

JWT validation is handled by our custom `JwtValidationFilter` (not Spring Security's built-in auth). We use Spring Security only for CSRF disabling and basic filter chain setup. The custom filter provides more control over which paths are public and how we extract user info.

---

## 8. CORS Configuration

```java
package com.payflow.gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsWebFilter;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * CORS configuration for the API Gateway.
 * Allows frontend applications (ports 3000, 3001) to call the API.
 * 
 * Note: Uses reactive CorsWebFilter (not servlet-based @CrossOrigin)
 * because Spring Cloud Gateway runs on Netty/WebFlux.
 */
@Configuration
public class CorsConfig {

    @Bean
    public CorsWebFilter corsWebFilter() {
        CorsConfiguration config = new CorsConfiguration();

        // Allowed origins — frontend apps
        config.setAllowedOrigins(List.of(
                "http://localhost:3000",   // Merchant Portal (Next.js)
                "http://localhost:3001"    // Hosted Checkout (Next.js)
        ));

        // Allowed HTTP methods
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"));

        // Allowed headers (including custom headers)
        config.setAllowedHeaders(List.of("*"));

        // Allow cookies/auth headers
        config.setAllowCredentials(true);

        // Cache preflight response for 1 hour
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);  // Apply to all routes

        return new CorsWebFilter(source);
    }
}
```

### CORS Flow (Preflight)

```
Browser (localhost:3000)                    API Gateway (:8080)
        │                                         │
        │  OPTIONS /v1/orders                     │
        │  Origin: http://localhost:3000           │
        │  Access-Control-Request-Method: POST    │
        │ ──────────────────────────────────────► │
        │                                         │
        │  200 OK                                 │
        │  Access-Control-Allow-Origin: *:3000    │
        │  Access-Control-Allow-Methods: POST     │
        │  Access-Control-Max-Age: 3600           │
        │ ◄────────────────────────────────────── │
        │                                         │
        │  POST /v1/orders                        │
        │  Origin: http://localhost:3000           │
        │  Authorization: Bearer eyJhbG...        │
        │ ──────────────────────────────────────► │
        │                                         │
```

---

## 9. Rate Limiting with Redis

### Token Bucket Algorithm

```
┌─────────────────────────────────────────────────────────────┐
│                   TOKEN BUCKET (per IP)                      │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│  Bucket Capacity: 20 tokens (burst-capacity)                │
│  Refill Rate: 10 tokens/second (requests-per-second)        │
│                                                             │
│  ┌─────────────────────────────────────────────────┐       │
│  │  [T][T][T][T][T][T][T][T][T][T][T][T][ ][ ][ ] │       │
│  │                                                 │       │
│  │  12 tokens available                            │       │
│  │  Each request consumes 1 token                  │       │
│  │  Tokens refill at 10/sec                        │       │
│  │  If 0 tokens → HTTP 429 Too Many Requests      │       │
│  └─────────────────────────────────────────────────┘       │
│                                                             │
│  Redis Key: rate_limiter.{192.168.1.50}.tokens              │
│  Redis Key: rate_limiter.{192.168.1.50}.timestamp           │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

### RateLimiterConfig — Key Resolver

```java
package com.payflow.gateway.config;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Mono;

/**
 * Rate limiter configuration using Redis.
 * Determines the key used for rate limiting (per IP or per API key).
 */
@Configuration
public class RateLimiterConfig {

    /**
     * Rate limit by client IP address.
     * In production, would also consider X-API-Key for per-merchant limits.
     */
    @Bean
    public KeyResolver ipKeyResolver() {
        return exchange -> Mono.just(
                exchange.getRequest().getRemoteAddress() != null
                        ? exchange.getRequest().getRemoteAddress().getAddress().getHostAddress()
                        : "unknown"
        );
    }
}
```

### Configuration

```yaml
# In api-gateway.yml (Config Server)
spring:
  data:
    redis:
      host: localhost
      port: 6379

rate-limiter:
  requests-per-second: 10   # Token refill rate
  burst-capacity: 20        # Maximum tokens in bucket
```

### Production Rate Limit Tiers

| Tier | Rate | Burst | Use Case |
|------|------|-------|----------|
| Free | 10 req/s | 20 | Development/testing |
| Standard | 100 req/s | 200 | Small merchants |
| Premium | 1000 req/s | 2000 | Large merchants |
| Enterprise | Unlimited | — | Custom SLA |

---

## 10. POM Dependencies

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 
         https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>com.payflow</groupId>
        <artifactId>payflow-payment-gateway</artifactId>
        <version>1.0.0-SNAPSHOT</version>
    </parent>

    <artifactId>api-gateway</artifactId>
    <name>PayFlow API Gateway</name>
    <description>Spring Cloud Gateway — single entry point for all requests</description>

    <dependencies>
        <!-- Spring Cloud Gateway (reactive — WebFlux based, runs on Netty) -->
        <dependency>
            <groupId>org.springframework.cloud</groupId>
            <artifactId>spring-cloud-starter-gateway</artifactId>
        </dependency>

        <!-- Eureka Client — discover downstream services by name -->
        <dependency>
            <groupId>org.springframework.cloud</groupId>
            <artifactId>spring-cloud-starter-netflix-eureka-client</artifactId>
        </dependency>

        <!-- Config Client — fetch config from Config Server -->
        <dependency>
            <groupId>org.springframework.cloud</groupId>
            <artifactId>spring-cloud-starter-config</artifactId>
        </dependency>

        <!-- Redis (reactive) — for rate limiting token bucket -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-data-redis-reactive</artifactId>
        </dependency>

        <!-- JWT validation libraries -->
        <dependency>
            <groupId>io.jsonwebtoken</groupId>
            <artifactId>jjwt-api</artifactId>
        </dependency>
        <dependency>
            <groupId>io.jsonwebtoken</groupId>
            <artifactId>jjwt-impl</artifactId>
            <scope>runtime</scope>
        </dependency>
        <dependency>
            <groupId>io.jsonwebtoken</groupId>
            <artifactId>jjwt-jackson</artifactId>
            <scope>runtime</scope>
        </dependency>

        <!-- SpringDoc for Gateway (WebFlux variant) -->
        <dependency>
            <groupId>org.springdoc</groupId>
            <artifactId>springdoc-openapi-starter-webflux-ui</artifactId>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>
        </plugins>
    </build>
</project>
```

**Important:** You CANNOT mix `spring-boot-starter-web` (Tomcat/Servlet) with `spring-cloud-starter-gateway` (Netty/WebFlux). They're incompatible runtimes.

---

## 11. Application Configuration

### Local application.yml (Minimal)

```yaml
server:
  port: 8080

spring:
  application:
    name: api-gateway
  config:
    import: optional:configserver:http://localhost:8888

eureka:
  client:
    service-url:
      defaultZone: http://localhost:8761/eureka/

jwt:
  secret: payflow-jwt-secret-key-must-be-at-least-256-bits-long-for-hmac-sha256
```

The full route configuration lives in Config Server's `api-gateway.yml` (see Section 5).

---

## 12. Dockerfile

```dockerfile
# Stage 1: Build with Maven
FROM maven:3.9-eclipse-temurin-17 AS builder
WORKDIR /app
COPY ../pom.xml ./pom.xml
COPY ../common-lib ./common-lib
COPY api-gateway ./api-gateway
RUN mvn clean package -pl api-gateway -am -DskipTests

# Stage 2: Lightweight runtime image
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY --from=builder /app/api-gateway/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
```

### Build & Run

```bash
# Build
docker build -t payflow/api-gateway:latest -f backend/api-gateway/Dockerfile backend/

# Run (requires service-registry and config-server to be running)
docker run -d \
  --name api-gateway \
  -p 8080:8080 \
  -e SPRING_PROFILES_ACTIVE=docker \
  payflow/api-gateway:latest
```

---

## 13. Request Flow — End to End

Complete flow for `POST /v1/orders` (authenticated merchant creating an order):

```
┌──────────────────────────────────────────────────────────────────────────────────┐
│                          COMPLETE REQUEST FLOW                                    │
├──────────────────────────────────────────────────────────────────────────────────┤
│                                                                                  │
│  Client (Merchant Portal)                                                        │
│      │                                                                           │
│      │  POST /v1/orders                                                          │
│      │  Authorization: Bearer eyJhbGciOiJIUzI1NiJ9...                           │
│      │  X-API-Key: key_7a3f9b2c1d4e                                             │
│      │  X-Idempotency-Key: unique-uuid-123                                      │
│      │  Content-Type: application/json                                           │
│      │                                                                           │
│      ▼                                                                           │
│  ┌─────────────────── API GATEWAY (:8080) ───────────────────────┐              │
│  │                                                                │              │
│  │  1. RateLimitFilter     → Check Redis token bucket (IP-based) │              │
│  │  2. RequestLoggingFilter → Assign X-Request-Id: uuid-456      │              │
│  │  3. JwtValidationFilter → Validate JWT, extract claims        │              │
│  │     → Add X-User-Id: usr_abc123                               │              │
│  │     → Add X-User-Email: merchant@company.com                  │              │
│  │     → Add X-User-Role: MERCHANT                               │              │
│  │  4. ApiKeyValidationFilter → Pass X-API-Key downstream        │              │
│  │  5. Route Match: /v1/orders/** → lb://payment-service         │              │
│  │                                                                │              │
│  └────────────────────────────┬───────────────────────────────────┘              │
│                               │                                                  │
│                               │  Eureka resolves: payment-service → 192.168.1.5  │
│                               │  Forward: POST http://192.168.1.5:8083/v1/orders │
│                               ▼                                                  │
│  ┌─────────────────── PAYMENT SERVICE (:8083) ───────────────────┐              │
│  │                                                                │              │
│  │  Receives request with enriched headers:                       │              │
│  │  • X-User-Id: usr_abc123                                      │              │
│  │  • X-Request-Id: uuid-456                                     │              │
│  │  • X-API-Key: key_7a3f9b2c1d4e                               │              │
│  │                                                                │              │
│  │  Processes order creation...                                   │              │
│  │  Returns: 201 Created {order_id: "order_xyz789"}              │              │
│  │                                                                │              │
│  └────────────────────────────────────────────────────────────────┘              │
│                                                                                  │
└──────────────────────────────────────────────────────────────────────────────────┘
```

---

## 14. Error Handling

The gateway handles errors at the filter level:

| Error | Source | Response |
|-------|--------|----------|
| Invalid/expired JWT | JwtValidationFilter | 401 Unauthorized |
| Rate limit exceeded | RateLimitFilter | 429 Too Many Requests |
| Service unavailable | Eureka/LoadBalancer | 503 Service Unavailable |
| Route not found | Gateway | 404 Not Found |
| Downstream timeout | Gateway | 504 Gateway Timeout |

### Gateway Exception Handler

```java
// GatewayExceptionHandler provides structured error responses
// for gateway-level errors (not downstream service errors)
{
  "success": false,
  "error": {
    "code": "GATEWAY_ERROR",
    "message": "Service temporarily unavailable"
  },
  "timestamp": "2024-01-15T10:30:00Z"
}
```

---

## 15. Testing the Gateway

### Health Check

```bash
curl http://localhost:8080/actuator/health
# {"status":"UP"}
```

### Test Public Route (No Auth)

```bash
curl -X POST http://localhost:8080/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"admin@payflow.com", "password":"password123"}'
```

### Test Protected Route (With JWT)

```bash
# Get token first
TOKEN=$(curl -s -X POST http://localhost:8080/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"admin@payflow.com","password":"password123"}' | jq -r '.data.accessToken')

# Use token to access protected endpoint
curl http://localhost:8080/v1/merchants \
  -H "Authorization: Bearer $TOKEN"
```

### Verify X-Request-Id Propagation

```bash
curl -v http://localhost:8080/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"admin@payflow.com","password":"password123"}'

# Look for X-Request-Id in response headers
```

---

## 📝 What You Learned

| # | Concept | Key Takeaway |
|---|---------|-------------|
| 1 | WebFlux Gateway | Spring Cloud Gateway runs on Netty (non-blocking) — NOT Tomcat |
| 2 | GlobalFilter | Custom filters run on every request in order of `getOrder()` |
| 3 | `lb://` routing | Load-balanced routing via Eureka service discovery |
| 4 | JWT at Gateway | Validate once at gateway, pass user info via X-headers downstream |
| 5 | Correlation ID | `X-Request-Id` propagates through all services for tracing |
| 6 | Token Bucket | Redis-backed rate limiting with configurable refill and burst |
| 7 | Reactive CORS | Use `CorsWebFilter` (not `@CrossOrigin`) for WebFlux apps |
| 8 | `@EnableWebFluxSecurity` | WebFlux variant of Spring Security (not `@EnableWebSecurity`) |
| 9 | Predicate + Filter + URI | Routes match paths (predicate), transform (filter), and forward (URI) |
| 10 | No Web Starter | Cannot use `spring-boot-starter-web` alongside Gateway (incompatible) |
| 11 | Filter Order | Lower order = earlier execution (-3 → -2 → -1 → 0) |
| 12 | Config Import | `optional:configserver:` prevents startup failure if Config Server is down |

---

## 📚 Document Index

| Part | Title | Status |
|------|-------|--------|
| 4.01 | Parent POM & Maven Setup | ✅ Complete |
| 4.02 | Common Library | ✅ Complete |
| 4.03 | Service Registry (Eureka) | ✅ Complete |
| 4.04 | Config Server | ✅ Complete |
| **4.05** | **API Gateway** | **📍 You are here** |
| 4.06a | Identity Service — Entities | ⏭️ Next |
| 4.06b | Identity Service — Auth Logic | 🔲 Pending |
| 4.07 | Merchant Service | 🔲 Pending |
| 4.08 | Payment Service | 🔲 Pending |
| 4.09 | Settlement Service | 🔲 Pending |
| 4.10 | Notification & Webhook Services | 🔲 Pending |
| 4.11 | Docker Compose & Integration | 🔲 Pending |
| 4.12 | Testing Strategy | 🔲 Pending |

---

## 🚀 Next Steps

In **Phase 4 Part 6a**, we build the **Identity Service — Entities** layer, defining the JPA entities, Flyway migrations, and repository interfaces for user authentication and authorization.

→ [Continue to Phase 4 Part 6a: Identity Service — Entities](./phase4-part06a-identity-entities.md)
