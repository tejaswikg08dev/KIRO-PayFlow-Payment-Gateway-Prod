package com.payflow.payment.mapper;

import com.payflow.payment.dto.PaymentResponse;
import com.payflow.payment.model.Payment;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * MapStruct mapper for Payment entity ↔ DTO conversions.
 */
@Mapper(componentModel = "spring")
public interface PaymentMapper {

    @Mapping(target = "status", expression = "java(payment.getStatus().name())")
    @Mapping(target = "paymentMethod", expression = "java(payment.getPaymentMethod().name())")
    PaymentResponse toResponse(Payment payment);
}
