package com.payflow.routing.fraud;

import java.util.List;

/**
 * Result of fraud analysis on a transaction.
 *
 * @param score   Fraud risk score (0-100, where 100 is highest risk)
 * @param action  Recommended action based on the score
 * @param reasons List of reasons contributing to the score
 */
public record FraudResult(
        int score,
        FraudAction action,
        List<String> reasons
) {

    /**
     * Fraud decision actions.
     */
    public enum FraudAction {
        /** Score < 30: Transaction is safe to process */
        APPROVE,
        /** Score 30-70: Transaction needs manual review */
        REVIEW,
        /** Score > 70: Transaction should be declined */
        DECLINE
    }

    /**
     * Determines the action based on the fraud score.
     *
     * @param score Fraud risk score (0-100)
     * @return Appropriate FraudAction
     */
    public static FraudAction actionFromScore(int score) {
        if (score < 30) return FraudAction.APPROVE;
        if (score <= 70) return FraudAction.REVIEW;
        return FraudAction.DECLINE;
    }

    /**
     * Factory method to create a FraudResult with auto-determined action.
     */
    public static FraudResult of(int score, List<String> reasons) {
        return new FraudResult(score, actionFromScore(score), reasons);
    }

    /**
     * Returns true if the transaction should be blocked.
     */
    public boolean isDeclined() {
        return action == FraudAction.DECLINE;
    }

    /**
     * Returns true if the transaction requires review.
     */
    public boolean needsReview() {
        return action == FraudAction.REVIEW;
    }
}
