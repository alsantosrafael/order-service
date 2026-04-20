package com.example.domain.entity;

import com.example.domain.enums.OrderStatus;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "orders")
public class Order extends PanacheEntityBase {

	@Id @GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "customer_id", nullable = false)
	public String customerId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	public OrderStatus status;

	@Column(name = "total_amount", precision = 12, scale = 2)
	public BigDecimal totalAmount;

	@Column(name = "created_at", nullable = false)
	public LocalDateTime createdAt;

	@Column(name = "updated_at", nullable = false)
	public LocalDateTime updatedAt;

	@OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
	public List<OrderItem> items = new ArrayList<>();


}
