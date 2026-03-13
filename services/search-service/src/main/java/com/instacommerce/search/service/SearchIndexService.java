package com.instacommerce.search.service;

import com.instacommerce.search.domain.model.SearchDocument;
import com.instacommerce.search.repository.SearchDocumentRepository;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SearchIndexService {
    private static final Logger log = LoggerFactory.getLogger(SearchIndexService.class);

    private final SearchDocumentRepository searchDocumentRepository;

    public SearchIndexService(SearchDocumentRepository searchDocumentRepository) {
        this.searchDocumentRepository = searchDocumentRepository;
    }

    @Transactional
    @CacheEvict(value = {"searchResults", "autocomplete"}, allEntries = true)
    public void upsertDocument(UUID productId, String name, String description, String brand,
                               String category, long priceCents, String imageUrl, boolean inStock,
                               UUID storeId) {
        log.info("Upserting search document for productId={}", productId);
        searchDocumentRepository.findByProductId(productId).ifPresentOrElse(
                existing -> {
                    existing.setName(name);
                    existing.setDescription(description);
                    existing.setBrand(brand);
                    existing.setCategory(category);
                    existing.setPriceCents(priceCents);
                    existing.setImageUrl(imageUrl);
                    existing.setInStock(inStock);
                    existing.setStoreId(storeId);
                    searchDocumentRepository.save(existing);
                    log.debug("Updated search document for productId={}", productId);
                },
                () -> {
                    SearchDocument doc = new SearchDocument(productId, name, description, brand,
                            category, priceCents, imageUrl, inStock, storeId);
                    searchDocumentRepository.save(doc);
                    log.debug("Created search document for productId={}", productId);
                });
    }

    @Transactional
    @CacheEvict(value = {"searchResults", "autocomplete"}, allEntries = true)
    public void deleteDocument(UUID productId) {
        log.info("Deleting search document for productId={}", productId);
        searchDocumentRepository.deleteByProductId(productId);
    }
}
