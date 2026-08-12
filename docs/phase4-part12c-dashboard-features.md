# Phase 4 Part 12c: Dashboard Features

## Overview

Core dashboard features: transactions listing with filters, analytics charts, and data tables with pagination. These pages give merchants visibility into their payment operations.

## Dashboard Page (Overview)

```tsx
// src/pages/dashboard/DashboardPage.tsx
import { useQuery } from '@tanstack/react-query';
import { analyticsApi } from '@/api/analytics.api';
import { RevenueChart } from '@/components/charts/RevenueChart';
import { TransactionVolume } from '@/components/charts/TransactionVolume';
import { PaymentMethodPie } from '@/components/charts/PaymentMethodPie';
import { StatCard } from '@/components/common/StatCard';

export function DashboardPage() {
  const { data: stats, isLoading } = useQuery({
    queryKey: ['dashboard-stats'],
    queryFn: () => analyticsApi.getDashboardStats(),
  });

  const { data: revenueData } = useQuery({
    queryKey: ['revenue-chart', '7d'],
    queryFn: () => analyticsApi.getRevenueTimeSeries('7d'),
  });

  if (isLoading) return <DashboardSkeleton />;

  return (
    <div className="space-y-6">
      <h1 className="text-2xl font-bold text-dark-900">Dashboard</h1>

      {/* Stat Cards */}
      <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-4">
        <StatCard
          title="Total Revenue"
          value={formatCurrency(stats.totalRevenue)}
          change={stats.revenueChange}
          icon="💰"
        />
        <StatCard
          title="Transactions"
          value={stats.totalTransactions.toLocaleString()}
          change={stats.transactionChange}
          icon="📊"
        />
        <StatCard
          title="Success Rate"
          value={`${stats.successRate}%`}
          change={stats.successRateChange}
          icon="✅"
        />
        <StatCard
          title="Avg. Ticket Size"
          value={formatCurrency(stats.avgTicketSize)}
          change={stats.avgTicketChange}
          icon="🎫"
        />
      </div>

      {/* Charts */}
      <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
        <div className="lg:col-span-2 bg-white rounded-xl p-6 shadow-sm">
          <h2 className="text-lg font-semibold mb-4">Revenue (Last 7 Days)</h2>
          <RevenueChart data={revenueData} />
        </div>
        <div className="bg-white rounded-xl p-6 shadow-sm">
          <h2 className="text-lg font-semibold mb-4">Payment Methods</h2>
          <PaymentMethodPie data={stats.methodBreakdown} />
        </div>
      </div>

      {/* Recent Transactions */}
      <div className="bg-white rounded-xl p-6 shadow-sm">
        <h2 className="text-lg font-semibold mb-4">Recent Transactions</h2>
        <RecentTransactionsTable />
      </div>
    </div>
  );
}
```

## Transactions Page

```tsx
// src/pages/transactions/TransactionsPage.tsx
import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { ordersApi } from '@/api/orders.api';
import { DataTable } from '@/components/common/Table';
import { Badge } from '@/components/common/Badge';
import { Pagination } from '@/components/common/Pagination';
import { TransactionFilters } from './TransactionFilters';
import { formatCurrency, formatDate } from '@/utils/formatters';
import type { OrderStatus } from '@/types/order.types';

interface Filters {
  status?: OrderStatus;
  dateFrom?: string;
  dateTo?: string;
  search?: string;
}

export function TransactionsPage() {
  const [page, setPage] = useState(0);
  const [filters, setFilters] = useState<Filters>({});
  const pageSize = 20;

  const { data, isLoading } = useQuery({
    queryKey: ['transactions', page, pageSize, filters],
    queryFn: () => ordersApi.listOrders({
      page,
      size: pageSize,
      ...filters,
    }),
    keepPreviousData: true,
  });

  const columns = [
    {
      header: 'Order ID',
      accessor: (row: any) => (
        <span className="font-mono text-sm text-primary-600">
          {row.id.substring(0, 8)}
        </span>
      ),
    },
    {
      header: 'Amount',
      accessor: (row: any) => (
        <span className="font-semibold">
          {formatCurrency(row.amount)}
        </span>
      ),
    },
    {
      header: 'Status',
      accessor: (row: any) => <StatusBadge status={row.status} />,
    },
    {
      header: 'Method',
      accessor: (row: any) => row.paymentMethod || '-',
    },
    {
      header: 'Customer',
      accessor: (row: any) => row.customerEmail || '-',
    },
    {
      header: 'Date',
      accessor: (row: any) => formatDate(row.createdAt),
    },
  ];

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <h1 className="text-2xl font-bold text-dark-900">Transactions</h1>
        <button className="text-sm text-primary-600 hover:text-primary-700">
          Export CSV
        </button>
      </div>

      {/* Filters */}
      <TransactionFilters filters={filters} onChange={setFilters} />

      {/* Data Table */}
      <div className="bg-white rounded-xl shadow-sm overflow-hidden">
        <DataTable
          columns={columns}
          data={data?.content || []}
          isLoading={isLoading}
          onRowClick={(row) => navigate(`/transactions/${row.id}`)}
        />

        {data && (
          <div className="border-t px-6 py-4">
            <Pagination
              currentPage={page}
              totalPages={data.totalPages}
              totalItems={data.totalElements}
              pageSize={pageSize}
              onPageChange={setPage}
            />
          </div>
        )}
      </div>
    </div>
  );
}
```

