import apiClient from './apiClient';
import { Settlement } from '@/types/analytics.types';
import { PaginatedResponse } from '@/types/payment.types';

export const settlementService = {
  async getSettlements(
    page: number = 0,
    size: number = 20
  ): Promise<PaginatedResponse<Settlement>> {
    const response = await apiClient.get<PaginatedResponse<Settlement>>(
      '/v1/settlements',
      { params: { page, size } }
    );
    return response.data;
  },

  async getSettlement(id: string): Promise<Settlement> {
    const response = await apiClient.get<Settlement>(`/v1/settlements/${id}`);
    return response.data;
  },

  async getPayouts(): Promise<Settlement[]> {
    const response = await apiClient.get<Settlement[]>('/v1/settlements/payouts');
    return response.data;
  },
};
