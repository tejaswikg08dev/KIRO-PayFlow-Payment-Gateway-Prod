package com.payflow.bank.logic;

import com.payflow.bank.config.SimulatorConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Generates bank approval/decline responses based on configurable rules.
 * Checks card BIN rules and amount rules before falling back to random success rate.
 *
 * Response codes:
 *   "00" = Approved
 *   "05" = Do Not Honor (general decline)
 *   "14" = Invalid Card Number
 *   "51" = Insufficient Funds
 *   "61" = Exceeds Amount Limit
 */
@Slf4j
@Component
public class ResponseGenerator {

    private final CardBinRules cardBinRules;
    private final AmountRules amountRules;
    private final SimulatorConfig config;

    public ResponseGenerator(CardBinRules cardBinRules, AmountRules amountRules, SimulatorConfig config) {
        this.cardBinRules = cardBinRules;
        this.amountRules = amountRules;
        this.config = config;
    }

    /**
     * Generate a response code based on card number and amount.
     *
     * @param pan    the Primary Account Number (card number)
     * @param amount the transaction amount as string
     * @return ISO 8583 response code
     */
    public String generateResponse(String pan, String amount) {
        // Check card BIN rules first
        String binResponse = cardBinRules.evaluate(pan);
        if (binResponse != null) {
            log.debug("BIN rule matched: PAN prefix={}, response={}", pan.substring(0, 4), binResponse);
            return binResponse;
        }

        // Check amount rules
        String amountResponse = amountRules.evaluate(amount);
        if (amountResponse != null) {
            log.debug("Amount rule matched: amount={}, response={}", amount, amountResponse);
            return amountResponse;
        }

        // Fall back to configurable random success rate
        int random = ThreadLocalRandom.current().nextInt(100);
        if (random < config.getSuccessRatePercent()) {
            return "00"; // Approved
        }

        // Random decline reason
        String[] declineCodes = {"05", "51", "14"};
        return declineCodes[ThreadLocalRandom.current().nextInt(declineCodes.length)];
    }
}
