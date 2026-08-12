import { useParams } from 'react-router-dom';
import { useTransaction } from '@/hooks/useTransactions';
import { LoadingSpinner } from '@/components/common/LoadingSpinner';
import { StatusBadge } from '@/components/common/StatusBadge';
import { formatCurrency, formatDate, maskCard } from '@/utils/formatters';

export default function TransactionDetailPage() {
  const { id } = useParams<{ id: string }>();
  const { data: transaction, isLoading } = useTransaction(id!);

  if (isLoading) {
    return (
      <div className="flex items-center justify-center h-64">
        <LoadingSpinner size="lg" />
      </div>
    );
  }

  if (!transaction) {
    return <div className="text-center text-gray-500 py-12">Transaction not found</div>;
  }

  return (
    <div>
      <h2 className="text-2xl font-bold text-gray-900 mb-6">Transaction Details</h2>

      <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
        <div className="lg:col-span-2 space-y-6">
          <div className="card">
            <h3 className="text-lg font-semibold mb-4">Payment Information</h3>
            <dl className="grid grid-cols-2 gap-4">
              <div>
                <dt className="text-sm text-gray-500">Payment ID</dt>
                <dd className="font-mono text-sm">{transaction.payment.id}</dd>
              </div>
              <div>
                <dt className="text-sm text-gray-500">Amount</dt>
                <dd className="font-semibold">
                  {formatCurrency(transaction.payment.amount, transaction.payment.currency)}
                </dd>
              </div>
              <div>
                <dt className="text-sm text-gray-500">Status</dt>
                <dd><StatusBadge status={transaction.payment.status} /></dd>
              </div>
              <div>
                <dt className="text-sm text-gray-500">Method</dt>
                <dd>{transaction.payment.method.replace('_', ' ')}</dd>
              </div>
              {transaction.payment.cardLast4 && (
                <div>
                  <dt className="text-sm text-gray-500">Card</dt>
                  <dd>{maskCard(transaction.payment.cardLast4)} ({transaction.payment.cardBrand})</dd>
                </div>
              )}
              {transaction.payment.upiId && (
                <div>
                  <dt className="text-sm text-gray-500">UPI ID</dt>
                  <dd>{transaction.payment.upiId}</dd>
                </div>
              )}
              <div>
                <dt className="text-sm text-gray-500">Date</dt>
                <dd>{formatDate(transaction.payment.createdAt)}</dd>
              </div>
            </dl>
          </div>

          <div className="card">
            <h3 className="text-lg font-semibold mb-4">Order Details</h3>
            <dl className="grid grid-cols-2 gap-4">
              <div>
                <dt className="text-sm text-gray-500">Order ID</dt>
                <dd className="font-mono text-sm">{transaction.order.id}</dd>
              </div>
              <div>
                <dt className="text-sm text-gray-500">Customer Email</dt>
                <dd>{transaction.order.customerEmail}</dd>
              </div>
              <div>
                <dt className="text-sm text-gray-500">Description</dt>
                <dd>{transaction.order.description}</dd>
              </div>
            </dl>
          </div>

          {transaction.refunds.length > 0 && (
            <div className="card">
              <h3 className="text-lg font-semibold mb-4">Refunds</h3>
              <div className="space-y-3">
                {transaction.refunds.map((refund) => (
                  <div key={refund.id} className="flex items-center justify-between p-3 bg-gray-50 rounded-lg">
                    <div>
                      <p className="text-sm font-medium">{formatCurrency(refund.amount, refund.currency)}</p>
                      <p className="text-xs text-gray-500">{refund.reason}</p>
                    </div>
                    <StatusBadge status={refund.status} />
                  </div>
                ))}
              </div>
            </div>
          )}
        </div>

        <div>
          <div className="card">
            <h3 className="text-lg font-semibold mb-4">Timeline</h3>
            <div className="space-y-4">
              {transaction.timeline.map((event, idx) => (
                <div key={idx} className="flex gap-3">
                  <div className="flex flex-col items-center">
                    <div className="w-3 h-3 rounded-full bg-primary-500" />
                    {idx < transaction.timeline.length - 1 && (
                      <div className="w-0.5 h-full bg-gray-200 mt-1" />
                    )}
                  </div>
                  <div className="pb-4">
                    <p className="text-sm font-medium text-gray-900">{event.event}</p>
                    <p className="text-xs text-gray-500">{formatDate(event.timestamp)}</p>
                    {event.details && (
                      <p className="text-xs text-gray-600 mt-1">{event.details}</p>
                    )}
                  </div>
                </div>
              ))}
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}
