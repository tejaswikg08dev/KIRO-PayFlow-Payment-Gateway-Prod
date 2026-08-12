import {
  BarChart,
  Bar,
  XAxis,
  YAxis,
  CartesianGrid,
  Tooltip,
  ResponsiveContainer,
} from 'recharts';
import { TimeSeriesPoint } from '@/types/analytics.types';
import { formatShortDate, formatNumber } from '@/utils/formatters';

interface VolumeChartProps {
  data: TimeSeriesPoint[];
}

export function VolumeChart({ data }: VolumeChartProps) {
  return (
    <div className="card">
      <h3 className="text-lg font-semibold text-gray-900 mb-4">Transaction Volume</h3>
      <ResponsiveContainer width="100%" height={300}>
        <BarChart data={data} margin={{ top: 5, right: 30, left: 20, bottom: 5 }}>
          <CartesianGrid strokeDasharray="3 3" stroke="#f0f0f0" />
          <XAxis
            dataKey="date"
            tickFormatter={formatShortDate}
            stroke="#6b7280"
            fontSize={12}
          />
          <YAxis
            tickFormatter={formatNumber}
            stroke="#6b7280"
            fontSize={12}
          />
          <Tooltip
            formatter={(value: number) => [formatNumber(value), 'Transactions']}
            labelFormatter={formatShortDate}
          />
          <Bar dataKey="value" fill="#3b82f6" radius={[4, 4, 0, 0]} />
        </BarChart>
      </ResponsiveContainer>
    </div>
  );
}
