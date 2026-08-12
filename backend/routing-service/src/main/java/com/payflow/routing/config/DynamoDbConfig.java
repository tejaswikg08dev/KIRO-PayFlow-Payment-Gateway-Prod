package com.payflow.routing.config;

import com.payflow.routing.routing.BankRoute;
import com.payflow.routing.routing.RoutingMetricsRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * DynamoDB configuration with configurable endpoint for LocalStack support.
 */
@Configuration
public class DynamoDbConfig {

    private static final Logger log = LoggerFactory.getLogger(DynamoDbConfig.class);

    @Value("${aws.dynamodb.endpoint:http://localhost:4566}")
    private String dynamoDbEndpoint;

    @Value("${aws.region:us-east-1}")
    private String awsRegion;

    @Value("${aws.access-key:localstack}")
    private String accessKey;

    @Value("${aws.secret-key:localstack}")
    private String secretKey;

    /**
     * Creates DynamoDB client bean with configurable endpoint.
     * In development/test, this connects to LocalStack.
     */
    @Bean
    public DynamoDbClient dynamoDbClient() {
        log.info("Configuring DynamoDB client with endpoint: {}", dynamoDbEndpoint);

        return DynamoDbClient.builder()
                .endpointOverride(URI.create(dynamoDbEndpoint))
                .region(Region.of(awsRegion))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKey, secretKey)))
                .build();
    }

    /**
     * In-memory implementation of RoutingMetricsRepository for development.
     * Provides pre-configured bank routes for testing.
     */
    @Bean
    @Profile({"dev", "default"})
    public RoutingMetricsRepository inMemoryMetricsRepository() {
        return new InMemoryRoutingMetricsRepository();
    }

    /**
     * In-memory repository implementation with default bank routes.
     */
    static class InMemoryRoutingMetricsRepository implements RoutingMetricsRepository {

        private final Map<String, BankRoute> bankRoutes = new ConcurrentHashMap<>();
        private final Map<String, AtomicLong> successCounts = new ConcurrentHashMap<>();
        private final Map<String, AtomicLong> totalCounts = new ConcurrentHashMap<>();
        private final Map<String, AtomicLong> totalLatency = new ConcurrentHashMap<>();

        InMemoryRoutingMetricsRepository() {
            // Initialize with default bank routes
            addBank(new BankRoute("bank-alpha", "Alpha Bank", 0.95, 120, 0.25, true));
            addBank(new BankRoute("bank-beta", "Beta Bank", 0.88, 200, 0.15, true));
            addBank(new BankRoute("bank-gamma", "Gamma Bank", 0.92, 150, 0.20, true));
            addBank(new BankRoute("bank-delta", "Delta Bank", 0.85, 300, 0.10, true));
        }

        private void addBank(BankRoute route) {
            bankRoutes.put(route.getBankId(), route);
            successCounts.put(route.getBankId(), new AtomicLong(0));
            totalCounts.put(route.getBankId(), new AtomicLong(0));
            totalLatency.put(route.getBankId(), new AtomicLong(0));
        }

        @Override
        public List<BankRoute> getAllBankRoutes() {
            return new ArrayList<>(bankRoutes.values());
        }

        @Override
        public List<BankRoute> getActiveBankRoutes() {
            return bankRoutes.values().stream()
                    .filter(BankRoute::isActive)
                    .toList();
        }

        @Override
        public BankRoute getBankRoute(String bankId) {
            return bankRoutes.get(bankId);
        }

        @Override
        public void recordTransactionResult(String bankId, boolean success, long latencyMs) {
            totalCounts.computeIfAbsent(bankId, k -> new AtomicLong(0)).incrementAndGet();
            totalLatency.computeIfAbsent(bankId, k -> new AtomicLong(0)).addAndGet(latencyMs);

            if (success) {
                successCounts.computeIfAbsent(bankId, k -> new AtomicLong(0)).incrementAndGet();
            }

            // Update running averages
            BankRoute route = bankRoutes.get(bankId);
            if (route != null) {
                long total = totalCounts.get(bankId).get();
                long successes = successCounts.get(bankId).get();
                long totalLat = totalLatency.get(bankId).get();

                route.setSuccessRate((double) successes / total);
                route.setAvgLatencyMs((double) totalLat / total);
            }
        }

        @Override
        public void updateBankRoute(BankRoute bankRoute) {
            bankRoutes.put(bankRoute.getBankId(), bankRoute);
        }

        @Override
        public long getTransactionCount(String bankId) {
            AtomicLong count = totalCounts.get(bankId);
            return count != null ? count.get() : 0;
        }
    }
}
