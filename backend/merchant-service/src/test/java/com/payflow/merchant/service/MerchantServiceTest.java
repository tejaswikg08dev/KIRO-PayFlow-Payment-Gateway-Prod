package com.payflow.merchant.service;

import com.payflow.common.exception.DuplicateResourceException;
import com.payflow.common.exception.ResourceNotFoundException;
import com.payflow.merchant.dto.MerchantRegisterRequest;
import com.payflow.merchant.dto.MerchantResponse;
import com.payflow.merchant.mapper.MerchantMapper;
import com.payflow.merchant.model.Merchant;
import com.payflow.merchant.repository.MerchantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("MerchantService Unit Tests")
class MerchantServiceTest {

    @Mock
    private MerchantRepository merchantRepository;

    @Mock
    private MerchantMapper merchantMapper;

    @InjectMocks
    private MerchantService merchantService;

    private UUID merchantId;
    private Merchant testMerchant;
    private MerchantRegisterRequest registerRequest;
    private MerchantResponse merchantResponse;

    @BeforeEach
    void setUp() {
        merchantId = UUID.randomUUID();

        testMerchant = Merchant.builder()
                .id(merchantId)
                .name("Test Shop")
                .email("shop@example.com")
                .businessType("RETAIL")
                .mdrRate(2.0)
                .active(true)
                .createdAt(Instant.now())
                .build();

        registerRequest = new MerchantRegisterRequest();
        registerRequest.setName("Test Shop");
        registerRequest.setEmail("shop@example.com");
        registerRequest.setBusinessType("RETAIL");

        merchantResponse = new MerchantResponse();
        merchantResponse.setId(merchantId);
        merchantResponse.setName("Test Shop");
        merchantResponse.setEmail("shop@example.com");
        merchantResponse.setBusinessType("RETAIL");
        merchantResponse.setActive(true);
    }

    @Test
    @DisplayName("registerMerchant - should save and return merchant response")
    void registerMerchant_Success() {
        when(merchantRepository.existsByEmail(registerRequest.getEmail())).thenReturn(false);
        when(merchantMapper.toEntity(registerRequest)).thenReturn(testMerchant);
        when(merchantRepository.save(any(Merchant.class))).thenReturn(testMerchant);
        when(merchantMapper.toResponse(testMerchant)).thenReturn(merchantResponse);

        MerchantResponse result = merchantService.registerMerchant(registerRequest);

        assertThat(result).isNotNull();
        assertThat(result.getName()).isEqualTo("Test Shop");
        assertThat(result.getEmail()).isEqualTo("shop@example.com");

        verify(merchantRepository).save(any(Merchant.class));
    }

    @Test
    @DisplayName("registerMerchant - should throw DuplicateResourceException for existing email")
    void registerMerchant_DuplicateEmail_Throws() {
        when(merchantRepository.existsByEmail(registerRequest.getEmail())).thenReturn(true);

        assertThatThrownBy(() -> merchantService.registerMerchant(registerRequest))
                .isInstanceOf(DuplicateResourceException.class);

        verify(merchantRepository, never()).save(any(Merchant.class));
    }

    @Test
    @DisplayName("getMerchant - should throw ResourceNotFoundException when not found")
    void getMerchant_NotFound_Throws() {
        UUID unknownId = UUID.randomUUID();
        when(merchantRepository.findById(unknownId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> merchantService.getMerchant(unknownId))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("getMerchant - should return merchant response when found")
    void getMerchant_Found_ReturnsResponse() {
        when(merchantRepository.findById(merchantId)).thenReturn(Optional.of(testMerchant));
        when(merchantMapper.toResponse(testMerchant)).thenReturn(merchantResponse);

        MerchantResponse result = merchantService.getMerchant(merchantId);

        assertThat(result).isNotNull();
        assertThat(result.getId()).isEqualTo(merchantId);
    }

    @Test
    @DisplayName("updateMerchant - should update and return updated merchant")
    void updateMerchant_Success() {
        MerchantRegisterRequest updateRequest = new MerchantRegisterRequest();
        updateRequest.setName("Updated Shop");
        updateRequest.setEmail("shop@example.com");
        updateRequest.setBusinessType("E_COMMERCE");

        when(merchantRepository.findById(merchantId)).thenReturn(Optional.of(testMerchant));
        when(merchantRepository.save(any(Merchant.class))).thenReturn(testMerchant);
        when(merchantMapper.toResponse(any(Merchant.class))).thenReturn(merchantResponse);

        MerchantResponse result = merchantService.updateMerchant(merchantId, updateRequest);

        assertThat(result).isNotNull();
        verify(merchantMapper).updateEntity(eq(updateRequest), eq(testMerchant));
        verify(merchantRepository).save(testMerchant);
    }
}
