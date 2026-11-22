package com.oorja.backend.repository;

import com.oorja.backend.entity.RefundEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RefundEventRepository extends JpaRepository<RefundEvent, Long> {
    List<RefundEvent> findByRefundIdOrderByCreatedAtAsc(Long refundId);
    List<RefundEvent> findByRefundIdInOrderByCreatedAtAsc(List<Long> refundIds);
}
