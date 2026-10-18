package com.instacommerce.inventory.repository;

import com.instacommerce.inventory.domain.model.AsnLineItem;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AsnLineItemRepository extends JpaRepository<AsnLineItem, UUID> {
    List<AsnLineItem> findByAsnId(UUID asnId);
}
