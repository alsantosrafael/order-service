package com.example.infrastructure.http.integrations.inventory.DTOs;

public record StockResponse(boolean available, int currentStock, String status) {}
