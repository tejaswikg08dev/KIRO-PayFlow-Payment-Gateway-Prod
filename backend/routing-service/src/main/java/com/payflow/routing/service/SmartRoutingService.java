package com.payflow.routing.service;

import com.payflow.routing.routing.BankRoute;
import com.payflow.routing.routing.RoutingDecision;
import com.payflow.routing.routing.RoutingMetricsRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Smart routing service implementing the multi-armed bandit epsilon-greedy algorithm.
 * <p>
 * Strategy:
 * - Explore (epsilon probability, default 10%): Select a random bank to gather performance data.
 * - Exploit (1-epsilon probability, default 90%): Select the bank with the highest success rate.
 * <p>
 * This approach balances discovering better routes with exploiting known good ones.
 */
@Service
public class SmartRoutingService {

    private static final Logger log = LoggerFactory.getLogger(SmartRoutingService.class);

    private final RoutingMetricsRepository metricsRepository;

    @Value("${routing.epsilon:0.10}")
    private double epsilon;

    public SmartRoutingService(RoutingMetricsRepository metricsRepository) {
        this.metricsRepository = metricsRepository;
    }

    /**
     * Selects the best bank route using epsilon-greedy strategy.
     *
     * @return RoutingDecision with selected bank and exploration flag
     * @throws IllegalStateException if no active banks are available
     */
    public RoutingDecision selectRoute() {
        List<BankRoute> activeBanks = metricsRepository.getActiveBankRoutes();

        if (activeBanks.isEmpty()) {
            throw new IllegalStateException("No active bank routes available for routing");
        }

        // Single bank? No choice needed.
        if (activeBanks.size() == 1) {
            return RoutingDecision.exploit(activeBanks.get(0));
        }

        double random = ThreadLocalRandom.current().nextDouble();

        if (random < epsilon) {
            // Explore: pick a random bank
            return explore(activeBanks);
        } else {
            // Exploit: pick the best performing bank
            return exploit(activeBanks);
        }
    }

    /**
     * Exploration: Selects a random bank for data gathering.
     */
    private RoutingDecision explore(List<BankRoute> banks) {
        int randomIndex = ThreadLocalRandom.current().nextInt(banks.size());
        BankRoute selectedBank = banks.get(randomIndex);

        log.info("EXPLORE: Randomly selected bank '{}' (epsilon={})",
                selectedBank.getBankName(), epsilon);

        return RoutingDecision.explore(selectedBank);
    }

    /**
     * Exploitation: Selects the bank with the highest success rate.
     * If success rates are equal, prefer lower latency. If latency is also equal, prefer lower cost.
     */
    private RoutingDecision exploit(List<BankRoute> banks) {
        BankRoute bestBank = banks.stream()
                .max(Comparator.comparingDouble(BankRoute::getSuccessRate)
                        .thenComparing(Comparator.comparingDouble(BankRoute::getAvgLatencyMs).reversed())
                        .thenComparing(Comparator.comparingDouble(BankRoute::getCostPerTxn).reversed()))
                .orElseThrow(() -> new IllegalStateException("No banks to exploit"));

        log.info("EXPLOIT: Selected best bank '{}' (successRate={:.2f}%, avgLatency={:.0f}ms)",
                bestBank.getBankName(), bestBank.getSuccessRate() * 100, bestBank.getAvgLatencyMs());

        return RoutingDecision.exploit(bestBank);
    }

    /**
     * Records the result of a transaction for future routing decisions.
     *
     * @param bankId    Bank that processed the transaction
     * @param success   Whether the transaction was approved
     * @param latencyMs Response time in milliseconds
     */
    public void recordResult(String bankId, boolean success, long latencyMs) {
        metricsRepository.recordTransactionResult(bankId, success, latencyMs);
        log.debug("Recorded result for bank={}: success={}, latency={}ms", bankId, success, latencyMs);
    }

    /**
     * Returns the current epsilon value.
     */
    public double getEpsilon() {
        return epsilon;
    }

    /**
     * Sets the epsilon value (for testing or dynamic adjustment).
     */
    public void setEpsilon(double epsilon) {
        this.epsilon = epsilon;
    }
}
