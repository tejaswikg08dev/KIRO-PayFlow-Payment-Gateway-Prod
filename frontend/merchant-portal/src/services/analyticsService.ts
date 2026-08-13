import apiClient from './apiClient';
import {
  RevenueData,
  VolumeData,
  SuccessRateData,
  PaymentMethodDistribution,
  DashboardMetrics,
  AnalyticsFilter,
} from '@/types/analytics.types';

export const analyticsService = {
  async getDashboardMetrics(): Promise<DashboardMetrics> {
    const response = await apiClient.get<DashboardMetrics>('/v1/analytics/dashboard');
    return response.data;
  },

  async getRevenue(filter: AnalyticsFilter): Promise<RevenueData> {
    const response = await apiClient.get<RevenueData>('/v1/analytics/revenue', {
      params: filter,
    });
    return response.data;
  },

  async getVolume(filter: AnalyticsFilter): Promise<VolumeData> {
    const response = await apiClient.get<VolumeData>('/v1/analytics/volume', {
      params: filter,
    });
    return response.data;
  },

  async getSuccessRate(filter: AnalyticsFilter): Promise<SuccessRateData> {
    const response = await apiClient.get<SuccessRateData>('/v1/analytics/success-rate', {
      params: filter,
    });
    return response.data;
  },

  async getPaymentMethodDistribution(
    filter: AnalyticsFilter
  ): Promise<PaymentMethodDistribution[]> {
    const response = await apiClient.get<PaymentMethodDistribution[]>(
      '/v1/analytics/payment-methods',
      { params: filter }
    );
    return response.data;
  },
};
