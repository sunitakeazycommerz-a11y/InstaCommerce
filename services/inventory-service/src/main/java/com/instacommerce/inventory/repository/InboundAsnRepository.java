package com.instacommerce.inventory.repository;

import com.instacommerce.inventory.domain.model.AsnStatus;
import com.instacommerce.inventory.domain.model.InboundAsn;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface InboundAsnRepository extends JpaRepository<InboundAsn, UUID> {
    Optional<InboundAsn> findByPalletBarcode(String palletBarcode);

    Optional<InboundAsn> findByAsnNumber(String asnNumber);

    List<InboundAsn> findByStoreIdAndStatus(UUID storeId, AsnStatus status);

    List<InboundAsn> findByStoreId(UUID storeId);
}
