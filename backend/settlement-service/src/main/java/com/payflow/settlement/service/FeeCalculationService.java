package com.payflow.settlement.service;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Service to calculate settlement fees: MDR, GST, and net payout amount.
 */
@Service
public class FeeCalculationService {

    private static final BigDecimal GST_RATE = new BigDecimal("0.18"); // 18% GST on MDR
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    /**
     * Calculate Merchant Discount Rate (MDR).
     * MDR = mdrPercent% * eligibleAmount
     *
     * @param eligibleAmount Gross - Refunds
     * @param mdrPercent     MDR percentage (e.g., 2.0 means 2%)
     * @return MDR amount
     */
    public BigDecimal calculateMdr(BigDecimal eligibleAmount, double mdrPercent) {
        BigDecimal mdrRate = BigDecimal.valueOf(mdrPercent).divide(HUNDRED, 6, RoundingMode.HALF_UP);
        return eligibleAmount.multiply(mdrRate).setScale(4, RoundingMode.HALF_UP);
    }

    /**
     * Calculate GST on MDR (18% of MDR).
     *
     * @param mdrAmount the MDR amount
     * @return GST amount
     */
    public BigDecimal calculateGst(BigDecimal mdrAmount) {
        return mdrAmount.multiply(GST_RATE).setScale(4, RoundingMode.HALF_UP);
    }

    /**
     * Calculate net payout amount.
     * Net = Gross - Refunds - MDR - GST
     *
     * @param grossAmount  total captured amount
     * @param refundAmount total refund amount
     * @param mdrAmount    MDR fee
     * @param gstAmount    GST on MDR
     * @return net amount payable to merchant
     */
    public BigDecimal calculateNet(BigDecimal grossAmount, BigDecimal refundAmount,
                                   BigDecimal mdrAmount, BigDecimal gstAmount) {
        return grossAmount
                .subtract(refundAmount)
                .subtract(mdrAmount)
                .subtract(gstAmount)
                .setScale(4, RoundingMode.HALF_UP);
    }
}
