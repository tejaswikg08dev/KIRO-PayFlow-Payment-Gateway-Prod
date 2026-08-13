import apiClient from './apiClient';
import { ApiKey, ApiKeyCreateRequest, ApiKeyCreateResponse } from '@/types/merchant.types';
import { authService } from './authService';

export const apiKeyService = {
  async listKeys(): Promise<ApiKey[]> {
    const merchantId = authService.getMerchantId();
    if (!merchantId) return [];
    const response = await apiClient.get<ApiKey[]>(`/v1/merchants/${merchantId}/api-keys`);
    return response.data;
  },

  async generateKey(data: ApiKeyCreateRequest): Promise<ApiKeyCreateResponse> {
    const merchantId = authService.getMerchantId();
    if (!merchantId) throw new Error('No merchant profile found');
    const response = await apiClient.post<ApiKeyCreateResponse>(
      `/v1/merchants/${merchantId}/api-keys`,
      data
    );
    return response.data;
  },

  async revokeKey(keyId: string): Promise<void> {
    const merchantId = authService.getMerchantId();
    if (!merchantId) throw new Error('No merchant profile found');
    await apiClient.delete(`/v1/merchants/${merchantId}/api-keys/${keyId}`);
  },
};
