import apiClient from './apiClient';
import { Merchant } from '@/types/merchant.types';
import { authService } from './authService';

export const merchantService = {
  async getProfile(): Promise<Merchant | null> {
    const merchantId = authService.getMerchantId();
    if (!merchantId) return null;
    const response = await apiClient.get(`/v1/merchants/${merchantId}`);
    return response.data.data;
  },

  async updateProfile(data: Partial<Merchant>): Promise<Merchant> {
    const merchantId = authService.getMerchantId();
    if (!merchantId) throw new Error('No merchant profile found');
    const response = await apiClient.put(`/v1/merchants/${merchantId}`, data);
    return response.data.data;
  },
};
