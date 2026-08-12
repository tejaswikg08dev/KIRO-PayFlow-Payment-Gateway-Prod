package com.payflow.payment.feign;

import com.payflow.payment.config.FeignConfig;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.Map;

/**
 * Feign client for the merchant-service.
 * Used to validate merchant existence and retrieve merchant configuration.
 */
@FeignClient(
        name = "merchant-service",
        configuration = FeignConfig.class,
        path = "/internal/merchants"
)
public interface MerchantServiceClient {

    /**
     * Retrieves merchant details by ID.
     *
     * @param merchantId the merchant identifier
     * @return merchant details map
     */
    @GetMapping("/{merchantId}")
    Map<String, Object> getMerchant(@PathVariable("merchantId") String merchantId);

    /**
     * Validates that a merchant exists and is active.
     *
     * @param merchantId the merchant identifier
     * @return merchant validation status
     */
    @GetMapping("/{merchantId}/validate")
    Map<String, Object> validateMerchant(@PathVariable("merchantId") String merchantId);
}
