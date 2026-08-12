# Phase 4 Part 2: API Gateway Implementation

## Overview

The API Gateway is the single entry point for all client requests. Built with Spring Cloud Gateway, it handles routing, JWT validation, API key authentication, rate limiting, CORS, and centralized exception handling.

## Dependencies (pom.xml)

```xml
<dependencies>
    <dependency>
        <groupId>org.springframework.cloud</groupId>
        <artifactId>spring-cloud-starter-gateway</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-data-redis-reactive</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-security</artifactId>
    </dependency>
    <dependency>
        <groupId>io.jsonwebtoken</groupId>
        <artifactId>jjwt-api</artifactId>
        <version>0.12.3</version>
    </dependency>
</dependencies>
```

## Route Configuration

```yaml
spring:
  cloud:
    gateway:
      routes:
        - id: identity-service
          uri: http://identity-service:8081
          predicates:
            - Path=/api/v1/auth/**
          filters:
            - StripPrefix=0

        - id: merchant-service
          uri: http://merchant-service:8083
          predicates:
            - Path=/api/v1/merchants/**
          filters:
            - StripPrefix=0
            - name: JwtValidation

        - id: payment-service-orders
          uri: http://payment-service:8082
          predicates:
            - Path=/api/v1/orders/**
          filters:
            - StripPrefix=0
            - name: ApiKeyValidation

        - id: payment-service-public
          uri: http://payment-service:8082
          predicates:
            - Path=/api/v1/payments/**
          filters:
            - StripPrefix=0

      default-filters:
        - name: RequestLogging
        - name: RateLimit
          args:
            requests-per-second: 100
            burst-capacity: 200
```

## JWT Validation Filter

```java
@Component
public class JwtValidationFilter implements GatewayFilter, Ordered {

    private final JwtUtil jwtUtil;
    private static final List<String> PUBLIC_PATHS = List.of(
        "/api/v1/auth/login",
        "/api/v1/auth/register",
        "/api/v1/auth/refresh"
    );

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();

        // Skip public endpoints
        if (PUBLIC_PATHS.stream().anyMatch(path::startsWith)) {
            return chain.filter(exchange);
        }

        // Extract Authorization header
        String authHeader = exchange.getRequest().getHeaders()
            .getFirst(HttpHeaders.AUTHORIZATION);

        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return onError(exchange, "Missing or invalid Authorization header",
                HttpStatus.UNAUTHORIZED);
        }

        String token = authHeader.substring(7);

        try {
            Claims claims = jwtUtil.validateToken(token);
            
            // Add user info to downstream headers
            ServerHttpRequest mutatedRequest = exchange.getRequest().mutate()
                .header("X-User-Id", claims.getSubject())
                .header("X-User-Email", claims.get("email", String.class))
                .header("X-User-Role", claims.get("role", String.class))
                .build();

            return chain.filter(exchange.mutate().request(mutatedRequest).build());
        } catch (ExpiredJwtException e) {
            return onError(exchange, "Token expired", HttpStatus.UNAUTHORIZED);
        } catch (JwtException e) {
            return onError(exchange, "Invalid token", HttpStatus.UNAUTHORIZED);
        }
    }

    private Mono<Void> onError(ServerWebExchange exchange, String message,
                               HttpStatus status) {
        exchange.getResponse().setStatusCode(status);
        exchange.getResponse().getHeaders()
            .setContentType(MediaType.APPLICATION_JSON);

        String body = """
            {"success": false, "message": "%s", "timestamp": "%s"}
            """.formatted(message, Instant.now());

        DataBuffer buffer = exchange.getResponse().bufferFactory()
            .wrap(body.getBytes());
        return exchange.getResponse().writeWith(Mono.just(buffer));
    }

    @Override
    public int getOrder() {
        return -1;  // Run before other filters
    }
}
```

## API Key Validation Filter

