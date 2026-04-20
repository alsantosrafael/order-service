package com.example.application.services;

import com.example.application.config.OrderConfig;
import com.example.domain.dtos.OrderResponse;
import com.example.domain.enums.OrderStatus;
import com.example.domain.mapper.OrderMapper;
import com.example.infrastructure.repositories.OrderRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;

@ApplicationScoped
public class OrderService {
	private final OrderRepository orderRepository;
	private final OrderMapper orderMapper;

	@Inject
	public OrderService(OrderRepository orderRepository,  OrderMapper orderMapper) {
		this.orderRepository = orderRepository;
		this.orderMapper = orderMapper;
	}

	public List<OrderResponse> findByStatus(String status, int page, int size) {
		return orderRepository.findByStatusPaged(OrderStatus.valueOf(status), page,size)
			.stream()
			.map(this.orderMapper::toDto)
			.toList();
	}
}
