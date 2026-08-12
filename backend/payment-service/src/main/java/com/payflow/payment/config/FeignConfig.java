package com.payflow.payment.config;

import feign.Logger;
import feign.Request;
import feign.Retryer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/**
 * Feign client configuration for inter-service communication.
 * Configures timeouts, retries, and logging level.
 */
@Configuration
public class FeignConfig {

    @Bean
    public Logger.Level feignLogLevel() {
        return Logger.Level.BASIC;
    }

    @Bean
    public Request.Options requestOptions() {
        return new Request.Options(
                5, TimeUnit.SECONDS,   // connect timeout
                10, TimeUnit.SECONDS,  // read timeout
                true                    // follow redirects
        );
    }

    @Bean
    public Retryer feignRetryer() {
        // Retry up to 3 times with 100ms initial interval and 1s max interval
        return new Retryer.Default(100, 1000, 3);
    }
}