```java
@Component
public class ApiKeyValidationFilter implements GatewayFilter, Ordered {

    private final ReactiveRedisTemplate<String, String> redisTemplate;
    private final WebClient merchantServiceClient;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String apiKey = exchange.getRequest().getHeaders()
            .getFirst("X-Api-Key");

        if (apiKey == null || apiKey.isBlank()) {
            return onError(exchange, "Missing X-Api-Key header",
                HttpStatus.UNAUTHORIZED);
        }

        // Check Redis cache first
        String cacheKey = "apikey:" + hashKey(apiKey);

        return redisTemplate.opsForValue().get(cacheKey)
            .switchIfEmpty(validateWithMerchantService(apiKey, cacheKey))
            .flatMap(merchantId -> {
                ServerHttpRequest mutatedRequest = exchange.getRequest().mutate()
                    .header("X-Merchant-Id", merchantId)
                    .build();
                return chain.filter(exchange.mutate()
                    .request(mutatedRequest).build());
            })
            .onErrorResume(e -> onError(exchange, "Invalid API key",
                HttpStatus.UNAUTHORIZED));
    }

    private Mono<String> validateWithMerchantService(String apiKey,
                                                      String cacheKey) {
        return merchantServiceClient.get()
            .uri("/internal/validate-key?key={key}", apiKey)
            .retrieve()
            .bodyToMono(MerchantValidationResponse.class)
            .flatMap(response -> {
                // Cache for 5 minutes
                return redisTemplate.opsForValue()
                    .set(cacheKey, response.merchantId(),
                         Duration.ofMinutes(5))
                    .thenReturn(response.merchantId());
            });
    }

    private String hashKey(String apiKey) {
        return DigestUtils.sha256Hex(apiKey).substring(0, 16);
    }

    @Override
    public int getOrder() {
        return 0;
    }
}
```

## Rate Limiting Configuration

```java
@Configuration
public class RateLimiterConfig {

    @Bean
    public RedisRateLimiter redisRateLimiter() {
        // 100 requests/second, burst capacity of 200
        return new RedisRateLimiter(100, 200, 1);
    }

    @Bean
    public KeyResolver apiKeyResolver() {
        return exchange -> {
            String apiKey = exchange.getRequest().getHeaders()
                .getFirst("X-Api-Key");
            if (apiKey != null) {
                return Mono.just(apiKey);
            }
            // Fallback to IP-based limiting
            return Mono.just(
                exchange.getRequest().getRemoteAddress()
                    .getAddress().getHostAddress()
            );
        };
    }

    @Bean
    public KeyResolver ipKeyResolver() {
        return exchange -> Mono.just(
            exchange.getRequest().getRemoteAddress()
                .getAddress().getHostAddress()
        );
    }
}
```

### Custom Rate Limit Filter (Token Bucket)

```java
@Component
public class RateLimitFilter implements GatewayFilter {

    private final ReactiveRedisTemplate<String, String> redis;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange,
                             GatewayFilterChain chain) {
        String clientId = resolveClientId(exchange);
        String key = "ratelimit:" + clientId;

        return redis.opsForValue().increment(key)
            .flatMap(count -> {
                if (count == 1) {
                    redis.expire(key, Duration.ofSeconds(1)).subscribe();
                }
                if (count > 100) {
                    exchange.getResponse()
                        .setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
                    exchange.getResponse().getHeaders()
                        .set("Retry-After", "1");
                    return exchange.getResponse().setComplete();
                }
                return chain.filter(exchange);
            });
    }
}
```

## CORS Configuration

