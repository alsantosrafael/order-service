package com.example.domain.mapper;

import com.example.domain.dtos.OrderItemResponse;
import com.example.domain.entity.OrderItem;
import org.mapstruct.Mapper;
import org.mapstruct.MappingConstants;
import org.mapstruct.factory.Mappers;

@Mapper(componentModel = MappingConstants.ComponentModel.JAKARTA_CDI)
public interface OrderItemMapper {

	OrderItemMapper INSTANCE = Mappers.getMapper(OrderItemMapper.class);

	OrderItemResponse toDto(OrderItem orderItem);
}
