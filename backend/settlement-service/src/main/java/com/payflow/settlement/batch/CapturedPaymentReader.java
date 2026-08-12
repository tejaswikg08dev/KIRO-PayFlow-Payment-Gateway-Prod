package com.payflow.settlement.batch;

import com.payflow.settlement.feign.PaymentServiceClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.item.ItemReader;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * ItemReader that fetches CAPTURED payments for the settlement date via Feign client.
 * Reads all captured payments in one call, then iterates over them one by one.
 */
@Slf4j
@Component
public class CapturedPaymentReader implements ItemReader<Map<String, Object>> {

    private final PaymentServiceClient paymentServiceClient;
    private Iterator<Map<String, Object>> paymentIterator;
    private boolean initialized = false;

    public CapturedPaymentReader(PaymentServiceClient paymentServiceClient) {
        this.paymentServiceClient = paymentServiceClient;
    }

    @Override
    public Map<String, Object> read() {
        if (!initialized) {
            initialize();
        }

        if (paymentIterator != null && paymentIterator.hasNext()) {
            return paymentIterator.next();
        }
        return null; // signals end of data
    }

    private void initialize() {
        LocalDate settlementDate = LocalDate.now().minusDays(1);
        log.info("Fetching captured payments for settlement date: {}", settlementDate);

        try {
            List<Map<String, Object>> payments = paymentServiceClient.getCapturedPayments(
                    settlementDate.toString());
            paymentIterator = payments.iterator();
            log.info("Loaded {} captured payments for processing", payments.size());
        } catch (Exception e) {
            log.error("Failed to fetch captured payments: {}", e.getMessage(), e);
            paymentIterator = List.<Map<String, Object>>of().iterator();
        }

        initialized = true;
    }

    /**
     * Reset the reader state for a new batch run.
     */
    public void reset() {
        initialized = false;
        paymentIterator = null;
    }
}
