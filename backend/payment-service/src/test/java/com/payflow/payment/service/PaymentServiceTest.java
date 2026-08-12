package com.payflow.payment.service;

import com.payflow.common.constant.OrderStatus;
import com.payflow.common.constant.PaymentMethod;
import com.payflow.common.constant.PaymentStatus;
import com.payflow.common.exception.PayflowException;
import com.payflow.payment.dto.AuthorizePaymentRequest;
import com.payflow.payment.dto.CapturePaymentRequest;
import com.payflow.payment.dto.PaymentResponse;
import com.payflow.payment.feign.RoutingServiceClient;
import com.payflow.payment.mapper.PaymentMapper;
import com.payflow.payment.model.Order;
import com.payflow.payment.model.Payment;
import com.payflow.payment.repository.PaymentMethodRepository;
import com.payflow.payment.repository.PaymentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("PaymentService Unit Tests")
class PaymentServiceTest {

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private PaymentMethodRepository paymentMethodRepository;

    @Mock
    private OrderService orderService;

    @Mock
    private RoutingServiceClient routingServiceClient;

    @Mock
    private EventPublisher eventPublisher;

    @Mock
    private PaymentMapper paymentMapper;

    @InjectMocks
    private PaymentService paymentService;

    private Order testOrder;
    private Payment testPayment;
    private AuthorizePaymentRequest authorizeRequest;
    private PaymentResponse paymentResponse;

    @BeforeEach
    void setUp() {
        testOrder = Order.builder()
                .id("order-001")
                .merchantId("merchant-001")
                .amount(new BigDecimal("10000"))
                .currency("INR")
                .status(OrderStatus.CREATED)
                .expiresAt(Instant.now().plus(30, ChronoUnit.MINUTES))
                .build();

        testPayment = Payment.builder()
                .id("pay-001")
                .orderId("order-001")
                .merchantId("merchant-001")
                .amount(new BigDecimal("10000"))
                .currency("INR")
                .status(PaymentStatus.AUTHORIZED)
                .paymentMethod(PaymentMethod.CARD)
                .authorizationCode("AUTH123")
                .bankReferenceId("BANK-REF-001")
                .build();

        authorizeRequest = new AuthorizePaymentRequest();
        authorizeRequest.setOrderId("order-001");
        authorizeRequest.setPaymentMethod("CARD");
        authorizeRequest.setCardNumber("4111111111111111");
        authorizeRequest.setCardExpiryMonth("12");
        authorizeRequest.setCardExpiryYear("2026");

        paymentResponse = new PaymentResponse();
        paymentResponse.setId("pay-001");
        paymentResponse.setOrderId("order-001");
        paymentResponse.setStatus("AUTHORIZED");
        paymentResponse.setAmount(new BigDecimal("10000"));
    }

    @Test
    @DisplayName("authorize - should create payment and route to bank successfully")
    void authorize_Success() {
        when(orderService.getOrderEntity("order-001")).thenReturn(testOrder);
        when(paymentRepository.findByOrderId("order-001")).thenReturn(Optional.empty());
        when(paymentRepository.save(any(Payment.class))).thenAnswer(invocation -> {
            Payment p = invocation.getArgument(0);
            if (p.getId() == null) p.setId("pay-001");
            return p;
        });
        when(routingServiceClient.routePayment(any(Map.class))).thenReturn(Map.of(
                "status", "AUTHORIZED",
                "authorizationCode", "AUTH123",
                "bankReferenceId", "BANK-REF-001"
        ));
        when(paymentMapper.toResponse(any(Payment.class))).thenReturn(paymentResponse);

        PaymentResponse result = paymentService.authorize(authorizeRequest);

        assertThat(result).isNotNull();
        assertThat(result.getId()).isEqualTo("pay-001");
        assertThat(result.getStatus()).isEqualTo("AUTHORIZED");

        verify(paymentRepository, atLeastOnce()).save(any(Payment.class));
        verify(orderService).updateOrderStatus("order-001", OrderStatus.ATTEMPTED);
    }

    @Test
    @DisplayName("capture - should change payment status from AUTHORIZED to CAPTURED")
    void capture_ChangesStatus() {
        CapturePaymentRequest captureRequest = new CapturePaymentRequest();
        captureRequest.setPaymentId("pay-001");
        captureRequest.setAmount(new BigDecimal("10000"));

        when(paymentRepository.findById("pay-001")).thenReturn(Optional.of(testPayment));
        when(paymentRepository.save(any(Payment.class))).thenAnswer(invocation -> invocation.getArgument(0));

        PaymentResponse capturedResponse = new PaymentResponse();
        capturedResponse.setId("pay-001");
        capturedResponse.setStatus("CAPTURED");
        when(paymentMapper.toResponse(any(Payment.class))).thenReturn(capturedResponse);

        PaymentResponse result = paymentService.capture(captureRequest);

        assertThat(result).isNotNull();
        assertThat(result.getStatus()).isEqualTo("CAPTURED");

        verify(orderService).updateOrderStatus("order-001", OrderStatus.PAID);
    }

    @Test
    @DisplayName("capture - should throw PayflowException for invalid state transition")
    void capture_InvalidState_Throws() {
        Payment createdPayment = Payment.builder()
                .id("pay-002")
                .orderId("order-002")
                .merchantId("merchant-001")
                .amount(new BigDecimal("5000"))
                .currency("INR")
                .status(PaymentStatus.CAPTURED) // Already captured
                .paymentMethod(PaymentMethod.CARD)
                .build();

        CapturePaymentRequest captureRequest = new CapturePaymentRequest();
        captureRequest.setPaymentId("pay-002");

        when(paymentRepository.findById("pay-002")).thenReturn(Optional.of(createdPayment));

        assertThatThrownBy(() -> paymentService.capture(captureRequest))
                .isInstanceOf(PayflowException.class)
                .hasMessageContaining("INVALID_STATE");
    }

    @Test
    @DisplayName("voidPayment - should change status from AUTHORIZED to VOIDED")
    void voidPayment_Success() {
        when(paymentRepository.findById("pay-001")).thenReturn(Optional.of(testPayment));
        when(paymentRepository.save(any(Payment.class))).thenAnswer(invocation -> invocation.getArgument(0));

        PaymentResponse voidedResponse = new PaymentResponse();
        voidedResponse.setId("pay-001");
        voidedResponse.setStatus("VOIDED");
        when(paymentMapper.toResponse(any(Payment.class))).thenReturn(voidedResponse);

        PaymentResponse result = paymentService.voidPayment("pay-001");

        assertThat(result).isNotNull();
        assertThat(result.getStatus()).isEqualTo("VOIDED");
    }
}
