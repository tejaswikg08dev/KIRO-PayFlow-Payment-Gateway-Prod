import { PieChart, Pie, Cell, Tooltip, ResponsiveContainer, Legend } from 'recharts';
import { PaymentMethodDistribution } from '@/types/analytics.types';

interface PaymentMethodChartProps {
  data: PaymentMethodDistribution[];
}

const COLORS = ['#2563eb', '#7c3aed', '#059669', '#d97706', '#dc2626'];

export function PaymentMethodChart({ data }: PaymentMethodChartProps) {
  return (
    <div className="card">
      <h3 className="text-lg font-semibold text-gray-900 mb-4">Payment Methods</h3>
      <ResponsiveContainer width="100%" height={300}>
        <PieChart>
          <Pie
            data={data}
            cx="50%"
            cy="50%"
            innerRadius={60}
            outerRadius={100}
            paddingAngle={5}
            dataKey="count"
            nameKey="method"
            label={({ method, percentage }) => `${method} (${percentage}%)`}
          >
            {data.map((_entry, index) => (
              <Cell key={`cell-${index}`} fill={COLORS[index % COLORS.length]} />
            ))}
          </Pie>
          <Tooltip
            formatter={(value: number, name: string) => [value, name]}
          />
          <Legend />
        </PieChart>
      </ResponsiveContainer>
    </div>
  );
}
