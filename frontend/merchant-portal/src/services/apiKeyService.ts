import apiClient from './apiClient';
import { ApiKey, ApiKeyCreateRequest, ApiKeyCreateResponse } from '@/types/merchant.types';

export const apiKeyService = {
  async listKeys(): Promise<ApiKey[]> {
    const response = await apiClient.get<ApiKey[]>('/api/merchants/api-keys');
    return response.data;
  },

  async generateKey(data: ApiKeyCreateRequest): Promise<ApiKeyCreateResponse> {
    const response = await apiClient.post<ApiKeyCreateResponse>(
      '/api/merchants/api-keys',
      data
    );
    return response.data;
  },

  async revokeKey(keyId: string): Promise<void> {
    await apiClient.delete(`/api/merchants/api-keys/${keyId}`);
  },
};
