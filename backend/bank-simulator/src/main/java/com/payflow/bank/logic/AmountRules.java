package com.payflow.bank.logic;

import org.springframework.stereotype.Component;

/**
 * Amount-based approval rules for the bank simulator.
 *
 * Rules:
 *   - Amount > 100000 (1,00,000) = decline with "61" (Exceeds Amount Limit)
 *   - Amount ending in 13 = decline with "05" (Do Not Honor — unlucky number)
 *   - Amount = 0 = decline with "12" (Invalid Transaction)
 *   - Others = no rule (fall through to random)
 */
@Component
public class AmountRules {

    private static final long MAX_AMOUNT = 100000_00L; // 100,000 in minor units (paise/cents)

    /**
     * Evaluate the transaction amount against rules.
     *
     * @param amountStr the amount as string (in minor units, e.g., "100000" = 1000.00)
     * @return response code if an amount rule matches, null otherwise
     */
    public String evaluate(String amountStr) {
        if (amountStr == null || amountStr.isBlank()) {
            return null;
        }

        try {
            long amount = Long.parseLong(amountStr.trim());

            // Zero amount
            if (amount <= 0) {
                return "12"; // Invalid Transaction
            }

            // Amount exceeds limit
            if (amount > MAX_AMOUNT) {
                return "61"; // Exceeds Amount Limit
            }

            // Amount ends in 13 (unlucky number test)
            if (amount % 100 == 13) {
                return "05"; // Do Not Honor
            }

            return null; // No amount rule matched
        } catch (NumberFormatException e) {
            return "30"; // Format Error
        }
    }
}
