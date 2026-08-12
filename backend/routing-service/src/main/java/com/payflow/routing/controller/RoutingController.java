package com.payflow.routing.controller;

import com.payflow.routing.dto.RoutingRequest;
import com.payflow.routing.dto.RoutingResponse;
import com.payflow.routing.fraud.FraudResult;
import com.payflow.routing.iso8583.*;
import com.payflow.routing.netty.BankNettyClient;
import com.payflow.routing.routing.BankRoute;
import com.payflow.routing.routing.RoutingDecision;
import com.payflow.routing.service.FraudDetectionService;
import com.payflow.routing.service.SmartRoutingService;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.CompletableFuture;

/**
 * Internal routing controller called by payment-service to route transactions to banks.
 */
@RestController
@RequestMapping("/internal")
@Tag(name = "Routing", description = "Internal transaction routing API")
public class RoutingController {

    private static final Logger log = LoggerFactory.getLogger(RoutingController.class);

    private final SmartRoutingService smartRoutingService;
    private final FraudDetectionService fraudDetectionService;
    private final BankNettyClient bankNettyClient;

    public RoutingController(SmartRoutingService smartRoutingService,
                             FraudDetectionService fraudDetectionService,
                             BankNettyClient bankNettyClient) {
        this.smartRoutingService = smartRoutingService;
        this.fraudDetectionService = fraudDetectionService;
        this.bankNettyClient = bankNettyClient;
    }

    /**
     * Routes a payment transaction to the appropriate bank.
     * Performs fraud detection, selects optimal route, and communicates with the bank via ISO 8583.
     */
    @PostMapping("/route")
    @Operation(summary = "Route a payment transaction", description = "Called by payment-service to route transactions")
    @CircuitBreaker(name = "bankCommunication", fallbackMethod = "routeFallback")
    public ResponseEntity<RoutingResponse> routeTransaction(@Valid @RequestBody RoutingRequest request) {
        long startTime = System.currentTimeMillis();

        log.info("Routing transaction: merchant={}, amount={}, currency={}",
                request.getMerchantId(), request.getAmount(), request.getCurrency());

        // Step 1: Fraud detection
        FraudResult fraudResult = fraudDetectionService.analyze(request);
        if (fraudResult.isDeclined()) {
            log.warn("Transaction DECLINED by fraud detection: score={}, reasons={}",
                    fraudResult.score(), fraudResult.reasons());
            return ResponseEntity.ok(RoutingResponse.fraudDeclined(
                    String.join("; ", fraudResult.reasons())));
        }

        // Step 2: Select route using epsilon-greedy
        RoutingDecision decision = smartRoutingService.selectRoute();
        BankRoute selectedBank = decision.selectedBank();

        log.info("Route selected: bank={}, explore={}", selectedBank.getBankName(), decision.isExplore());

        // Step 3: Build ISO 8583 message
        Iso8583Message isoRequest = buildIso8583Request(request);

        // Step 4: Send to bank via Netty
        try {
            CompletableFuture<Iso8583Message> responseFuture = bankNettyClient.sendMessage(isoRequest);
            Iso8583Message isoResponse = responseFuture.join();

            long latencyMs = System.currentTimeMillis() - startTime;

            // Step 5: Parse response
            String responseCode = isoResponse.getField(Iso8583Constants.FIELD_RESPONSE_CODE);
            String authCode = isoResponse.getField(Iso8583Constants.FIELD_AUTH_CODE);
            boolean success = Iso8583Constants.RESPONSE_APPROVED.equals(responseCode);

            // Step 6: Record metrics for future routing
            smartRoutingService.recordResult(selectedBank.getBankId(), success, latencyMs);

            if (success) {
                log.info("Transaction APPROVED: bank={}, authCode={}, latency={}ms",
                        selectedBank.getBankName(), authCode, latencyMs);
                return ResponseEntity.ok(RoutingResponse.approved(authCode, selectedBank.getBankId(), latencyMs));
            } else {
                String message = mapResponseCodeToMessage(responseCode);
                log.info("Transaction DECLINED by bank: code={}, message={}", responseCode, message);
                return ResponseEntity.ok(RoutingResponse.declined(responseCode, message, selectedBank.getBankId(), latencyMs));
            }

        } catch (Exception e) {
            long latencyMs = System.currentTimeMillis() - startTime;
            smartRoutingService.recordResult(selectedBank.getBankId(), false, latencyMs);
            log.error("Bank communication failed: {}", e.getMessage(), e);
            throw new RuntimeException("Bank communication error", e);
        }
    }

    /**
     * Fallback method when the circuit breaker is open.
     */
    public ResponseEntity<RoutingResponse> routeFallback(RoutingRequest request, Throwable throwable) {
        log.error("Circuit breaker OPEN - bank communication unavailable: {}", throwable.getMessage());
        return ResponseEntity.ok(RoutingResponse.error(
                "Bank communication temporarily unavailable. Please retry."));
    }

    /**
     * Builds an ISO 8583 authorization request from the routing request.
     */
    private Iso8583Message buildIso8583Request(RoutingRequest request) {
        String traceNumber = String.format("%06d", System.nanoTime() % 1000000);
        String time = LocalTime.now().format(DateTimeFormatter.ofPattern("HHmmss"));
        String amountStr = String.format("%012d", request.getAmount().movePointRight(2).longValue());

        return new Iso8583MessageBuilder()
                .setMti(Iso8583Constants.MTI_AUTH_REQUEST)
                .setPan(request.getCardNumber() != null ? request.getCardNumber() : "0000000000000000")
                .setProcessingCode(Iso8583Constants.PROC_CODE_PURCHASE)
                .setAmount(amountStr)
                .setTraceNumber(traceNumber)
                .setTime(time)
                .setCurrencyCode(request.getCurrency() != null ? mapCurrencyToCode(request.getCurrency()) : "840")
                .build();
    }

    /**
     * Maps ISO currency name to numeric code.
     */
    private String mapCurrencyToCode(String currency) {
        return switch (currency.toUpperCase()) {
            case "USD" -> "840";
            case "EUR" -> "978";
            case "GBP" -> "826";
            case "JPY" -> "392";
            case "INR" -> "356";
            default -> "840";
        };
    }

    /**
     * Maps ISO 8583 response codes to human-readable messages.
     */
    private String mapResponseCodeToMessage(String responseCode) {
        if (responseCode == null) return "Unknown error";
        return switch (responseCode) {
            case "00" -> "Approved";
            case "05" -> "Do Not Honor";
            case "14" -> "Invalid Card Number";
            case "51" -> "Insufficient Funds";
            case "54" -> "Expired Card";
            case "59" -> "Suspected Fraud";
            case "96" -> "System Malfunction";
            default -> "Declined (code: " + responseCode + ")";
        };
    }
}
