package com.payflow.settlement.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("FeeCalculationService Unit Tests")
class FeeCalculationServiceTest {

    private FeeCalculationService feeCalculationService;

    @BeforeEach
    void setUp() {
        feeCalculationService = new FeeCalculationService();
    }

    @Test
    @DisplayName("calculateMdr - gross=100000, refunds=5000, mdrPercent=2.0 should give correct MDR")
    void calculateMdr_StandardScenario() {
        BigDecimal grossAmount = new BigDecimal("100000");
        BigDecimal refundAmount = new BigDecimal("5000");
        BigDecimal eligibleAmount = grossAmount.subtract(refundAmount); // 95000

        BigDecimal mdr = feeCalculationService.calculateMdr(eligibleAmount, 2.0);

        // MDR = 95000 * 2% = 1900
        assertThat(mdr).isEqualByComparingTo(new BigDecimal("1900.0000"));
    }

    @Test
    @DisplayName("calculateGst - 18% GST on MDR amount")
    void calculateGst_OnMdrAmount() {
        BigDecimal mdrAmount = new BigDecimal("1900");

        BigDecimal gst = feeCalculationService.calculateGst(mdrAmount);

        // GST = 1900 * 18% = 342
        assertThat(gst).isEqualByComparingTo(new BigDecimal("342.0000"));
    }

    @Test
    @DisplayName("calculateNet - should compute net = gross - refunds - mdr - gst")
    void calculateNet_FullCalculation() {
        BigDecimal grossAmount = new BigDecimal("100000");
        BigDecimal refundAmount = new BigDecimal("5000");
        BigDecimal mdrAmount = new BigDecimal("1900");
        BigDecimal gstAmount = new BigDecimal("342");

        BigDecimal net = feeCalculationService.calculateNet(grossAmount, refundAmount, mdrAmount, gstAmount);

        // Net = 100000 - 5000 - 1900 - 342 = 92758
        assertThat(net).isEqualByComparingTo(new BigDecimal("92758.0000"));
    }

    @Test
    @DisplayName("full fee calculation pipeline - verify MDR, GST, and net amounts")
    void fullPipeline_VerifyAllAmounts() {
        BigDecimal grossAmount = new BigDecimal("100000");
        BigDecimal refundAmount = new BigDecimal("5000");
        double mdrPercent = 2.0;

        // Step 1: Calculate eligible amount
        BigDecimal eligibleAmount = grossAmount.subtract(refundAmount);
        assertThat(eligibleAmount).isEqualByComparingTo(new BigDecimal("95000"));

        // Step 2: Calculate MDR
        BigDecimal mdr = feeCalculationService.calculateMdr(eligibleAmount, mdrPercent);
        assertThat(mdr).isEqualByComparingTo(new BigDecimal("1900.0000"));

        // Step 3: Calculate GST on MDR
        BigDecimal gst = feeCalculationService.calculateGst(mdr);
        assertThat(gst).isEqualByComparingTo(new BigDecimal("342.0000"));

        // Step 4: Calculate net payout
        BigDecimal net = feeCalculationService.calculateNet(grossAmount, refundAmount, mdr, gst);
        assertThat(net).isEqualByComparingTo(new BigDecimal("92758.0000"));
    }

    @Test
    @DisplayName("calculateMdr - zero eligible amount should return zero MDR")
    void calculateMdr_ZeroAmount_ReturnsZero() {
        BigDecimal mdr = feeCalculationService.calculateMdr(BigDecimal.ZERO, 2.0);

        assertThat(mdr).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("calculateMdr - different MDR rates produce proportional fees")
    void calculateMdr_DifferentRates() {
        BigDecimal eligibleAmount = new BigDecimal("50000");

        BigDecimal mdr1 = feeCalculationService.calculateMdr(eligibleAmount, 1.5);
        BigDecimal mdr2 = feeCalculationService.calculateMdr(eligibleAmount, 3.0);

        // 1.5% of 50000 = 750
        assertThat(mdr1).isEqualByComparingTo(new BigDecimal("750.0000"));
        // 3.0% of 50000 = 1500
        assertThat(mdr2).isEqualByComparingTo(new BigDecimal("1500.0000"));
        // Double the rate = double the MDR
        assertThat(mdr2).isEqualByComparingTo(mdr1.multiply(new BigDecimal("2")));
    }
}
