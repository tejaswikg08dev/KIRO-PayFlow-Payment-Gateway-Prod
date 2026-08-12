package com.payflow.merchant.controller;

import com.payflow.common.dto.ApiResponse;
import com.payflow.merchant.dto.*;
import com.payflow.merchant.model.WebhookConfig;
import com.payflow.merchant.service.ApiKeyService;
import com.payflow.merchant.service.MerchantService;
import com.payflow.merchant.service.WebhookConfigService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/v1/merchants")
@RequiredArgsConstructor
public class MerchantController {

    private final MerchantService merchantService;
    private final ApiKeyService apiKeyService;
    private final WebhookConfigService webhookConfigService;

    // ─── Merchant CRUD ───────────────────────────────────────────────────────────

    @PostMapping
    public ResponseEntity<ApiResponse<MerchantResponse>> registerMerchant(
            @Valid @RequestBody MerchantRegisterRequest request) {
        MerchantResponse response = merchantService.registerMerchant(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(response));
    }

    @GetMapping("/{merchantId}")
    public ResponseEntity<ApiResponse<MerchantResponse>> getMerchant(
            @PathVariable UUID merchantId) {
        MerchantResponse response = merchantService.getMerchant(merchantId);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<MerchantResponse>>> getAllMerchants() {
        List<MerchantResponse> response = merchantService.getAllMerchants();
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @PutMapping("/{merchantId}")
    public ResponseEntity<ApiResponse<MerchantResponse>> updateMerchant(
            @PathVariable UUID merchantId,
            @Valid @RequestBody MerchantRegisterRequest request) {
        MerchantResponse response = merchantService.updateMerchant(merchantId, request);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @DeleteMapping("/{merchantId}")
    public ResponseEntity<ApiResponse<Void>> deactivateMerchant(
            @PathVariable UUID merchantId) {
        merchantService.deactivateMerchant(merchantId);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    // ─── API Key Management ──────────────────────────────────────────────────────

    @PostMapping("/{merchantId}/api-keys")
    public ResponseEntity<ApiResponse<ApiKeyResponse>> generateApiKey(
            @PathVariable UUID merchantId) {
        ApiKeyResponse response = apiKeyService.generateApiKey(merchantId);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(response));
    }

    @GetMapping("/{merchantId}/api-keys")
    public ResponseEntity<ApiResponse<List<ApiKeyResponse>>> getApiKeys(
            @PathVariable UUID merchantId) {
        List<ApiKeyResponse> response = apiKeyService.getApiKeys(merchantId);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @DeleteMapping("/{merchantId}/api-keys/{keyId}")
    public ResponseEntity<ApiResponse<Void>> revokeApiKey(
            @PathVariable UUID merchantId,
            @PathVariable UUID keyId) {
        apiKeyService.revokeApiKey(keyId);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    @PostMapping("/api-keys/validate")
    public ResponseEntity<ApiResponse<UUID>> validateApiKey(
            @RequestParam String key) {
        UUID merchantId = apiKeyService.validateApiKey(key);
        return ResponseEntity.ok(ApiResponse.success(merchantId));
    }

    // ─── Webhook Configuration ───────────────────────────────────────────────────

    @PostMapping("/{merchantId}/webhooks")
    public ResponseEntity<ApiResponse<WebhookConfig>> createWebhookConfig(
            @PathVariable UUID merchantId,
            @Valid @RequestBody WebhookConfigRequest request) {
        WebhookConfig config = webhookConfigService.createWebhookConfig(merchantId, request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(config));
    }

    @GetMapping("/{merchantId}/webhooks")
    public ResponseEntity<ApiResponse<List<WebhookConfig>>> getWebhookConfigs(
            @PathVariable UUID merchantId) {
        List<WebhookConfig> configs = webhookConfigService.getWebhookConfigs(merchantId);
        return ResponseEntity.ok(ApiResponse.success(configs));
    }

    @PutMapping("/{merchantId}/webhooks/{configId}")
    public ResponseEntity<ApiResponse<WebhookConfig>> updateWebhookConfig(
            @PathVariable UUID merchantId,
            @PathVariable UUID configId,
            @Valid @RequestBody WebhookConfigRequest request) {
        WebhookConfig config = webhookConfigService.updateWebhookConfig(configId, request);
        return ResponseEntity.ok(ApiResponse.success(config));
    }

    @DeleteMapping("/{merchantId}/webhooks/{configId}")
    public ResponseEntity<ApiResponse<Void>> deactivateWebhookConfig(
            @PathVariable UUID merchantId,
            @PathVariable UUID configId) {
        webhookConfigService.deactivateWebhookConfig(configId);
        return ResponseEntity.ok(ApiResponse.success(null));
    }
}
