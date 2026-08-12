package com.payflow.gateway.exception;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.web.reactive.error.ErrorWebExceptionHandler;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Map;

/**
 * Global exception handler for the reactive gateway.
 * Formats all errors into consistent JSON structure.
 */
@Component
@Order(-1)
public class GatewayExceptionHandler implements ErrorWebExceptionHandler {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
        HttpStatus status = HttpStatus.INTERNAL_SERVER_ERROR;

        if (ex instanceof org.springframework.web.server.ResponseStatusException rse) {
            status = HttpStatus.valueOf(rse.getStatusCode().value());
        }

        exchange.getResponse().setStatusCode(status);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> errorBody = Map.of(
                "success", false,
                "error", Map.of(
                        "code", status.name(),
                        "message", ex.getMessage() != null ? ex.getMessage() : "An unexpected error occurred"
                ),
                "timestamp", Instant.now().toString(),
                "path", exchange.getRequest().getURI().getPath()
        );

        try {
            byte[] bytes = objectMapper.writeValueAsBytes(errorBody);
            DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(bytes);
            return exchange.getResponse().writeWith(Mono.just(buffer));
        } catch (JsonProcessingException e) {
            return exchange.getResponse().setComplete();
        }
    }
}
