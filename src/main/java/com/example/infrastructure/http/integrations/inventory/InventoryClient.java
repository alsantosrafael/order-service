package com.example.infrastructure.http.integrations.inventory;


import com.example.infrastructure.http.integrations.inventory.DTOs.StockResponse;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

@RegisterRestClient(configKey = "inventory-api")
@Path("/api/v1/inventory")
public interface InventoryClient {

	@GET
	@Path("/{productId}/stock")
	StockResponse checkStock(@PathParam("productId") String productId, @QueryParam("quantity") Integer quantity);
}
