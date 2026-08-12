package com.payflow.webhook.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.payflow.common.event.PaymentEvent;
import com.payflow.common.event.SettlementEvent;
import com.payflow.webhook.feign.MerchantServiceClient;
import com.payflow.webhook.model.WebhookDeliveryRecord;
import com.payflow.webhook.repository.DynamoDbDeliveryRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Service that delivers webhook events to merchant endpoints via HTTP POST.
 * Signs payloads with HMAC-SHA256 and includes retry logic.
 */
@Slf4j
@Service
public class WebhookDeliveryService {

    private final RestTemplate restTemplate;
    private final HmacSignatureService hmacSignatureService;
    private final RetryPolicyService retryPolicyService;
    private final DynamoDbDeliveryRepository deliveryRepository;
    private final MerchantServiceClient merchantServiceClient;
    private final ObjectMapper objectMapper;

    public WebhookDeliveryService(RestTemplate restTemplate,
                                  HmacSignatureService hmacSignatureService,
                                  RetryPolicyService retryPolicyService,
                                  DynamoDbDeliveryRepository deliveryRepository,
                                  MerchantServiceClient merchantServiceClient,
                                  ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
        this.hmacSignatureService = hmacSignatureService;
        this.retryPolicyService = retryPolicyService;
        this.deliveryRepository = deliveryRepository;
        this.merchantServiceClient = merchantServiceClient;
        this.objectMapper = objectMapper;
    }

    /**
     * Deliver a payment event as a webhook to the merchant's configured endpoint.
     */
    public void deliverPaymentWebhook(PaymentEvent event) {
        Map<String, Object> merchantConfig = merchantServiceClient.getWebhookConfig(event.getMerchantId());
        String webhookUrl = (String) merchantConfig.get("webhookUrl");
        String secret = (String) merchantConfig.get("webhookSecret");

        if (webhookUrl == null || webhookUrl.isBlank()) {
            log.warn("No webhook URL configured for merchant: {}", event.getMerchantId());
            return;
        }

        String payload = serializePayload(event);
        deliver(event.getEventId(), event.getMerchantId(), webhookUrl, secret, payload);
    }

    /**
     * Deliver a settlement event as a webhook to the merchant's configured endpoint.
     */
    public void deliverSettlementWebhook(SettlementEvent event) {
        if (event.getMerchantId() == null) {
            log.info("Settlement event has no merchantId (batch-level), skipping webhook delivery");
            return;
        }

        Map<String, Object> merchantConfig = merchantServiceClient.getWebhookConfig(event.getMerchantId());
        String webhookUrl = (String) merchantConfig.get("webhookUrl");
        String secret = (String) merchantConfig.get("webhookSecret");

        if (webhookUrl == null || webhookUrl.isBlank()) {
            log.warn("No webhook URL configured for merchant: {}", event.getMerchantId());
            return;
        }

        String payload = serializePayload(event);
        deliver(event.getEventId(), event.getMerchantId(), webhookUrl, secret, payload);
    }

    /**
     * Core delivery logic: POST payload to merchant URL with HMAC signature.
     */
    private void deliver(String eventId, String merchantId, String webhookUrl, String secret, String payload) {
        String signature = hmacSignatureService.generateSignature(payload, secret);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-PayFlow-Signature", signature);
        headers.set("X-PayFlow-Event-Id", eventId);

        HttpEntity<String> request = new HttpEntity<>(payload, headers);

        WebhookDeliveryRecord record = WebhookDeliveryRecord.builder()
                .eventId(eventId)
                .merchantId(merchantId)
                .webhookUrl(webhookUrl)
                .attempts(0)
                .completed(false)
                .lastAttemptAt(Instant.now())
                .build();

        boolean success = attemptDelivery(request, webhookUrl, record);

        if (!success) {
            // Schedule retries via retry policy
            Instant nextRetry = retryPolicyService.getNextRetryTime(record.getAttempts());
            if (nextRetry != null) {
                record.setNextRetryAt(nextRetry);
                log.info("Scheduling retry for event={}, attempt={}, nextRetry={}",
                        eventId, record.getAttempts(), nextRetry);
            } else {
                record.setCompleted(true); // Max retries exhausted
                log.warn("Max retries exhausted for event={}", eventId);
            }
        }

        deliveryRepository.save(record);
    }

    private boolean attemptDelivery(HttpEntity<String> request, String webhookUrl, WebhookDeliveryRecord record) {
        try {
            ResponseEntity<String> response = restTemplate.exchange(
                    webhookUrl, HttpMethod.POST, request, String.class);

            int statusCode = response.getStatusCode().value();
            record.setLastStatusCode(statusCode);
            record.setAttempts(record.getAttempts() + 1);
            record.setLastAttemptAt(Instant.now());

            if (response.getStatusCode().is2xxSuccessful()) {
                record.setCompleted(true);
                log.info("Webhook delivered successfully: url={}, status={}", webhookUrl, statusCode);
                return true;
            }

            log.warn("Webhook delivery failed: url={}, status={}", webhookUrl, statusCode);
            return false;
        } catch (Exception e) {
            record.setAttempts(record.getAttempts() + 1);
            record.setLastAttemptAt(Instant.now());
            record.setLastStatusCode(0);
            log.error("Webhook delivery error: url={}, error={}", webhookUrl, e.getMessage());
            return false;
        }
    }

    private String serializePayload(Object event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize webhook payload", e);
        }
    }
}
