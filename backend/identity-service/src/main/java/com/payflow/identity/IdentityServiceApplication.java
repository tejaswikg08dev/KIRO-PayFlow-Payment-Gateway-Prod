package com.payflow.identity;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Identity Service — Handles user authentication and authorization.
 * Endpoints: /v1/auth/register, /v1/auth/login, /v1/auth/refresh, /v1/auth/profile
 * Port: 8081
 */
@SpringBootApplication
public class IdentityServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(IdentityServiceApplication.class, args);
    }
}
