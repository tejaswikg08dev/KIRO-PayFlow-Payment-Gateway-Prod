package com.payflow.merchant.repository;

import com.payflow.merchant.model.WebhookConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface WebhookConfigRepository extends JpaRepository<WebhookConfig, UUID> {

    List<WebhookConfig> findByMerchantId(UUID merchantId);

    List<WebhookConfig> findByMerchantIdAndActiveTrue(UUID merchantId);
}
