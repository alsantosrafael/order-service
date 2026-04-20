package com.example.infrastructure.http;

import com.example.application.services.OrderService;
import com.example.domain.dtos.OrderResponse;
import io.smallrye.common.annotation.RunOnVirtualThread;
import io.vertx.core.impl.logging.Logger;
import io.vertx.core.impl.logging.LoggerFactory;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

import java.util.List;

@Path("/orders")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class OrderResource {

	private OrderService orderService;
	private static final Logger LOG = LoggerFactory.getLogger(OrderResource.class);
	@Inject
	public OrderResource(OrderService orderService) {
		this.orderService = orderService;
	}

//	@GET
//	@Path("/hi")
//	public String sayHello() {
//		LOG.info("Passing by here..");
//		return "Hello World";
//	}

	@GET
	@RunOnVirtualThread
	public List<OrderResponse> list(
		@QueryParam("status") @DefaultValue("ALL") String status,
		@QueryParam("page") @DefaultValue("0") int page,
		@QueryParam("size") @DefaultValue("20") int size
	) {
		return orderService.findByStatus(status, page, size);
	}

}
