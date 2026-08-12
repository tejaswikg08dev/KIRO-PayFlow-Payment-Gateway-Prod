package com.payflow.common.constant;

/**
 * Supported currency codes (ISO 4217).
 */
public enum CurrencyCode {
    INR("Indian Rupee", "₹"),
    USD("US Dollar", "$"),
    EUR("Euro", "€");

    private final String displayName;
    private final String symbol;

    CurrencyCode(String displayName, String symbol) {
        this.displayName = displayName;
        this.symbol = symbol;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getSymbol() {
        return symbol;
    }
}
