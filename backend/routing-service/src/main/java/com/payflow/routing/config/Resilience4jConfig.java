package com.payflow.routing.config;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Resilience4j Circuit Breaker configuration for bank communication.
 * <p>
 * The circuit breaker protects against cascading failures when the bank
 * simulator is unavailable or responding slowly.
 */
@Configuration
public class Resilience4jConfig {

    private static final Logger log = LoggerFactory.getLogger(Resilience4jConfig.class);

    @Value("${resilience4j.circuitbreaker.failure-rate-threshold:50}")
    private float failureRateThreshold;

    @Value("${resilience4j.circuitbreaker.slow-call-rate-threshold:80}")
    private float slowCallRateThreshold;

    @Value("${resilience4j.circuitbreaker.slow-call-duration-threshold-seconds:5}")
    private int slowCallDurationThresholdSeconds;

    @Value("${resilience4j.circuitbreaker.sliding-window-size:10}")
    private int slidingWindowSize;

    @Value("${resilience4j.circuitbreaker.minimum-number-of-calls:5}")
    private int minimumNumberOfCalls;

    @Value("${resilience4j.circuitbreaker.wait-duration-in-open-state-seconds:30}")
    private int waitDurationInOpenStateSeconds;

    @Value("${resilience4j.circuitbreaker.permitted-calls-in-half-open:3}")
    private int permittedCallsInHalfOpen;

    @Bean
    public CircuitBreakerRegistry circuitBreakerRegistry() {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .failureRateThreshold(failureRateThreshold)
                .slowCallRateThreshold(slowCallRateThreshold)
                .slowCallDurationThreshold(Duration.ofSeconds(slowCallDurationThresholdSeconds))
                .slidingWindowSize(slidingWindowSize)
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .minimumNumberOfCalls(minimumNumberOfCalls)
                .waitDurationInOpenState(Duration.ofSeconds(waitDurationInOpenStateSeconds))
                .permittedNumberOfCallsInHalfOpenState(permittedCallsInHalfOpen)
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                .build();

        CircuitBreakerRegistry registry = CircuitBreakerRegistry.of(config);

        // Register event listeners for monitoring
        CircuitBreaker breaker = registry.circuitBreaker("bankCommunication");
        breaker.getEventPublisher()
                .onStateTransition(event ->
                        log.warn("Circuit Breaker '{}' state transition: {} -> {}",
                                event.getCircuitBreakerName(),
                                event.getStateTransition().getFromState(),
                                event.getStateTransition().getToState()))
                .onError(event ->
                        log.debug("Circuit Breaker '{}' error: {}",
                                event.getCircuitBreakerName(),
                                event.getThrowable().getMessage()));

        return registry;
    }
}
