package com.payflow.payment.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.payflow.payment.dto.AuthorizePaymentRequest;
import com.payflow.payment.dto.CapturePaymentRequest;
import com.payflow.payment.dto.PaymentResponse;
import com.payflow.payment.service.IdempotencyService;
import com.payflow.payment.service.PaymentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(PaymentController.class)
@DisplayName("PaymentController Integration Tests")
class PaymentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private PaymentService paymentService;

    @MockBean
    private IdempotencyService idempotencyService;

    @Autowired
    private ObjectMapper objectMapper;

    private PaymentResponse mockPaymentResponse;

    @BeforeEach
    void setUp() {
        mockPaymentResponse = new PaymentResponse();
        mockPaymentResponse.setId("pay-001");
        mockPaymentResponse.setOrderId("order-001");
        mockPaymentResponse.setMerchantId("merchant-001");
        mockPaymentResponse.setAmount(new BigDecimal("10000"));
        mockPaymentResponse.setCurrency("INR");
        mockPaymentResponse.setStatus("AUTHORIZED");
    }

    @Test
    @DisplayName("POST /v1/payments/authorize - should return 201 for successful authorization")
    void authorize_ReturnsCreated() throws Exception {
        AuthorizePaymentRequest request = new AuthorizePaymentRequest();
        request.setOrderId("order-001");
        request.setPaymentMethod("CARD");
        request.setCardNumber("4111111111111111");
        request.setCardExpiryMonth("12");
        request.setCardExpiryYear("2026");

        when(idempotencyService.getCachedResponse(anyString())).thenReturn(Optional.empty());
        when(idempotencyService.acquireLock(anyString())).thenReturn(true);
        when(paymentService.authorize(any(AuthorizePaymentRequest.class)))
                .thenReturn(mockPaymentResponse);

        mockMvc.perform(post("/v1/payments/authorize")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "idem-key-001")
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.id").value("pay-001"))
                .andExpect(jsonPath("$.data.status").value("AUTHORIZED"));
    }

    @Test
    @DisplayName("POST /v1/payments/capture - should return 200 for successful capture")
    void capture_ReturnsOk() throws Exception {
        CapturePaymentRequest request = new CapturePaymentRequest();
        request.setPaymentId("pay-001");
        request.setAmount(new BigDecimal("10000"));

        PaymentResponse capturedResponse = new PaymentResponse();
        capturedResponse.setId("pay-001");
        capturedResponse.setOrderId("order-001");
        capturedResponse.setAmount(new BigDecimal("10000"));
        capturedResponse.setStatus("CAPTURED");

        when(paymentService.capture(any(CapturePaymentRequest.class))).thenReturn(capturedResponse);

        mockMvc.perform(post("/v1/payments/capture")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value("pay-001"))
                .andExpect(jsonPath("$.data.status").value("CAPTURED"));
    }

    @Test
    @DisplayName("GET /v1/payments/{paymentId} - should return 200 with payment details")
    void getPayment_ReturnsOk() throws Exception {
        when(paymentService.getPayment("pay-001")).thenReturn(mockPaymentResponse);

        mockMvc.perform(get("/v1/payments/pay-001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value("pay-001"))
                .andExpect(jsonPath("$.data.orderId").value("order-001"))
                .andExpect(jsonPath("$.data.amount").value(10000));
    }

    @Test
    @DisplayName("POST /v1/payments/authorize - should return cached response for duplicate idempotency key")
    void authorize_CachedIdempotencyKey_ReturnsCached() throws Exception {
        AuthorizePaymentRequest request = new AuthorizePaymentRequest();
        request.setOrderId("order-001");
        request.setPaymentMethod("CARD");
        request.setCardNumber("4111111111111111");
        request.setCardExpiryMonth("12");
        request.setCardExpiryYear("2026");

        String cachedJson = objectMapper.writeValueAsString(mockPaymentResponse);
        when(idempotencyService.getCachedResponse("duplicate-key")).thenReturn(Optional.of(cachedJson));
        when(idempotencyService.deserialize(cachedJson, PaymentResponse.class))
                .thenReturn(mockPaymentResponse);

        mockMvc.perform(post("/v1/payments/authorize")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "duplicate-key")
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value("pay-001"));
    }
}
