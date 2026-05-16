package com.umurinan.hikaricp.dto;

import java.util.List;

public record PlaceOrderRequest(String customerEmail, List<String> items) {}
