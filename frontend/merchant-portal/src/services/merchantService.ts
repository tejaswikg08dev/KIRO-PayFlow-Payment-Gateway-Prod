import apiClient from './apiClient';
import { Merchant } from '@/types/merchant.types';

export const merchantService = {
  async getProfile(): Promise<Merchant> {
    const response = await apiClient.get<Merchant>('/api/merchants/profile');
    return response.data;
  },

  async updateProfile(data: Partial<Merchant>): Promise<Merchant> {
    const response = await apiClient.put<Merchant>('/api/merchants/profile', data);
    return response.data;
  },
};
