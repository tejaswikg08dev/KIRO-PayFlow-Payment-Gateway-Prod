package com.payflow.bank.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration properties for the bank simulator.
 * Controls success rate, latency simulation, and TCP port.
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "simulator")
public class SimulatorConfig {

    /**
     * TCP port for the ISO 8583 server (default: 9090).
     */
    private int tcpPort = 9090;

    /**
     * Percentage of transactions that succeed when no specific rule matches (default: 85%).
     */
    private int successRatePercent = 85;

    /**
     * Minimum simulated latency in milliseconds (default: 50ms).
     */
    private int minLatencyMs = 50;

    /**
     * Maximum simulated latency in milliseconds (default: 500ms).
     */
    private int maxLatencyMs = 500;

    /**
     * Whether to log full ISO 8583 messages (default: false for security).
     */
    private boolean logFullMessages = false;
}
