package com.payflow.merchant.repository;

import com.payflow.merchant.model.ApiKey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ApiKeyRepository extends JpaRepository<ApiKey, UUID> {

    List<ApiKey> findByMerchantId(UUID merchantId);

    Optional<ApiKey> findByKeyHash(String keyHash);

    Optional<ApiKey> findByPrefix(String prefix);

    List<ApiKey> findByMerchantIdAndActiveTrue(UUID merchantId);
}