## Transaction Filters

```tsx
// src/pages/transactions/TransactionFilters.tsx
import { useState } from 'react';
import { OrderStatus } from '@/types/order.types';

const STATUS_OPTIONS: { value: OrderStatus | ''; label: string }[] = [
  { value: '', label: 'All Statuses' },
  { value: 'CAPTURED', label: 'Captured' },
  { value: 'AUTHORIZED', label: 'Authorized' },
  { value: 'FAILED', label: 'Failed' },
  { value: 'REFUNDED', label: 'Refunded' },
  { value: 'CREATED', label: 'Created' },
];

export function TransactionFilters({ filters, onChange }) {
  return (
    <div className="bg-white rounded-xl p-4 shadow-sm flex flex-wrap gap-4 items-center">
      {/* Search */}
      <input
        type="text"
        placeholder="Search by order ID, email..."
        className="flex-1 min-w-[200px] px-4 py-2 border border-dark-200 rounded-lg focus:ring-2 focus:ring-primary-500 focus:border-transparent"
        value={filters.search || ''}
        onChange={(e) => onChange({ ...filters, search: e.target.value })}
      />

      {/* Status Filter */}
      <select
        className="px-4 py-2 border border-dark-200 rounded-lg"
        value={filters.status || ''}
        onChange={(e) => onChange({ ...filters, status: e.target.value || undefined })}
      >
        {STATUS_OPTIONS.map(opt => (
          <option key={opt.value} value={opt.value}>{opt.label}</option>
        ))}
      </select>

      {/* Date Range */}
      <input
        type="date"
        className="px-4 py-2 border border-dark-200 rounded-lg"
        value={filters.dateFrom || ''}
        onChange={(e) => onChange({ ...filters, dateFrom: e.target.value })}
      />
      <span className="text-dark-700">to</span>
      <input
        type="date"
        className="px-4 py-2 border border-dark-200 rounded-lg"
        value={filters.dateTo || ''}
        onChange={(e) => onChange({ ...filters, dateTo: e.target.value })}
      />
    </div>
  );
}
```

## Analytics Charts

