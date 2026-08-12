package com.payflow.merchant.service;

import com.payflow.common.exception.ResourceNotFoundException;
import com.payflow.merchant.dto.ApiKeyResponse;
import com.payflow.merchant.model.ApiKey;
import com.payflow.merchant.repository.ApiKeyRepository;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ApiKeyService Unit Tests")
class ApiKeyServiceTest {

    @Mock
    private ApiKeyRepository apiKeyRepository;

    @Mock
    private MerchantRepository merchantRepository;

    @InjectMocks
    private ApiKeyService apiKeyService;

    private UUID merchantId;
    private UUID keyId;
    private ApiKey testApiKey;

    @BeforeEach
    void setUp() {
        merchantId = UUID.randomUUID();
        keyId = UUID.randomUUID();

        testApiKey = ApiKey.builder()
                .id(keyId)
                .keyHash("sha256-hash-value")
                .prefix("abcd1234")
                .merchantId(merchantId)
                .active(true)
                .createdAt(Instant.now())
                .build();
    }

    @Test
    @DisplayName("generateApiKey - should create and return API key with raw key visible")
    void generateApiKey_Success() {
        when(merchantRepository.existsById(merchantId)).thenReturn(true);
        when(apiKeyRepository.save(any(ApiKey.class))).thenAnswer(invocation -> {
            ApiKey saved = invocation.getArgument(0);
            saved.setId(keyId);
            saved.setCreatedAt(Instant.now());
            return saved;
        });

        ApiKeyResponse response = apiKeyService.generateApiKey(merchantId);

        assertThat(response).isNotNull();
        assertThat(response.getRawKey()).startsWith("pk_");
        assertThat(response.getRawKey()).isNotBlank();
        assertThat(response.getMerchantId()).isEqualTo(merchantId);
        assertThat(response.getActive()).isTrue();
        assertThat(response.getPrefix()).isNotBlank();

        verify(apiKeyRepository).save(any(ApiKey.class));
    }

    @Test
    @DisplayName("generateApiKey - should throw ResourceNotFoundException for non-existent merchant")
    void generateApiKey_MerchantNotFound_Throws() {
        when(merchantRepository.existsById(merchantId)).thenReturn(false);

        assertThatThrownBy(() -> apiKeyService.generateApiKey(merchantId))
                .isInstanceOf(ResourceNotFoundException.class);

        verify(apiKeyRepository, never()).save(any(ApiKey.class));
    }

    @Test
    @DisplayName("validateApiKey - should return merchantId for valid active key")
    void validateApiKey_Success() {
        when(apiKeyRepository.findByKeyHash(anyString())).thenReturn(Optional.of(testApiKey));

        UUID result = apiKeyService.validateApiKey("pk_someRawKeyValue123456");

        assertThat(result).isEqualTo(merchantId);
    }

    @Test
    @DisplayName("validateApiKey - should throw ResourceNotFoundException for revoked key")
    void validateApiKey_RevokedKey_Throws() {
        ApiKey revokedKey = ApiKey.builder()
                .id(keyId)
                .keyHash("sha256-hash-revoked")
                .prefix("revk1234")
                .merchantId(merchantId)
                .active(false)
                .createdAt(Instant.now())
                .build();

        when(apiKeyRepository.findByKeyHash(anyString())).thenReturn(Optional.of(revokedKey));

        assertThatThrownBy(() -> apiKeyService.validateApiKey("pk_revokedKeyValue"))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("revoked");
    }

    @Test
    @DisplayName("revokeApiKey - should deactivate the API key")
    void revokeApiKey_Success() {
        when(apiKeyRepository.findById(keyId)).thenReturn(Optional.of(testApiKey));
        when(apiKeyRepository.save(any(ApiKey.class))).thenReturn(testApiKey);

        apiKeyService.revokeApiKey(keyId);

        assertThat(testApiKey.getActive()).isFalse();
        verify(apiKeyRepository).save(testApiKey);
    }

    @Test
    @DisplayName("revokeApiKey - should throw ResourceNotFoundException for unknown key ID")
    void revokeApiKey_NotFound_Throws() {
        UUID unknownId = UUID.randomUUID();
        when(apiKeyRepository.findById(unknownId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> apiKeyService.revokeApiKey(unknownId))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
