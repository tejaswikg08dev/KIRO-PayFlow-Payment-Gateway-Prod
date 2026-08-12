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
        String requestId = exchange.getRequest().getHeaders().getFirst(REQUEST_ID_HEADER);

        if (requestId == null) {
            requestId = UUID.randomUUID().toString();
        }

        ServerHttpRequest modifiedRequest = exchange.getRequest().mutate()
                .header(REQUEST_ID_HEADER, requestId)
                .build();

        log.info("Request: {} {} | RequestId: {}",
                exchange.getRequest().getMethod(),
                exchange.getRequest().getURI().getPath(),
                requestId);

        String finalRequestId = requestId;
        return chain.filter(exchange.mutate().request(modifiedRequest).build())
                .then(Mono.fromRunnable(() ->
                        log.info("Response: {} | RequestId: {}",
                                exchange.getResponse().getStatusCode(),
                                finalRequestId)));
    }

    @Override
    public int getOrder() {
        return -2; // Execute first (before JWT filter)
    }
}
