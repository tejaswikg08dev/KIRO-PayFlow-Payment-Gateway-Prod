package com.payflow.bank.logic;

import org.springframework.stereotype.Component;

/**
 * Card BIN-based approval rules for the bank simulator.
 *
 * Rules:
 *   - 4111 prefix = always approve (test Visa card)
 *   - 4000 prefix = always decline
 *   - 5500 prefix = always approve (test Mastercard)
 *   - 5400 prefix = always decline (Mastercard decline)
 *   - Others = no rule (fall through to random)
 */
@Component
public class CardBinRules {

    /**
     * Evaluate the PAN against BIN rules.
     *
     * @param pan the Primary Account Number
     * @return response code if a BIN rule matches, null otherwise
     */
    public String evaluate(String pan) {
        if (pan == null || pan.length() < 4) {
            return null;
        }

        String bin = pan.substring(0, 4);

        return switch (bin) {
            case "4111" -> "00"; // Always approve (Visa test card 4111111111111111)
            case "5500" -> "00"; // Always approve (Mastercard test card)
            case "4000" -> "05"; // Always decline (Do Not Honor)
            case "5400" -> "14"; // Always decline (Invalid Card Number)
            case "4917" -> "51"; // Insufficient Funds
            default -> null;     // No BIN rule — fall through
        };
    }
}
