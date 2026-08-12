package com.payflow.payment.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Security configuration for the payment service.
 * In a microservice architecture, authentication is handled by the API Gateway.
 * Internal service-to-service calls are trusted within the cluster.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // Actuator health/info endpoints
                        .requestMatchers("/actuator/**").permitAll()
                        // Swagger/OpenAPI
                        .requestMatchers("/swagger-ui/**", "/v3/api-docs/**").permitAll()
                        // All payment endpoints — authentication handled by gateway
                        .requestMatchers("/v1/**").permitAll()
                        // Internal endpoints (service-to-service)
                        .requestMatchers("/internal/**").permitAll()
                        .anyRequest().authenticated()
                );

        return http.build();
    }
}
