package com.payflow.routing.dto;

/**
 * Response DTO returned after routing a payment transaction.
 */
public class RoutingResponse {

    private boolean success;
    private String authorizationCode;
    private String responseCode;
    private String responseMessage;
    private String bankId;
    private long latencyMs;

    public RoutingResponse() {
    }

    public RoutingResponse(boolean success, String authorizationCode, String responseCode,
                           String responseMessage, String bankId, long latencyMs) {
        this.success = success;
        this.authorizationCode = authorizationCode;
        this.responseCode = responseCode;
        this.responseMessage = responseMessage;
        this.bankId = bankId;
        this.latencyMs = latencyMs;
    }

    // Static factory methods

    public static RoutingResponse approved(String authorizationCode, String bankId, long latencyMs) {
        return new RoutingResponse(true, authorizationCode, "00", "Approved", bankId, latencyMs);
    }

    public static RoutingResponse declined(String responseCode, String message, String bankId, long latencyMs) {
        return new RoutingResponse(false, null, responseCode, message, bankId, latencyMs);
    }

    public static RoutingResponse fraudDeclined(String reason) {
        return new RoutingResponse(false, null, "59", "Suspected Fraud: " + reason, null, 0);
    }

    public static RoutingResponse error(String message) {
        return new RoutingResponse(false, null, "96", "System Error: " + message, null, 0);
    }

    // Getters and setters

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public String getAuthorizationCode() {
        return authorizationCode;
    }

    public void setAuthorizationCode(String authorizationCode) {
        this.authorizationCode = authorizationCode;
    }

    public String getResponseCode() {
        return responseCode;
    }

    public void setResponseCode(String responseCode) {
        this.responseCode = responseCode;
    }

    public String getResponseMessage() {
        return responseMessage;
    }

    public void setResponseMessage(String responseMessage) {
        this.responseMessage = responseMessage;
    }

    public String getBankId() {
        return bankId;
    }

    public void setBankId(String bankId) {
        this.bankId = bankId;
    }

    public long getLatencyMs() {
        return latencyMs;
    }

    public void setLatencyMs(long latencyMs) {
        this.latencyMs = latencyMs;
    }
}