```tsx
// src/components/charts/RevenueChart.tsx
import { AreaChart, Area, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer } from 'recharts';
import { formatCurrency } from '@/utils/formatters';

interface RevenueDataPoint {
  date: string;
  revenue: number;
  transactions: number;
}

export function RevenueChart({ data }: { data: RevenueDataPoint[] }) {
  return (
    <ResponsiveContainer width="100%" height={300}>
      <AreaChart data={data}>
        <defs>
          <linearGradient id="colorRevenue" x1="0" y1="0" x2="0" y2="1">
            <stop offset="5%" stopColor="#22c55e" stopOpacity={0.3} />
            <stop offset="95%" stopColor="#22c55e" stopOpacity={0} />
          </linearGradient>
        </defs>
        <CartesianGrid strokeDasharray="3 3" stroke="#e2e8f0" />
        <XAxis dataKey="date" stroke="#64748b" fontSize={12} />
        <YAxis stroke="#64748b" fontSize={12}
          tickFormatter={(value) => `₹${(value / 100).toLocaleString()}`}
        />
        <Tooltip
          content={({ active, payload }) => {
            if (!active || !payload?.length) return null;
            return (
              <div className="bg-white shadow-lg rounded-lg p-3 border">
                <p className="font-semibold">{formatCurrency(payload[0].value)}</p>
                <p className="text-sm text-dark-700">
                  {payload[0].payload.transactions} transactions
                </p>
              </div>
            );
          }}
        />
        <Area
          type="monotone"
          dataKey="revenue"
          stroke="#22c55e"
          fillOpacity={1}
          fill="url(#colorRevenue)"
          strokeWidth={2}
        />
      </AreaChart>
    </ResponsiveContainer>
  );
}

// src/components/charts/PaymentMethodPie.tsx
import { PieChart, Pie, Cell, Tooltip, ResponsiveContainer } from 'recharts';

const COLORS = ['#22c55e', '#3b82f6', '#f59e0b', '#ef4444'];

export function PaymentMethodPie({ data }) {
  return (
    <ResponsiveContainer width="100%" height={250}>
      <PieChart>
        <Pie
          data={data}
          cx="50%"
          cy="50%"
          innerRadius={60}
          outerRadius={80}
          dataKey="count"
          nameKey="method"
          label={({ method, percent }) =>
            `${method} ${(percent * 100).toFixed(0)}%`
          }
        >
          {data.map((_, index) => (
            <Cell key={index} fill={COLORS[index % COLORS.length]} />
          ))}
        </Pie>
        <Tooltip />
      </PieChart>
    </ResponsiveContainer>
  );
}
```

## Data Table Component

```tsx
// src/components/common/Table.tsx
interface Column<T> {
  header: string;
  accessor: (row: T) => React.ReactNode;
  className?: string;
}

interface DataTableProps<T> {
  columns: Column<T>[];
  data: T[];
  isLoading?: boolean;
  onRowClick?: (row: T) => void;
}

export function DataTable<T>({ columns, data, isLoading, onRowClick }: DataTableProps<T>) {
  if (isLoading) {
    return <TableSkeleton columns={columns.length} rows={5} />;
  }

  if (data.length === 0) {
    return (
      <div className="text-center py-12 text-dark-700">
        <p className="text-lg">No transactions found</p>
        <p className="text-sm mt-1">Try adjusting your filters</p>
      </div>
    );
  }

  return (
    <table className="w-full">
      <thead>
        <tr className="border-b border-dark-200">
          {columns.map((col, i) => (
            <th key={i} className="px-6 py-3 text-left text-xs font-medium text-dark-700 uppercase tracking-wider">
              {col.header}
            </th>
          ))}
        </tr>
      </thead>
      <tbody className="divide-y divide-dark-100">
        {data.map((row, i) => (
          <tr
            key={i}
            onClick={() => onRowClick?.(row)}
            className={onRowClick ? 'hover:bg-dark-50 cursor-pointer' : ''}
          >
            {columns.map((col, j) => (
              <td key={j} className={`px-6 py-4 whitespace-nowrap text-sm ${col.className || ''}`}>
                {col.accessor(row)}
              </td>
            ))}
          </tr>
        ))}
      </tbody>
    </table>
  );
}
```

## Status Badge Component

```tsx
// src/components/common/Badge.tsx
const STATUS_STYLES: Record<string, string> = {
  CAPTURED: 'bg-green-100 text-green-800',
  AUTHORIZED: 'bg-blue-100 text-blue-800',
  PROCESSING: 'bg-yellow-100 text-yellow-800',
  CREATED: 'bg-gray-100 text-gray-800',
  FAILED: 'bg-red-100 text-red-800',
  REFUNDED: 'bg-purple-100 text-purple-800',
  SETTLED: 'bg-emerald-100 text-emerald-800',
};

export function StatusBadge({ status }: { status: string }) {
  const style = STATUS_STYLES[status] || 'bg-gray-100 text-gray-800';

  return (
    <span className={`inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium ${style}`}>
      {status}
    </span>
  );
}
```

## Utility Formatters

```ts
// src/utils/formatters.ts
export function formatCurrency(amountInPaise: number, currency = 'INR'): string {
  const amount = amountInPaise / 100;
  return new Intl.NumberFormat('en-IN', {
    style: 'currency',
    currency,
    minimumFractionDigits: 2,
  }).format(amount);
}

export function formatDate(isoString: string): string {
  return new Intl.DateTimeFormat('en-IN', {
    day: '2-digit',
    month: 'short',
    year: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  }).format(new Date(isoString));
}

export function truncateId(id: string, length = 8): string {
  return id.substring(0, length);
}
```
