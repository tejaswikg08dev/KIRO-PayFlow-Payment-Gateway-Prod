import {
  LineChart,
  Line,
  XAxis,
  YAxis,
  CartesianGrid,
  Tooltip,
  ResponsiveContainer,
} from 'recharts';
import { TimeSeriesPoint } from '@/types/analytics.types';
import { formatCurrency, formatShortDate } from '@/utils/formatters';

interface RevenueChartProps {
  data: TimeSeriesPoint[];
}

export function RevenueChart({ data }: RevenueChartProps) {
  return (
    <div className="card">
      <h3 className="text-lg font-semibold text-gray-900 mb-4">Revenue</h3>
      <ResponsiveContainer width="100%" height={300}>
        <LineChart data={data} margin={{ top: 5, right: 30, left: 20, bottom: 5 }}>
          <CartesianGrid strokeDasharray="3 3" stroke="#f0f0f0" />
          <XAxis
            dataKey="date"
            tickFormatter={formatShortDate}
            stroke="#6b7280"
            fontSize={12}
          />
          <YAxis
            tickFormatter={(value) => formatCurrency(value)}
            stroke="#6b7280"
            fontSize={12}
          />
          <Tooltip
            formatter={(value: number) => [formatCurrency(value), 'Revenue']}
            labelFormatter={formatShortDate}
          />
          <Line
            type="monotone"
            dataKey="value"
            stroke="#2563eb"
            strokeWidth={2}
            dot={false}
            activeDot={{ r: 6 }}
          />
        </LineChart>
      </ResponsiveContainer>
    </div>
  );
}
