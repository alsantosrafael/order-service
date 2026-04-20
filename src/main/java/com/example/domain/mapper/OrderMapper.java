package com.example.domain.mapper;

import com.example.domain.dtos.OrderResponse;
import com.example.domain.entity.Order;
import org.mapstruct.Mapper;
import org.mapstruct.MappingConstants;
import org.mapstruct.factory.Mappers;

@Mapper(componentModel = MappingConstants.ComponentModel.JAKARTA_CDI, uses = OrderItemMapper.class)
public interface OrderMapper {

	OrderMapper INSTANCE = Mappers.getMapper(OrderMapper.class);

	OrderResponse toDto(Order order);
}
