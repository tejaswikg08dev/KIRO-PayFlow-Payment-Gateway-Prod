package com.payflow.routing.routing;

import java.util.List;

/**
 * Repository interface for storing and retrieving routing metrics.
 * Can be backed by DynamoDB or in-memory storage.
 */
public interface RoutingMetricsRepository {

    /**
     * Returns all configured bank routes with their metrics.
     */
    List<BankRoute> getAllBankRoutes();

    /**
     * Returns all active bank routes.
     */
    List<BankRoute> getActiveBankRoutes();

    /**
     * Gets a specific bank route by ID.
     */
    BankRoute getBankRoute(String bankId);

    /**
     * Updates the success rate for a bank after a transaction.
     *
     * @param bankId  Bank identifier
     * @param success Whether the transaction was successful
     * @param latencyMs Response latency in milliseconds
     */
    void recordTransactionResult(String bankId, boolean success, long latencyMs);

    /**
     * Updates the bank route metrics (success rate, latency).
     */
    void updateBankRoute(BankRoute bankRoute);

    /**
     * Returns the total transaction count for a bank.
     */
    long getTransactionCount(String bankId);
}
