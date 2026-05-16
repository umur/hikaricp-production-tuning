package com.umurinan.hikaricp.dto;

import com.umurinan.hikaricp.entity.OrderEntity;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record OrderResponse(
        Long id,
        String customerEmail,
        List<String> items,
        BigDecimal price,
        Instant createdAt) {

    public static OrderResponse from(OrderEntity entity) {
        return new OrderResponse(
                entity.getId(),
                entity.getCustomerEmail(),
                List.of(entity.getItems().split(",")),
                entity.getPrice(),
                entity.getCreatedAt());
    }
}
