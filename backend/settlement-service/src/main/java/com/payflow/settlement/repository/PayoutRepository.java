package com.payflow.settlement.repository;

import com.payflow.settlement.model.Payout;
import com.payflow.settlement.model.Payout.PayoutStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PayoutRepository extends JpaRepository<Payout, String> {

    List<Payout> findByMerchantId(String merchantId);

    List<Payout> findByStatus(PayoutStatus status);
}