```java
@Configuration
public class CorsConfig {

    @Bean
    public CorsWebFilter corsWebFilter() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(List.of(
            "http://localhost:5173",     // Vite dev server
            "http://localhost:3000",     // Alternative dev
            "https://dashboard.payflow.io",  // Production
            "https://checkout.payflow.io"    // Hosted checkout
        ));
        config.setAllowedMethods(List.of(
            "GET", "POST", "PUT", "DELETE", "OPTIONS"
        ));
        config.setAllowedHeaders(List.of(
            "Authorization", "Content-Type", "X-Api-Key",
            "X-Idempotency-Key", "X-Request-Id"
        ));
        config.setExposedHeaders(List.of(
            "X-RateLimit-Remaining", "X-RateLimit-Reset",
            "X-Request-Id"
        ));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source =
            new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);

        return new CorsWebFilter(source);
    }
}
```

## Global Exception Handler

```java
@Component
@Order(-2)
public class GatewayExceptionHandler implements ErrorWebExceptionHandler {

    private final ObjectMapper objectMapper;

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
        HttpStatus status;
        String message;

        if (ex instanceof ResponseStatusException rse) {
            status = HttpStatus.valueOf(rse.getStatusCode().value());
            message = rse.getReason();
        } else if (ex instanceof ConnectException) {
            status = HttpStatus.SERVICE_UNAVAILABLE;
            message = "Service temporarily unavailable";
        } else if (ex instanceof TimeoutException) {
            status = HttpStatus.GATEWAY_TIMEOUT;
            message = "Request timed out";
        } else {
            status = HttpStatus.INTERNAL_SERVER_ERROR;
            message = "An unexpected error occurred";
        }

        exchange.getResponse().setStatusCode(status);
        exchange.getResponse().getHeaders()
            .setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> errorBody = Map.of(
            "success", false,
            "message", message,
            "status", status.value(),
            "path", exchange.getRequest().getURI().getPath(),
            "timestamp", Instant.now().toString()
        );

        byte[] bytes = objectMapper.writeValueAsBytes(errorBody);
        DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(bytes);
        return exchange.getResponse().writeWith(Mono.just(buffer));
    }
}
```

## Request Flow Diagram

```
Client Request
      │
      ▼
┌─────────────────────────┐
│   RequestLoggingFilter   │  → Logs method, path, correlation ID
├─────────────────────────┤
│   RateLimitFilter        │  → Checks Redis counter (429 if exceeded)
├─────────────────────────┤
│   JwtValidationFilter    │  → Validates Bearer token (401 if invalid)
│   OR                     │
│   ApiKeyValidationFilter │  → Validates X-Api-Key header
├─────────────────────────┤
│   Route Matching         │  → Matches path to downstream service
├─────────────────────────┤
│   Load Balancing         │  → Selects service instance
├─────────────────────────┤
│   Proxy to Service       │  → Forwards request with added headers
└─────────────────────────┘
      │
      ▼
Downstream Service Response
      │
      ▼
┌─────────────────────────┐
│  Response Logging        │  → Logs status code, latency
└─────────────────────────┘
      │
      ▼
Client Response
```

## Security Configuration

```java
@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    @Bean
    public SecurityWebFilterChain securityFilterChain(
            ServerHttpSecurity http) {
        return http
            .csrf(ServerHttpSecurity.CsrfSpec::disable)
            .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
            .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
            .authorizeExchange(exchange -> exchange
                .pathMatchers("/actuator/health").permitAll()
                .pathMatchers("/api/v1/auth/**").permitAll()
                .pathMatchers("/api/v1/payments/checkout/**").permitAll()
                .anyExchange().permitAll()  // Custom filters handle auth
            )
            .build();
    }
}
```

## Testing the Gateway

```bash
# Health check
curl http://localhost:8080/actuator/health

# Test rate limiting
for i in {1..150}; do
  curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8080/api/v1/orders
done
# Should see 429 after 100 requests

# Test JWT validation
curl -H "Authorization: Bearer invalid_token" \
  http://localhost:8080/api/v1/merchants
# Returns 401

# Test API key validation
curl -H "X-Api-Key: pk_live_abc123" \
  http://localhost:8080/api/v1/orders
# Routes to payment service with X-Merchant-Id header
```
