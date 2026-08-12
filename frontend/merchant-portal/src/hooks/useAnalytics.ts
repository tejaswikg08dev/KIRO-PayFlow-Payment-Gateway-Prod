import { useQuery } from '@tanstack/react-query';
import { analyticsService } from '@/services/analyticsService';
import { AnalyticsFilter } from '@/types/analytics.types';

export function useDashboardMetrics() {
  return useQuery({
    queryKey: ['dashboardMetrics'],
    queryFn: () => analyticsService.getDashboardMetrics(),
    refetchInterval: 60000,
  });
}

export function useRevenue(filter: AnalyticsFilter) {
  return useQuery({
    queryKey: ['revenue', filter],
    queryFn: () => analyticsService.getRevenue(filter),
  });
}

export function useVolume(filter: AnalyticsFilter) {
  return useQuery({
    queryKey: ['volume', filter],
    queryFn: () => analyticsService.getVolume(filter),
  });
}

export function useSuccessRate(filter: AnalyticsFilter) {
  return useQuery({
    queryKey: ['successRate', filter],
    queryFn: () => analyticsService.getSuccessRate(filter),
  });
}

export function usePaymentMethodDistribution(filter: AnalyticsFilter) {
  return useQuery({
    queryKey: ['paymentMethods', filter],
    queryFn: () => analyticsService.getPaymentMethodDistribution(filter),
  });
}
