package com.payflow.payment.controller;

import com.payflow.common.dto.ApiResponse;
import com.payflow.payment.dto.CreateOrderRequest;
import com.payflow.payment.dto.OrderResponse;
import com.payflow.payment.service.OrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST controller for payment order management.
 */
@RestController
@RequestMapping("/v1/orders")
@RequiredArgsConstructor
@Tag(name = "Orders", description = "Payment order management APIs")
public class OrderController {

    private final OrderService orderService;

    @PostMapping
    @Operation(summary = "Create a new payment order")
    public ResponseEntity<ApiResponse<OrderResponse>> createOrder(
            @Valid @RequestBody CreateOrderRequest request) {
        OrderResponse response = orderService.createOrder(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(response));
    }

    @GetMapping("/{orderId}")
    @Operation(summary = "Get order by ID")
    public ResponseEntity<ApiResponse<OrderResponse>> getOrder(
            @PathVariable String orderId) {
        OrderResponse response = orderService.getOrder(orderId);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping
    @Operation(summary = "List orders by merchant")
    public ResponseEntity<ApiResponse<List<OrderResponse>>> listOrders(
            @RequestParam String merchantId) {
        List<OrderResponse> orders = orderService.listByMerchant(merchantId);
        return ResponseEntity.ok(ApiResponse.success(orders));
    }

    @PostMapping("/expire")
    @Operation(summary = "Expire stale orders (admin/internal)")
    public ResponseEntity<ApiResponse<Integer>> expireOrders() {
        int count = orderService.expireOrders();
        return ResponseEntity.ok(ApiResponse.success(count));
    }
}
