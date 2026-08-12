package com.payflow.merchant.service;

import com.payflow.common.exception.ResourceNotFoundException;
import com.payflow.merchant.dto.WebhookConfigRequest;
import com.payflow.merchant.model.WebhookConfig;
import com.payflow.merchant.repository.MerchantRepository;
import com.payflow.merchant.repository.WebhookConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class WebhookConfigService {

    private final WebhookConfigRepository webhookConfigRepository;
    private final MerchantRepository merchantRepository;
    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * Create a webhook configuration for a merchant.
     */
    @Transactional
    public WebhookConfig createWebhookConfig(UUID merchantId, WebhookConfigRequest request) {
        if (!merchantRepository.existsById(merchantId)) {
            throw new ResourceNotFoundException("Merchant", merchantId.toString());
        }

        // Generate a webhook signing secret
        byte[] secretBytes = new byte[32];
        secureRandom.nextBytes(secretBytes);
        String secret = "whsec_" + Base64.getUrlEncoder().withoutPadding().encodeToString(secretBytes);

        WebhookConfig config = WebhookConfig.builder()
                .url(request.getUrl())
                .secret(secret)
                .events(request.getEvents())
                .merchantId(merchantId)
                .active(true)
                .build();

        WebhookConfig saved = webhookConfigRepository.save(config);
        log.info("Webhook config created for merchant: {}, url: {}", merchantId, request.getUrl());
        return saved;
    }

    /**
     * Get all webhook configs for a merchant.
     */
    @Transactional(readOnly = true)
    public List<WebhookConfig> getWebhookConfigs(UUID merchantId) {
        return webhookConfigRepository.findByMerchantId(merchantId);
    }

    /**
     * Get active webhook configs for a merchant.
     */
    @Transactional(readOnly = true)
    public List<WebhookConfig> getActiveWebhookConfigs(UUID merchantId) {
        return webhookConfigRepository.findByMerchantIdAndActiveTrue(merchantId);
    }

    /**
     * Update a webhook configuration.
     */
    @Transactional
    public WebhookConfig updateWebhookConfig(UUID configId, WebhookConfigRequest request) {
        WebhookConfig config = webhookConfigRepository.findById(configId)
                .orElseThrow(() -> new ResourceNotFoundException("WebhookConfig", configId.toString()));

        config.setUrl(request.getUrl());
        config.setEvents(request.getEvents());

        WebhookConfig updated = webhookConfigRepository.save(config);
        log.info("Webhook config updated: id={}", configId);
        return updated;
    }

    /**
     * Deactivate a webhook configuration.
     */
    @Transactional
    public void deactivateWebhookConfig(UUID configId) {
        WebhookConfig config = webhookConfigRepository.findById(configId)
                .orElseThrow(() -> new ResourceNotFoundException("WebhookConfig", configId.toString()));
        config.setActive(false);
        webhookConfigRepository.save(config);
        log.info("Webhook config deactivated: id={}", configId);
    }
}
