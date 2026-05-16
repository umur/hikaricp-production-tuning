package com.umurinan.hikaricp.service;

import com.umurinan.hikaricp.client.PricingClient;
import com.umurinan.hikaricp.dto.OrderResponse;
import com.umurinan.hikaricp.dto.PlaceOrderRequest;
import com.umurinan.hikaricp.entity.OrderEntity;
import com.umurinan.hikaricp.repository.OrderRepository;
import java.math.BigDecimal;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderRepository orderRepository;
    private final PricingClient pricingClient;

    /**
     * The anti-pattern. External HTTP-style call inside @Transactional means the database
     * connection is held open for the full duration of the pricing call. Under sustained
     * load this pins every connection in the pool and the service starts timing out.
     *
     * <p>POST /api/orders/anti-pattern routes here. Run k6 against this endpoint with a
     * pool size of 10 and watch hikaricp_connections_pending climb almost immediately.
     */
    @Transactional
    public OrderResponse placeAntiPattern(PlaceOrderRequest request) {
        OrderEntity entity = orderRepository.save(toEntity(request, BigDecimal.ZERO));
        BigDecimal price = pricingClient.calculate(request.items());
        entity.setPrice(price);
        return OrderResponse.from(entity);
    }

    /**
     * The fix. Do the slow external call before opening the transaction. The transaction
     * lasts only as long as the database work, which keeps connections returning to the
     * pool quickly.
     *
     * <p>POST /api/orders routes here.
     */
    public OrderResponse place(PlaceOrderRequest request) {
        BigDecimal price = pricingClient.calculate(request.items());
        return persist(request, price);
    }

    @Transactional
    protected OrderResponse persist(PlaceOrderRequest request, BigDecimal price) {
        OrderEntity saved = orderRepository.save(toEntity(request, price));
        return OrderResponse.from(saved);
    }

    @Transactional(readOnly = true)
    public List<OrderResponse> recent() {
        return orderRepository.findTop20ByOrderByCreatedAtDesc()
                .stream()
                .map(OrderResponse::from)
                .toList();
    }

    private OrderEntity toEntity(PlaceOrderRequest request, BigDecimal price) {
        OrderEntity entity = new OrderEntity();
        entity.setCustomerEmail(request.customerEmail());
        entity.setItems(String.join(",", request.items()));
        entity.setPrice(price);
        return entity;
    }
}
