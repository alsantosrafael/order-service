package com.example.domain.dtos;

import com.example.domain.enums.OrderStatus;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@RegisterForReflection
public record OrderResponse(
	Long id,
 	String customerId,
	OrderStatus status,
	BigDecimal totalAmount,
	LocalDateTime createdAt,
	LocalDateTime updatedAt,
	List<OrderItemResponse> items
) {
}
