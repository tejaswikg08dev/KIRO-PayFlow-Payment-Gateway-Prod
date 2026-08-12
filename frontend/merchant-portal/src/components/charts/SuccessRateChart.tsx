import {
  AreaChart,
  Area,
  XAxis,
  YAxis,
  CartesianGrid,
  Tooltip,
  ResponsiveContainer,
} from 'recharts';
import { TimeSeriesPoint } from '@/types/analytics.types';
import { formatShortDate } from '@/utils/formatters';

interface SuccessRateChartProps {
  data: TimeSeriesPoint[];
}

export function SuccessRateChart({ data }: SuccessRateChartProps) {
  return (
    <div className="card">
      <h3 className="text-lg font-semibold text-gray-900 mb-4">Success Rate</h3>
      <ResponsiveContainer width="100%" height={300}>
        <AreaChart data={data} margin={{ top: 5, right: 30, left: 20, bottom: 5 }}>
          <CartesianGrid strokeDasharray="3 3" stroke="#f0f0f0" />
          <XAxis
            dataKey="date"
            tickFormatter={formatShortDate}
            stroke="#6b7280"
            fontSize={12}
          />
          <YAxis
            domain={[0, 100]}
            tickFormatter={(value) => `${value}%`}
            stroke="#6b7280"
            fontSize={12}
          />
          <Tooltip
            formatter={(value: number) => [`${value.toFixed(1)}%`, 'Success Rate']}
            labelFormatter={formatShortDate}
          />
          <defs>
            <linearGradient id="successGradient" x1="0" y1="0" x2="0" y2="1">
              <stop offset="5%" stopColor="#059669" stopOpacity={0.3} />
              <stop offset="95%" stopColor="#059669" stopOpacity={0} />
            </linearGradient>
          </defs>
          <Area
            type="monotone"
            dataKey="value"
            stroke="#059669"
            strokeWidth={2}
            fill="url(#successGradient)"
          />
        </AreaChart>
      </ResponsiveContainer>
    </div>
  );
}
