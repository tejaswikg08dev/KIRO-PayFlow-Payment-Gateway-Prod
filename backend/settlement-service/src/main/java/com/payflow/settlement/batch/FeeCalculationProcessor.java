package com.payflow.settlement.batch;

import com.payflow.settlement.model.SettlementRecord;
import com.payflow.settlement.service.FeeCalculationService;
import com.payflow.common.util.IdGenerator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;

/**
 * ItemProcessor that calculates settlement fees for each merchant's captured payments.
 * Formula:
 *   Gross = sum of captured amounts
 *   Refunds = sum of refund amounts
 *   MDR = mdrPercent * (Gross - Refunds)
 *   GST = 18% of MDR
 *   Net = Gross - Refunds - MDR - GST
 */
@Slf4j
@Component
public class FeeCalculationProcessor implements ItemProcessor<Map<String, Object>, SettlementRecord> {

    private final FeeCalculationService feeCalculationService;

    @Value("${settlement.mdr-percent:2.0}")
    private double mdrPercent;

    public FeeCalculationProcessor(FeeCalculationService feeCalculationService) {
        this.feeCalculationService = feeCalculationService;
    }

    @Override
    public SettlementRecord process(Map<String, Object> paymentData) {
        String merchantId = (String) paymentData.get("merchantId");
        BigDecimal grossAmount = toBigDecimal(paymentData.get("grossAmount"));
        BigDecimal refundAmount = toBigDecimal(paymentData.getOrDefault("refundAmount", BigDecimal.ZERO));
        int paymentCount = paymentData.containsKey("paymentCount")
                ? ((Number) paymentData.get("paymentCount")).intValue()
                : 1;

        BigDecimal eligible = grossAmount.subtract(refundAmount);
        BigDecimal mdrAmount = feeCalculationService.calculateMdr(eligible, mdrPercent);
        BigDecimal gstAmount = feeCalculationService.calculateGst(mdrAmount);
        BigDecimal netAmount = feeCalculationService.calculateNet(grossAmount, refundAmount, mdrAmount, gstAmount);

        log.debug("Processing merchant={}, gross={}, refunds={}, mdr={}, gst={}, net={}",
                merchantId, grossAmount, refundAmount, mdrAmount, gstAmount, netAmount);

        return SettlementRecord.builder()
                .id(IdGenerator.generate())
                .merchantId(merchantId)
                .grossAmount(grossAmount)
                .refundAmount(refundAmount)
                .mdrAmount(mdrAmount)
                .gstAmount(gstAmount)
                .netAmount(netAmount)
                .paymentCount(paymentCount)
                .build();
    }

    private BigDecimal toBigDecimal(Object value) {
        if (value == null) return BigDecimal.ZERO;
        if (value instanceof BigDecimal) return (BigDecimal) value;
        if (value instanceof Number) return BigDecimal.valueOf(((Number) value).doubleValue());
        return new BigDecimal(value.toString());
    }
}
