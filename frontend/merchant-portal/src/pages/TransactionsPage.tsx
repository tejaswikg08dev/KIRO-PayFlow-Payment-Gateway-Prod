import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useTransactions } from '@/hooks/useTransactions';
import { usePagination } from '@/hooks/usePagination';
import { DataTable } from '@/components/common/DataTable';
import { Pagination } from '@/components/common/Pagination';
import { StatusBadge } from '@/components/common/StatusBadge';
import { formatCurrency, formatDate } from '@/utils/formatters';
import { Payment, PaymentStatus, PaymentMethod } from '@/types/payment.types';
import { PAYMENT_STATUSES, PAYMENT_METHODS } from '@/utils/constants';

export default function TransactionsPage() {
  const navigate = useNavigate();
  const { page, size, goToPage } = usePagination();
  const [statusFilter, setStatusFilter] = useState<PaymentStatus | ''>('');
  const [methodFilter, setMethodFilter] = useState<PaymentMethod | ''>('');
  const [startDate, setStartDate] = useState('');
  const [endDate, setEndDate] = useState('');

  const { data, isLoading } = useTransactions({
    page,
    size,
    status: statusFilter || undefined,
    method: methodFilter || undefined,
    startDate: startDate || undefined,
    endDate: endDate || undefined,
  });

  const columns = [
    {
      key: 'id',
      header: 'Payment ID',
      sortable: true,
      render: (payment: Payment) => (
        <span className="font-mono text-xs">{payment.id.substring(0, 12)}...</span>
      ),
    },
    {
      key: 'amount',
      header: 'Amount',
      sortable: true,
      render: (payment: Payment) => formatCurrency(payment.amount, payment.currency),
    },
    {
      key: 'method',
      header: 'Method',
      render: (payment: Payment) => payment.method.replace('_', ' '),
    },
    {
      key: 'status',
      header: 'Status',
      render: (payment: Payment) => <StatusBadge status={payment.status} />,
    },
    {
      key: 'createdAt',
      header: 'Date',
      sortable: true,
      render: (payment: Payment) => formatDate(payment.createdAt),
    },
  ];

  return (
    <div>
      <h2 className="text-2xl font-bold text-gray-900 mb-6">Transactions</h2>

      <div className="card mb-6">
        <div className="flex flex-wrap gap-4">
          <select
            value={statusFilter}
            onChange={(e) => setStatusFilter(e.target.value as PaymentStatus | '')}
            className="input-field w-auto"
          >
            <option value="">All Statuses</option>
            {PAYMENT_STATUSES.map((status) => (
              <option key={status} value={status}>
                {status.replace(/_/g, ' ')}
              </option>
            ))}
          </select>

          <select
            value={methodFilter}
            onChange={(e) => setMethodFilter(e.target.value as PaymentMethod | '')}
            className="input-field w-auto"
          >
            <option value="">All Methods</option>
            {PAYMENT_METHODS.map((method) => (
              <option key={method} value={method}>
                {method.replace('_', ' ')}
              </option>
            ))}
          </select>

          <input
            type="date"
            value={startDate}
            onChange={(e) => setStartDate(e.target.value)}
            className="input-field w-auto"
            placeholder="Start date"
          />
          <input
            type="date"
            value={endDate}
            onChange={(e) => setEndDate(e.target.value)}
            className="input-field w-auto"
            placeholder="End date"
          />
        </div>
      </div>

      <div className="card">
        <DataTable
          columns={columns}
          data={data?.content || []}
          isLoading={isLoading}
          emptyMessage="No transactions found"
          onRowClick={(payment) => navigate(`/transactions/${payment.id}`)}
        />
        {data && (
          <Pagination
            currentPage={page}
            totalPages={data.totalPages}
            onPageChange={goToPage}
          />
        )}
      </div>
    </div>
  );
}
