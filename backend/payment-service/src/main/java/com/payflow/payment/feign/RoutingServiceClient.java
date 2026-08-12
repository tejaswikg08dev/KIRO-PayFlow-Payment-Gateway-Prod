package com.payflow.payment.feign;

import com.payflow.payment.config.FeignConfig;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.Map;

/**
 * Feign client for the routing-service.
 * Routes payment transactions to the appropriate bank/PSP via internal API.
 */
@FeignClient(
        name = "routing-service",
        configuration = FeignConfig.class,
        path = "/internal"
)
public interface RoutingServiceClient {

    /**
     * Routes a payment to the appropriate bank for authorization.
     *
     * @param request payment routing request containing paymentId, amount, currency, paymentMethod
     * @return bank response with status, authorizationCode, bankReferenceId
     */
    @PostMapping("/route")
    Map<String, Object> routePayment(@RequestBody Map<String, Object> request);
}
