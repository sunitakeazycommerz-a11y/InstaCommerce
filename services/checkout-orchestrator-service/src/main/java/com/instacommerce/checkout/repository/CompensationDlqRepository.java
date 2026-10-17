package com.instacommerce.checkout.repository;

import com.instacommerce.checkout.domain.CompensationDlq;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface CompensationDlqRepository extends JpaRepository<CompensationDlq, UUID> {
    List<CompensationDlq> findByWorkflowId(String workflowId);
    List<CompensationDlq> findByStatus(String status);
}
