package com.payflow.merchant.service;

import com.payflow.common.exception.DuplicateResourceException;
import com.payflow.common.exception.ResourceNotFoundException;
import com.payflow.merchant.dto.MerchantRegisterRequest;
import com.payflow.merchant.dto.MerchantResponse;
import com.payflow.merchant.mapper.MerchantMapper;
import com.payflow.merchant.model.Merchant;
import com.payflow.merchant.repository.MerchantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class MerchantService {

    private final MerchantRepository merchantRepository;
    private final MerchantMapper merchantMapper;

    /**
     * Onboard a new merchant.
     */
    @Transactional
    public MerchantResponse registerMerchant(MerchantRegisterRequest request) {
        if (merchantRepository.existsByEmail(request.getEmail())) {
            throw new DuplicateResourceException("Merchant", "email", request.getEmail());
        }

        Merchant merchant = merchantMapper.toEntity(request);
        Merchant saved = merchantRepository.save(merchant);

        log.info("Merchant registered: id={}, name={}", saved.getId(), saved.getName());
        return merchantMapper.toResponse(saved);
    }

    /**
     * Get merchant by ID.
     */
    @Transactional(readOnly = true)
    public MerchantResponse getMerchant(UUID merchantId) {
        Merchant merchant = merchantRepository.findById(merchantId)
                .orElseThrow(() -> new ResourceNotFoundException("Merchant", merchantId.toString()));
        return merchantMapper.toResponse(merchant);
    }

    /**
     * Get all merchants.
     */
    @Transactional(readOnly = true)
    public List<MerchantResponse> getAllMerchants() {
        return merchantRepository.findAll().stream()
                .map(merchantMapper::toResponse)
                .collect(Collectors.toList());
    }

    /**
     * Update merchant details.
     */
    @Transactional
    public MerchantResponse updateMerchant(UUID merchantId, MerchantRegisterRequest request) {
        Merchant merchant = merchantRepository.findById(merchantId)
                .orElseThrow(() -> new ResourceNotFoundException("Merchant", merchantId.toString()));

        // Check email uniqueness if changed
        if (!merchant.getEmail().equals(request.getEmail()) &&
                merchantRepository.existsByEmail(request.getEmail())) {
            throw new DuplicateResourceException("Merchant", "email", request.getEmail());
        }

        merchantMapper.updateEntity(request, merchant);
        Merchant updated = merchantRepository.save(merchant);

        log.info("Merchant updated: id={}", updated.getId());
        return merchantMapper.toResponse(updated);
    }

    /**
     * Deactivate merchant.
     */
    @Transactional
    public void deactivateMerchant(UUID merchantId) {
        Merchant merchant = merchantRepository.findById(merchantId)
                .orElseThrow(() -> new ResourceNotFoundException("Merchant", merchantId.toString()));
        merchant.setActive(false);
        merchantRepository.save(merchant);
        log.info("Merchant deactivated: id={}", merchantId);
    }
}
