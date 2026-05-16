package com.umurinan.hikaricp.repository;

import com.umurinan.hikaricp.entity.OrderEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderRepository extends JpaRepository<OrderEntity, Long> {

    List<OrderEntity> findTop20ByOrderByCreatedAtDesc();
}
