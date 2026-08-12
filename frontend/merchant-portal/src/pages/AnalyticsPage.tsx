import { useState } from 'react';
import { useRevenue, useVolume, useSuccessRate, usePaymentMethodDistribution } from '@/hooks/useAnalytics';
import { RevenueChart } from '@/components/charts/RevenueChart';
import { VolumeChart } from '@/components/charts/VolumeChart';
import { SuccessRateChart } from '@/components/charts/SuccessRateChart';
import { PaymentMethodChart } from '@/components/charts/PaymentMethodChart';
import { LoadingSpinner } from '@/components/common/LoadingSpinner';
import { AnalyticsFilter } from '@/types/analytics.types';

export default function AnalyticsPage() {
  const [filter, setFilter] = useState<AnalyticsFilter>({ period: '30d' });

  const { data: revenue, isLoading: revenueLoading } = useRevenue(filter);
  const { data: volume, isLoading: volumeLoading } = useVolume(filter);
  const { data: successRate, isLoading: successRateLoading } = useSuccessRate(filter);
  const { data: paymentMethods, isLoading: methodsLoading } = usePaymentMethodDistribution(filter);

  const isLoading = revenueLoading || volumeLoading || successRateLoading || methodsLoading;

  return (
    <div>
      <div className="flex items-center justify-between mb-6">
        <h2 className="text-2xl font-bold text-gray-900">Analytics</h2>
        <div className="flex gap-2">
          {(['7d', '30d', '90d', '12m'] as const).map((period) => (
            <button
              key={period}
              onClick={() => setFilter({ period })}
              className={`px-3 py-1.5 text-sm rounded-lg transition-colors ${
                filter.period === period
                  ? 'bg-primary-600 text-white'
                  : 'bg-white text-gray-600 border border-gray-300 hover:bg-gray-50'
              }`}
            >
              {period === '12m' ? '12 Months' : `${period.replace('d', '')} Days`}
            </button>
          ))}
        </div>
      </div>

      {isLoading ? (
        <div className="flex items-center justify-center h-64">
          <LoadingSpinner size="lg" />
        </div>
      ) : (
        <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
          {revenue && <RevenueChart data={revenue.timeSeries} />}
          {volume && <VolumeChart data={volume.timeSeries} />}
          {successRate && <SuccessRateChart data={successRate.timeSeries} />}
          {paymentMethods && <PaymentMethodChart data={paymentMethods} />}
        </div>
      )}
    </div>
  );
}
