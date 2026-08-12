package com.payflow.settlement.repository;

import com.payflow.settlement.model.SettlementRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SettlementRecordRepository extends JpaRepository<SettlementRecord, String> {

    List<SettlementRecord> findByBatchId(String batchId);

    List<SettlementRecord> findByMerchantId(String merchantId);
}
