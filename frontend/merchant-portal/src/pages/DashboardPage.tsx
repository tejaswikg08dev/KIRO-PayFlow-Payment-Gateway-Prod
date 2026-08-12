import { useDashboardMetrics } from '@/hooks/useAnalytics';
import { formatCurrency, formatNumber, formatPercentage } from '@/utils/formatters';
import { LoadingSpinner } from '@/components/common/LoadingSpinner';

export default function DashboardPage() {
  const { data: metrics, isLoading } = useDashboardMetrics();

  if (isLoading) {
    return (
      <div className="flex items-center justify-center h-64">
        <LoadingSpinner size="lg" />
      </div>
    );
  }

  const cards = [
    {
      title: 'Revenue Today',
      value: formatCurrency(metrics?.revenueToday || 0),
      change: metrics?.revenueChange || 0,
      icon: '💰',
    },
    {
      title: 'Total Transactions',
      value: formatNumber(metrics?.totalTransactions || 0),
      change: metrics?.transactionsChange || 0,
      icon: '💳',
    },
    {
      title: 'Success Rate',
      value: `${(metrics?.successRate || 0).toFixed(1)}%`,
      change: metrics?.successRateChange || 0,
      icon: '✅',
    },
    {
      title: 'Active Disputes',
      value: formatNumber(metrics?.activeDisputes || 0),
      change: 0,
      icon: '⚠️',
    },
  ];

  return (
    <div>
      <h2 className="text-2xl font-bold text-gray-900 mb-6">Dashboard</h2>
      <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-6 mb-8">
        {cards.map((card) => (
          <div key={card.title} className="card">
            <div className="flex items-center justify-between mb-2">
              <span className="text-sm text-gray-500">{card.title}</span>
              <span className="text-2xl">{card.icon}</span>
            </div>
            <div className="text-2xl font-bold text-gray-900">{card.value}</div>
            {card.change !== 0 && (
              <div
                className={`text-sm mt-1 ${
                  card.change > 0 ? 'text-green-600' : 'text-red-600'
                }`}
              >
                {formatPercentage(card.change)} from yesterday
              </div>
            )}
          </div>
        ))}
      </div>
    </div>
  );
}
