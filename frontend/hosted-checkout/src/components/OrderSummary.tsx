import { OrderDetails } from '@/types/checkout.types';

interface OrderSummaryProps {
  order: OrderDetails;
}

export function OrderSummary({ order }: OrderSummaryProps) {
  const formatAmount = (amount: number, currency: string) => {
    return new Intl.NumberFormat('en-IN', {
      style: 'currency',
      currency,
    }).format(amount / 100);
  };

  return (
    <div className="bg-white rounded-xl shadow-sm border border-gray-200 p-6">
      <div className="flex items-center gap-4 mb-4">
        {order.merchantLogo ? (
          <img
            src={order.merchantLogo}
            alt={order.merchantName}
            className="w-12 h-12 rounded-lg object-cover"
          />
        ) : (
          <div className="w-12 h-12 bg-primary-100 rounded-lg flex items-center justify-center">
            <span className="text-primary-700 font-bold text-lg">
              {order.merchantName.charAt(0)}
            </span>
          </div>
        )}
        <div>
          <h2 className="font-semibold text-gray-900">{order.merchantName}</h2>
          <p className="text-sm text-gray-500">{order.description}</p>
        </div>
      </div>

      <div className="border-t border-gray-200 pt-4">
        <div className="flex items-center justify-between">
          <span className="text-gray-600">Total Amount</span>
          <span className="text-2xl font-bold text-gray-900">
            {formatAmount(order.amount, order.currency)}
          </span>
        </div>
      </div>
    </div>
  );
}
