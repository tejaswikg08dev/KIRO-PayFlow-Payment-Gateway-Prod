package com.payflow.settlement.repository;

import com.payflow.settlement.model.SettlementBatch;
import com.payflow.settlement.model.SettlementBatch.BatchStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface SettlementBatchRepository extends JpaRepository<SettlementBatch, String> {

    Optional<SettlementBatch> findBySettlementDate(LocalDate settlementDate);

    List<SettlementBatch> findByStatus(BatchStatus status);
}
