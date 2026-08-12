package com.payflow.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * API Gateway — Single entry point for all PayFlow API requests.
 * Handles: routing, rate limiting, JWT validation, CORS, request logging.
 * Port: 8080
 */
@SpringBootApplication
public class ApiGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(ApiGatewayApplication.class, args);
    }
}
