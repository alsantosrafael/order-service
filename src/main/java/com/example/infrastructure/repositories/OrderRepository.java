package com.example.infrastructure.repositories;

import com.example.domain.entity.Order;
import com.example.domain.enums.OrderStatus;
import io.quarkus.hibernate.orm.panache.PanacheRepository;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.Optional;

@ApplicationScoped
public class OrderRepository implements PanacheRepository<Order> {

	public List<Order> findByStatusPaged(OrderStatus status, int page, int size) {
		return find("status", status)
			.page(page, size)
			.list();
	}

	public Optional<Order> findByIdWithItems(Long id) {
		return find("SELECT o " +
			"FROM Order o " +
			"LEFT JOIN FETCH o.items" +
			"WHERE o.id = ?1", id)
			.firstResultOptional();
	}
}
