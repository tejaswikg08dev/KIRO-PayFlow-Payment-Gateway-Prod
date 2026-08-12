package com.payflow.routing.routing;

/**
 * Represents a bank route with performance metrics for smart routing decisions.
 */
public class BankRoute {

    private String bankId;
    private String bankName;
    private double successRate;
    private double avgLatencyMs;
    private double costPerTxn;
    private boolean active;

    public BankRoute() {
    }

    public BankRoute(String bankId, String bankName, double successRate,
                     double avgLatencyMs, double costPerTxn, boolean active) {
        this.bankId = bankId;
        this.bankName = bankName;
        this.successRate = successRate;
        this.avgLatencyMs = avgLatencyMs;
        this.costPerTxn = costPerTxn;
        this.active = active;
    }

    public String getBankId() {
        return bankId;
    }

    public void setBankId(String bankId) {
        this.bankId = bankId;
    }

    public String getBankName() {
        return bankName;
    }

    public void setBankName(String bankName) {
        this.bankName = bankName;
    }

    public double getSuccessRate() {
        return successRate;
    }

    public void setSuccessRate(double successRate) {
        this.successRate = successRate;
    }

    public double getAvgLatencyMs() {
        return avgLatencyMs;
    }

    public void setAvgLatencyMs(double avgLatencyMs) {
        this.avgLatencyMs = avgLatencyMs;
    }

    public double getCostPerTxn() {
        return costPerTxn;
    }

    public void setCostPerTxn(double costPerTxn) {
        this.costPerTxn = costPerTxn;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    @Override
    public String toString() {
        return "BankRoute{" +
                "bankId='" + bankId + '\'' +
                ", bankName='" + bankName + '\'' +
                ", successRate=" + successRate +
                ", avgLatencyMs=" + avgLatencyMs +
                ", costPerTxn=" + costPerTxn +
                ", active=" + active +
                '}';
    }
}
