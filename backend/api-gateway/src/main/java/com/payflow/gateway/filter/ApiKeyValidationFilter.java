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
        return 0;
    }
}
