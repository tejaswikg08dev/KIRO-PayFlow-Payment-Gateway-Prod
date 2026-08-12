package com.payflow.webhook.feign;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.Map;

/**
 * Feign client to communicate with the Merchant Service.
 * Fetches webhook configuration (URL, secret) for a merchant.
 */
@FeignClient(name = "merchant-service", path = "/v1/merchants")
public interface MerchantServiceClient {

    /**
     * Get webhook configuration for a merchant.
     *
     * @param merchantId the merchant ID
     * @return map containing webhookUrl and webhookSecret
     */
    @GetMapping("/{merchantId}/webhook-config")
    Map<String, Object> getWebhookConfig(@PathVariable("merchantId") String merchantId);
}
