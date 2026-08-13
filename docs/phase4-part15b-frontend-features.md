# Phase 4 · Part 15B — Frontend Feature Pages

| Field | Value |
|-------|-------|
| **Project** | PayFlow Payment Gateway |
| **Phase** | 4 — Frontend Development |
| **Part** | 15B — Feature Pages (Dashboard, Transactions, Analytics) |
| **Previous** | [Part 15A — Frontend Setup & Auth](phase4-part15a-frontend-setup.md) |
| **Next** | [Part 15C — Settings Pages](phase4-part15c-frontend-settings.md) |
| **Time** | ~3 hours |
| **Difficulty** | ★★★☆☆ Intermediate |
| **Prerequisites** | React basics, TanStack Query, Recharts, TailwindCSS |

---

## Table of Contents

1. [Dashboard Page](#1-dashboard-page)
2. [Transactions Page](#2-transactions-page)
3. [Analytics Page](#3-analytics-page)
4. [Transaction Detail Page](#4-transaction-detail-page)
5. [TanStack Query Hooks](#5-tanstack-query-hooks)
6. [What You Learned](#what-you-learned)
7. [Common Errors & Fixes](#common-errors--fixes)

---

## 1. Dashboard Page

The dashboard is the merchant's landing page after login — a quick pulse on revenue and activity.

```
+------------------------------------------------------------------+
|  PAYFLOW DASHBOARD                               [Profile] [Logout]|
+------------------------------------------------------------------+
|                                                                    |
|  +------------+  +------------+  +------------+  +------------+   |
|  | Total Rev  |  | Today Rev  |  | Success %  |  | Txn Count  |   |
|  | ₹12,45,000 |  | ₹1,23,400  |  |   96.5%    |  |   3,412    |   |
|  | +12% ↑     |  | +8% ↑      |  |  -0.5% ↓   |  |  +15% ↑    |   |
|  +------------+  +------------+  +------------+  +------------+   |
|                                                                    |
|  +------------------------------+  +---------------------------+  |
|  |   Revenue Chart (7 days)     |  |  Recent Transactions      |  |
|  |   ___    ___                 |  |  #TXN001  ₹500  SUCCESS   |  |
|  |  /   \  /   \               |  |  #TXN002  ₹200  PENDING   |  |
|  | /     \/     \__            |  |  #TXN003  ₹1500 FAILED    |  |
|  +------------------------------+  +---------------------------+  |
+------------------------------------------------------------------+
```

### Revenue Stat Cards

```tsx
// src/pages/Dashboard.tsx
import { useAnalytics } from '@/hooks/useAnalytics';
import { StatCard } from '@/components/ui/StatCard';
import { RevenueChart } from '@/components/charts/RevenueChart';
import { RecentTransactions } from '@/components/dashboard/RecentTransactions';

export function Dashboard() {
  const { data: stats, isLoading } = useAnalytics('dashboard-stats');

  if (isLoading) return <DashboardSkeleton />;

  return (
    <div className="p-6 space-y-6">
      <h1 className="text-2xl font-bold text-gray-900">Dashboard</h1>

      {/* Revenue Cards */}
      <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-4">
        <StatCard
          title="Total Revenue"
          value={formatCurrency(stats.totalRevenue)}
          change={stats.revenueChange}
          icon={<CurrencyRupeeIcon />}
        />
        <StatCard
          title="Today's Revenue"
          value={formatCurrency(stats.todayRevenue)}
          change={stats.todayChange}
          icon={<CalendarIcon />}
        />
        <StatCard
          title="Success Rate"
          value={`${stats.successRate}%`}
          change={stats.successRateChange}
          icon={<CheckCircleIcon />}
        />
        <StatCard
          title="Transactions"
          value={stats.transactionCount.toLocaleString()}
          change={stats.txnCountChange}
          icon={<ArrowsRightLeftIcon />}
        />
      </div>

      {/* Charts + Recent Activity */}
      <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
        <div className="lg:col-span-2">
          <RevenueChart data={stats.revenueTimeline} />
        </div>
        <RecentTransactions transactions={stats.recentTransactions} />
      </div>
    </div>
  );
}
```

---

## 2. Transactions Page

The transactions page provides a filterable DataTable with status badges, search, and pagination.

### DataTable with Filters

```tsx
// src/pages/Transactions.tsx
import { useTransactions } from '@/hooks/useTransactions';
import { DataTable } from '@/components/ui/DataTable';
import { StatusBadge } from '@/components/ui/StatusBadge';
import { DateRangePicker } from '@/components/ui/DateRangePicker';

export function TransactionsPage() {
  const [filters, setFilters] = useState({
    status: '',
    method: '',
    dateFrom: '',
    dateTo: '',
    search: '',
    page: 0,
    size: 20,
  });

  const { data, isLoading } = useTransactions(filters);

  const columns = [
    { header: 'Order ID', accessor: 'orderId' },
    { header: 'Amount', accessor: 'amount', cell: (row) => formatCurrency(row.amount) },
    { header: 'Method', accessor: 'paymentMethod' },
    {
      header: 'Status',
      accessor: 'status',
      cell: (row) => <StatusBadge status={row.status} />,
    },
    { header: 'Date', accessor: 'createdAt', cell: (row) => formatDate(row.createdAt) },
    { header: 'Actions', cell: (row) => <Link to={`/transactions/${row.id}`}>View</Link> },
  ];

  return (
    <div className="p-6 space-y-4">
      <h1 className="text-2xl font-bold">Transactions</h1>

      {/* Filters */}
      <div className="flex flex-wrap gap-3 items-center">
        <input
          type="text"
          placeholder="Search by Order ID..."
          className="input input-bordered w-64"
          onChange={(e) => setFilters(f => ({ ...f, search: e.target.value }))}
        />
        <select onChange={(e) => setFilters(f => ({ ...f, status: e.target.value }))}>
          <option value="">All Status</option>
          <option value="AUTHORIZED">Authorized</option>
          <option value="CAPTURED">Captured</option>
          <option value="FAILED">Failed</option>
          <option value="REFUNDED">Refunded</option>
        </select>
        <DateRangePicker onChange={(from, to) => setFilters(f => ({ ...f, dateFrom: from, dateTo: to }))} />
      </div>

      <DataTable columns={columns} data={data?.content} loading={isLoading} />

      {/* Pagination */}
      <Pagination
        page={data?.page}
        totalPages={data?.totalPages}
        onPageChange={(p) => setFilters(f => ({ ...f, page: p }))}
      />
    </div>
  );
}
```

### Status Badge Component

```tsx
// src/components/ui/StatusBadge.tsx
const statusStyles = {
  CREATED:    'bg-gray-100 text-gray-700',
  AUTHORIZED: 'bg-blue-100 text-blue-700',
  CAPTURED:   'bg-green-100 text-green-700',
  FAILED:     'bg-red-100 text-red-700',
  REFUNDED:   'bg-yellow-100 text-yellow-700',
  SETTLED:    'bg-purple-100 text-purple-700',
};

export function StatusBadge({ status }: { status: string }) {
  return (
    <span className={`px-2 py-1 rounded-full text-xs font-medium ${statusStyles[status]}`}>
      {status}
    </span>
  );
}
```

---

## 3. Analytics Page

Uses Recharts to render interactive charts — LineChart, BarChart, PieChart, AreaChart.

```tsx
// src/pages/Analytics.tsx
import { LineChart, Line, BarChart, Bar, PieChart, Pie, AreaChart, Area,
         XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer, Cell } from 'recharts';
import { useAnalytics } from '@/hooks/useAnalytics';

export function AnalyticsPage() {
  const { data: analytics } = useAnalytics('full');
  const COLORS = ['#4F46E5', '#10B981', '#F59E0B', '#EF4444'];

  return (
    <div className="p-6 space-y-6">
      <h1 className="text-2xl font-bold">Analytics</h1>

      <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
        {/* Revenue Trend — Line Chart */}
        <div className="bg-white p-4 rounded-lg shadow">
          <h3 className="font-semibold mb-4">Revenue Trend (30 days)</h3>
          <ResponsiveContainer width="100%" height={300}>
            <LineChart data={analytics.revenueTrend}>
              <CartesianGrid strokeDasharray="3 3" />
              <XAxis dataKey="date" />
              <YAxis />
              <Tooltip />
              <Line type="monotone" dataKey="revenue" stroke="#4F46E5" strokeWidth={2} />
            </LineChart>
          </ResponsiveContainer>
        </div>

        {/* Payment Methods — Pie Chart */}
        <div className="bg-white p-4 rounded-lg shadow">
          <h3 className="font-semibold mb-4">Payment Methods</h3>
          <ResponsiveContainer width="100%" height={300}>
            <PieChart>
              <Pie data={analytics.methodBreakdown} dataKey="count" nameKey="method"
                   cx="50%" cy="50%" outerRadius={100} label>
                {analytics.methodBreakdown.map((_, i) => (
                  <Cell key={i} fill={COLORS[i % COLORS.length]} />
                ))}
              </Pie>
              <Tooltip />
            </PieChart>
          </ResponsiveContainer>
        </div>

        {/* Daily Volume — Bar Chart */}
        <div className="bg-white p-4 rounded-lg shadow">
          <h3 className="font-semibold mb-4">Daily Transaction Volume</h3>
          <ResponsiveContainer width="100%" height={300}>
            <BarChart data={analytics.dailyVolume}>
              <CartesianGrid strokeDasharray="3 3" />
              <XAxis dataKey="date" />
              <YAxis />
              <Tooltip />
              <Bar dataKey="count" fill="#10B981" radius={[4, 4, 0, 0]} />
            </BarChart>
          </ResponsiveContainer>
        </div>

        {/* Success Rate — Area Chart */}
        <div className="bg-white p-4 rounded-lg shadow">
          <h3 className="font-semibold mb-4">Success Rate Over Time</h3>
          <ResponsiveContainer width="100%" height={300}>
            <AreaChart data={analytics.successRateTrend}>
              <CartesianGrid strokeDasharray="3 3" />
              <XAxis dataKey="date" />
              <YAxis domain={[80, 100]} />
              <Tooltip />
              <Area type="monotone" dataKey="rate" stroke="#F59E0B" fill="#FEF3C7" />
            </AreaChart>
          </ResponsiveContainer>
        </div>
      </div>
    </div>
  );
}
```

---

## 4. Transaction Detail Page

Shows full payment lifecycle with a visual timeline.

```tsx
// src/pages/TransactionDetail.tsx
import { useParams } from 'react-router-dom';
import { useTransaction } from '@/hooks/useTransactions';
import { PaymentTimeline } from '@/components/transactions/PaymentTimeline';

export function TransactionDetail() {
  const { id } = useParams();
  const { data: txn, isLoading } = useTransaction(id!);

  if (isLoading) return <Spinner />;

  return (
    <div className="p-6 max-w-4xl mx-auto space-y-6">
      <h1 className="text-2xl font-bold">Transaction #{txn.orderId}</h1>

      {/* Summary Card */}
      <div className="bg-white p-6 rounded-lg shadow grid grid-cols-2 md:grid-cols-4 gap-4">
        <InfoField label="Amount" value={formatCurrency(txn.amount)} />
        <InfoField label="Status" value={<StatusBadge status={txn.status} />} />
        <InfoField label="Method" value={txn.paymentMethod} />
        <InfoField label="Created" value={formatDateTime(txn.createdAt)} />
      </div>

      {/* Payment Timeline */}
      <PaymentTimeline events={txn.timeline} />

      {/* Raw Details */}
      <div className="bg-white p-6 rounded-lg shadow">
        <h3 className="font-semibold mb-2">Payment Details</h3>
        <pre className="bg-gray-50 p-4 rounded text-sm overflow-x-auto">
          {JSON.stringify(txn.metadata, null, 2)}
        </pre>
      </div>
    </div>
  );
}
```

### Payment Timeline Component

```tsx
// src/components/transactions/PaymentTimeline.tsx
export function PaymentTimeline({ events }) {
  return (
    <div className="bg-white p-6 rounded-lg shadow">
      <h3 className="font-semibold mb-4">Payment Timeline</h3>
      <ol className="relative border-l border-gray-200 ml-3">
        {events.map((event, idx) => (
          <li key={idx} className="mb-6 ml-6">
            <span className={`absolute -left-3 w-6 h-6 rounded-full flex items-center justify-center
              ${event.success ? 'bg-green-100 text-green-600' : 'bg-red-100 text-red-600'}`}>
              {event.success ? '✓' : '✗'}
            </span>
            <h4 className="font-medium">{event.action}</h4>
            <time className="text-sm text-gray-500">{formatDateTime(event.timestamp)}</time>
            {event.message && <p className="text-sm text-gray-600 mt-1">{event.message}</p>}
          </li>
        ))}
      </ol>
    </div>
  );
}
```

---

## 5. TanStack Query Hooks

All API calls use TanStack Query (React Query) for caching, background refetching, and stale-while-revalidate.

```tsx
// src/hooks/useTransactions.ts
import { useQuery } from '@tanstack/react-query';
import { apiClient } from '@/lib/apiClient';

export function useTransactions(filters: TransactionFilters) {
  return useQuery({
    queryKey: ['transactions', filters],
    queryFn: () => apiClient.get('/v1/payments/transactions', { params: filters }),
    keepPreviousData: true,  // smooth pagination
    staleTime: 30_000,       // 30 seconds before refetch
  });
}

export function useTransaction(id: string) {
  return useQuery({
    queryKey: ['transaction', id],
    queryFn: () => apiClient.get(`/v1/payments/transactions/${id}`),
    enabled: !!id,
  });
}

// src/hooks/useAnalytics.ts
export function useAnalytics(type: 'dashboard-stats' | 'full') {
  return useQuery({
    queryKey: ['analytics', type],
    queryFn: () => apiClient.get(`/v1/analytics/${type}`),
    staleTime: 60_000,       // analytics data cached 1 minute
    refetchOnWindowFocus: false,
  });
}
```

---

## What You Learned

| # | Topic | Key Takeaway |
|---|-------|-------------|
| 1 | Dashboard layout | Grid-based stat cards provide at-a-glance metrics |
| 2 | DataTable + filters | Server-side pagination keeps UI fast on large datasets |
| 3 | Status badges | Color-coded badges make status instantly scannable |
| 4 | Recharts | Four chart types cover most analytics visualization needs |
| 5 | Payment timeline | Visual timeline shows the full payment lifecycle |
| 6 | TanStack Query | queryKey-based caching eliminates redundant API calls |
| 7 | Responsive design | Tailwind grid utilities adapt to mobile/tablet/desktop |

---

## Common Errors & Fixes

| Error | Cause | Fix |
|-------|-------|-----|
| `useQuery` returns `undefined` data | Query hasn't resolved yet | Add `isLoading` check before rendering |
| Chart renders with 0 width | Parent container has no explicit height | Wrap `ResponsiveContainer` in fixed-height div |
| Filters don't reset page | Changing filter without resetting `page` to 0 | Reset page in filter handler: `setFilters(f => ({...f, status: val, page: 0}))` |
| Stale data after mutation | Query cache not invalidated | Call `queryClient.invalidateQueries(['transactions'])` after mutation |
| StatusBadge shows undefined | Backend returns lowercase status | Normalize: `status.toUpperCase()` before lookup |

---

<div align="center">

**[← Part 15A: Frontend Setup](phase4-part15a-frontend-setup.md)** | **[Documentation Index](../README.md)** | **[Part 15C: Settings Pages →](phase4-part15c-frontend-settings.md)**

</div>
