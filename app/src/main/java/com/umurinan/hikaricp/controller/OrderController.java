package com.umurinan.hikaricp.controller;

import com.umurinan.hikaricp.dto.OrderResponse;
import com.umurinan.hikaricp.dto.PlaceOrderRequest;
import com.umurinan.hikaricp.service.OrderService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    @PostMapping
    public ResponseEntity<OrderResponse> place(@RequestBody PlaceOrderRequest request) {
        return ResponseEntity.ok(orderService.place(request));
    }

    @PostMapping("/anti-pattern")
    public ResponseEntity<OrderResponse> placeAntiPattern(@RequestBody PlaceOrderRequest request) {
        return ResponseEntity.ok(orderService.placeAntiPattern(request));
    }

    @GetMapping("/recent")
    public ResponseEntity<List<OrderResponse>> recent() {
        return ResponseEntity.ok(orderService.recent());
    }
}
