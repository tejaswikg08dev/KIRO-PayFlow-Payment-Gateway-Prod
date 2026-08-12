package com.payflow.gateway.config;

import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Programmatic route definitions for the API Gateway.
 * Routes requests to appropriate microservices via Eureka service discovery.
 */
@Configuration
public class GatewayRoutesConfig {

    @Bean
    public RouteLocator customRouteLocator(RouteLocatorBuilder builder) {
        return builder.routes()
                .route("identity-service", r -> r
                        .path("/v1/auth/**")
                        .uri("lb://identity-service"))
                .route("merchant-service", r -> r
                        .path("/v1/merchants/**")
                        .uri("lb://merchant-service"))
                .route("payment-orders", r -> r
                        .path("/v1/orders/**")
                        .uri("lb://payment-service"))
                .route("payment-payments", r -> r
                        .path("/v1/payments/**")
                        .uri("lb://payment-service"))
                .route("payment-refunds", r -> r
                        .path("/v1/refunds/**")
                        .uri("lb://payment-service"))
                .route("settlement-service", r -> r
                        .path("/v1/settlements/**")
                        .uri("lb://settlement-service"))
                .build();
    }
}
