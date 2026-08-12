package com.payflow.payment.controller;

import com.payflow.common.dto.ApiResponse;
import com.payflow.payment.dto.AuthorizePaymentRequest;
import com.payflow.payment.dto.CapturePaymentRequest;
import com.payflow.payment.dto.PaymentResponse;
import com.payflow.payment.service.IdempotencyService;
import com.payflow.payment.service.PaymentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Optional;

/**
 * REST controller for payment processing operations.
 * Supports idempotency via Idempotency-Key header.
 */
@RestController
@RequestMapping("/v1/payments")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Payments", description = "Payment processing APIs — authorize, capture, void")
public class PaymentController {

    private final PaymentService paymentService;
    private final IdempotencyService idempotencyService;

    @PostMapping("/authorize")
    @Operation(summary = "Authorize a payment for an order")
    public ResponseEntity<ApiResponse<PaymentResponse>> authorize(
            @Valid @RequestBody AuthorizePaymentRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {

        // Check idempotency
        if (idempotencyKey != null) {
            Optional<String> cached = idempotencyService.getCachedResponse(idempotencyKey);
            if (cached.isPresent()) {
                PaymentResponse cachedResponse = idempotencyService.deserialize(
                        cached.get(), PaymentResponse.class);
                return ResponseEntity.ok(ApiResponse.success(cachedResponse));
            }

            if (!idempotencyService.acquireLock(idempotencyKey)) {
                Optional<String> retryCache = idempotencyService.getCachedResponse(idempotencyKey);
                if (retryCache.isPresent()) {
                    PaymentResponse cachedResponse = idempotencyService.deserialize(
                            retryCache.get(), PaymentResponse.class);
                    return ResponseEntity.ok(ApiResponse.success(cachedResponse));
                }
            }
        }

        try {
            PaymentResponse response = paymentService.authorize(request);

            if (idempotencyKey != null) {
                idempotencyService.cacheResponse(idempotencyKey, response);
            }

            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(ApiResponse.success(response));
        } catch (Exception e) {
            if (idempotencyKey != null) {
                idempotencyService.releaseLock(idempotencyKey);
            }
            throw e;
        }
    }

    @PostMapping("/capture")
    @Operation(summary = "Capture an authorized payment")
    public ResponseEntity<ApiResponse<PaymentResponse>> capture(
            @Valid @RequestBody CapturePaymentRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {

        if (idempotencyKey != null) {
            Optional<String> cached = idempotencyService.getCachedResponse(idempotencyKey);
            if (cached.isPresent()) {
                PaymentResponse cachedResponse = idempotencyService.deserialize(
                        cached.get(), PaymentResponse.class);
                return ResponseEntity.ok(ApiResponse.success(cachedResponse));
            }

            if (!idempotencyService.acquireLock(idempotencyKey)) {
                Optional<String> retryCache = idempotencyService.getCachedResponse(idempotencyKey);
                if (retryCache.isPresent()) {
                    PaymentResponse cachedResponse = idempotencyService.deserialize(
                            retryCache.get(), PaymentResponse.class);
                    return ResponseEntity.ok(ApiResponse.success(cachedResponse));
                }
            }
        }

        try {
            PaymentResponse response = paymentService.capture(request);

            if (idempotencyKey != null) {
                idempotencyService.cacheResponse(idempotencyKey, response);
            }

            return ResponseEntity.ok(ApiResponse.success(response));
        } catch (Exception e) {
            if (idempotencyKey != null) {
                idempotencyService.releaseLock(idempotencyKey);
            }
            throw e;
        }
    }

    @PostMapping("/{paymentId}/void")
    @Operation(summary = "Void an authorized payment")
    public ResponseEntity<ApiResponse<PaymentResponse>> voidPayment(
            @PathVariable String paymentId) {
        PaymentResponse response = paymentService.voidPayment(paymentId);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping("/{paymentId}")
    @Operation(summary = "Get payment by ID")
    public ResponseEntity<ApiResponse<PaymentResponse>> getPayment(
            @PathVariable String paymentId) {
        PaymentResponse response = paymentService.getPayment(paymentId);
        return ResponseEntity.ok(ApiResponse.success(response));
    }
}
