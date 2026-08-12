package com.payflow.payment.service;

import com.payflow.common.constant.OrderStatus;
import com.payflow.common.exception.ResourceNotFoundException;
import com.payflow.payment.dto.CreateOrderRequest;
import com.payflow.payment.dto.OrderResponse;
import com.payflow.payment.mapper.OrderMapper;
import com.payflow.payment.model.Order;
import com.payflow.payment.repository.OrderRepository;
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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("OrderService Unit Tests")
class OrderServiceTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private OrderMapper orderMapper;

    @InjectMocks
    private OrderService orderService;

    private Order testOrder;
    private CreateOrderRequest createOrderRequest;
    private OrderResponse orderResponse;

    @BeforeEach
    void setUp() {
        testOrder = Order.builder()
                .id("order-001")
                .merchantId("merchant-001")
                .amount(new BigDecimal("15000"))
                .currency("INR")
                .status(OrderStatus.CREATED)
                .customerEmail("customer@example.com")
                .description("Test order")
                .expiresAt(Instant.now().plus(30, ChronoUnit.MINUTES))
                .build();

        createOrderRequest = new CreateOrderRequest();
        createOrderRequest.setMerchantId("merchant-001");
        createOrderRequest.setAmount(new BigDecimal("15000"));
        createOrderRequest.setCurrency("INR");
        createOrderRequest.setCustomerEmail("customer@example.com");
        createOrderRequest.setDescription("Test order");

        orderResponse = new OrderResponse();
        orderResponse.setId("order-001");
        orderResponse.setMerchantId("merchant-001");
        orderResponse.setAmount(new BigDecimal("15000"));
        orderResponse.setCurrency("INR");
        orderResponse.setStatus("CREATED");
    }

    @Test
    @DisplayName("createOrder - should create order and return response")
    void createOrder_Success() {
        when(orderRepository.save(any(Order.class))).thenReturn(testOrder);
        when(orderMapper.toResponse(any(Order.class))).thenReturn(orderResponse);

        OrderResponse result = orderService.createOrder(createOrderRequest);

        assertThat(result).isNotNull();
        assertThat(result.getId()).isEqualTo("order-001");
        assertThat(result.getMerchantId()).isEqualTo("merchant-001");
        assertThat(result.getAmount()).isEqualByComparingTo(new BigDecimal("15000"));
        assertThat(result.getStatus()).isEqualTo("CREATED");

        verify(orderRepository).save(any(Order.class));
    }

    @Test
    @DisplayName("getOrder - should return order response when found")
    void getOrder_Found_ReturnsResponse() {
        when(orderRepository.findById("order-001")).thenReturn(Optional.of(testOrder));
        when(orderMapper.toResponse(testOrder)).thenReturn(orderResponse);

        OrderResponse result = orderService.getOrder("order-001");

        assertThat(result).isNotNull();
        assertThat(result.getId()).isEqualTo("order-001");
        assertThat(result.getCurrency()).isEqualTo("INR");
    }

    @Test
    @DisplayName("getOrder - should throw ResourceNotFoundException when not found")
    void getOrder_NotFound_Throws() {
        when(orderRepository.findById("order-unknown")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> orderService.getOrder("order-unknown"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("updateOrderStatus - should update order status")
    void updateOrderStatus_Success() {
        when(orderRepository.findById("order-001")).thenReturn(Optional.of(testOrder));
        when(orderRepository.save(any(Order.class))).thenReturn(testOrder);

        orderService.updateOrderStatus("order-001", OrderStatus.PAID);

        assertThat(testOrder.getStatus()).isEqualTo(OrderStatus.PAID);
        verify(orderRepository).save(testOrder);
    }

    @Test
    @DisplayName("getOrderEntity - should return entity directly for internal use")
    void getOrderEntity_ReturnsEntity() {
        when(orderRepository.findById("order-001")).thenReturn(Optional.of(testOrder));

        Order result = orderService.getOrderEntity("order-001");

        assertThat(result).isNotNull();
        assertThat(result.getId()).isEqualTo("order-001");
        assertThat(result.getAmount()).isEqualByComparingTo(new BigDecimal("15000"));
    }
}
