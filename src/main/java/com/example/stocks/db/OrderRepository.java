package com.example.stocks.db;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface OrderRepository extends JpaRepository<OrderEntity, Long> {

    Optional<OrderEntity> findByIdentifier(String identifier);

    Optional<OrderEntity> findByUuid(String uuid);
}
