export interface TimeSeriesPoint {
  date: string;
  value: number;
}

export interface RevenueData {
  totalRevenue: number;
  revenueChange: number;
  timeSeries: TimeSeriesPoint[];
}

export interface VolumeData {
  totalTransactions: number;
  volumeChange: number;
  timeSeries: TimeSeriesPoint[];
}

export interface SuccessRateData {
  successRate: number;
  rateChange: number;
  timeSeries: TimeSeriesPoint[];
}

export interface PaymentMethodDistribution {
  method: string;
  count: number;
  amount: number;
  percentage: number;
}

export interface ChartData {
  revenue: RevenueData;
  volume: VolumeData;
  successRate: SuccessRateData;
  paymentMethods: PaymentMethodDistribution[];
}

export interface AnalyticsFilter {
  period: '7d' | '30d' | '90d' | '12m';
  startDate?: string;
  endDate?: string;
}

export interface DashboardMetrics {
  revenueToday: number;
  revenueChange: number;
  totalTransactions: number;
  transactionsChange: number;
  successRate: number;
  successRateChange: number;
  activeDisputes: number;
}

export interface Settlement {
  id: string;
  amount: number;
  currency: string;
  status: 'PENDING' | 'PROCESSING' | 'SETTLED' | 'FAILED';
  transactionCount: number;
  settledAt?: string;
  createdAt: string;
  bankAccount: string;
}
