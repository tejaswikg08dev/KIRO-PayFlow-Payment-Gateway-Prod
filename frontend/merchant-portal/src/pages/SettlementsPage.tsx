import { useQuery } from '@tanstack/react-query';
import { settlementService } from '@/services/settlementService';
import { usePagination } from '@/hooks/usePagination';
import { DataTable } from '@/components/common/DataTable';
import { Pagination } from '@/components/common/Pagination';
import { StatusBadge } from '@/components/common/StatusBadge';
import { formatCurrency, formatDate, formatNumber } from '@/utils/formatters';
import { Settlement } from '@/types/analytics.types';

export default function SettlementsPage() {
  const { page, size, goToPage } = usePagination();

  const { data, isLoading } = useQuery({
    queryKey: ['settlements', page, size],
    queryFn: () => settlementService.getSettlements(page, size),
  });

  const columns = [
    {
      key: 'id',
      header: 'Settlement ID',
      render: (settlement: Settlement) => (
        <span className="font-mono text-xs">{settlement.id.substring(0, 12)}...</span>
      ),
    },
    {
      key: 'amount',
      header: 'Amount',
      sortable: true,
      render: (settlement: Settlement) => formatCurrency(settlement.amount, settlement.currency),
    },
    {
      key: 'transactions',
      header: 'Transactions',
      render: (settlement: Settlement) => formatNumber(settlement.transactionCount),
    },
    {
      key: 'status',
      header: 'Status',
      render: (settlement: Settlement) => <StatusBadge status={settlement.status} />,
    },
    {
      key: 'bankAccount',
      header: 'Bank Account',
      render: (settlement: Settlement) => settlement.bankAccount,
    },
    {
      key: 'createdAt',
      header: 'Date',
      sortable: true,
      render: (settlement: Settlement) => formatDate(settlement.createdAt),
    },
  ];

  return (
    <div>
      <h2 className="text-2xl font-bold text-gray-900 mb-6">Settlements</h2>

      <div className="card">
        <DataTable
          columns={columns}
          data={data?.content || []}
          isLoading={isLoading}
          emptyMessage="No settlements yet"
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
