package com.payflow.gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;

/**
 * Aggregated Swagger/OpenAPI configuration.
 * Provides a single Swagger UI at the gateway for all services.
 */
@Configuration
public class SwaggerConfig {

    @Bean
    public OpenAPI gatewayOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("PayFlow Payment Gateway API")
                        .version("1.0.0")
                        .description("Unified API documentation for all PayFlow microservices"));
    }
}
