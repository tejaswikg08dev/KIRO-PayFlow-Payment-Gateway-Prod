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
