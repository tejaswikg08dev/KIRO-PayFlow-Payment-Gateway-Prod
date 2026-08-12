package com.payflow.settlement.feign;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;
import java.util.Map;

/**
 * Feign client to communicate with the Payment Service.
 * Fetches captured payments for a given settlement date.
 */
@FeignClient(name = "payment-service", path = "/v1/payments")
public interface PaymentServiceClient {

    /**
     * Get all captured payments for a specific date, grouped by merchant.
     *
     * @param captureDate the date for which to fetch captured payments (ISO format: yyyy-MM-dd)
     * @return list of payment aggregation records per merchant
     */
    @GetMapping("/captured")
    List<Map<String, Object>> getCapturedPayments(@RequestParam("captureDate") String captureDate);
}
