package com.payflow.payment.mapper;

import com.payflow.payment.dto.OrderResponse;
import com.payflow.payment.model.Order;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * MapStruct mapper for Order entity ↔ DTO conversions.
 */
@Mapper(componentModel = "spring")
public interface OrderMapper {

    @Mapping(target = "status", expression = "java(order.getStatus().name())")
    OrderResponse toResponse(Order order);
}
