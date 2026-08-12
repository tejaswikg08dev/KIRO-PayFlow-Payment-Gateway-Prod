import { useState, useEffect } from 'react';
import { useParams } from 'react-router-dom';
import { checkoutService } from '@/services/checkoutService';
import { PaymentStatusResponse } from '@/types/checkout.types';
import { SecureFooter } from '@/components/SecureFooter';

export default function PaymentStatusPage() {
  const { paymentId } = useParams<{ paymentId: string }>();
  const [status, setStatus] = useState<PaymentStatusResponse | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    if (!paymentId) return;

    const fetchStatus = async () => {
      try {
        const data = await checkoutService.getPaymentStatus(paymentId);
        setStatus(data);
        if (data.status === 'PROCESSING' || data.status === 'PENDING') {
          setTimeout(fetchStatus, 3000);
        }
      } catch {
        setStatus(null);
      } finally {
        setLoading(false);
      }
    };

    fetchStatus();
  }, [paymentId]);

  if (loading) {
    return (
      <div className="min-h-screen flex items-center justify-center bg-gray-100">
        <div className="text-center">
          <div className="animate-spin h-12 w-12 border-4 border-primary-600 border-t-transparent rounded-full mx-auto mb-4" />
          <p className="text-gray-600">Processing your payment...</p>
        </div>
      </div>
    );
  }

  const formatAmount = (amount: number, currency: string) => {
    return new Intl.NumberFormat('en-IN', {
      style: 'currency',
      currency,
    }).format(amount / 100);
  };

  return (
    <div className="min-h-screen bg-gray-100 py-8 px-4 flex flex-col items-center justify-center">
      <div className="max-w-md w-full bg-white rounded-xl shadow-sm border border-gray-200 p-8 text-center">
        {status?.status === 'SUCCESS' && (
          <>
            <div className="w-20 h-20 bg-green-100 rounded-full flex items-center justify-center mx-auto mb-4">
              <svg className="w-10 h-10 text-green-600" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M5 13l4 4L19 7" />
              </svg>
            </div>
            <h1 className="text-2xl font-bold text-green-700 mb-2">Payment Successful</h1>
            <p className="text-gray-600 mb-4">
              Your payment of {formatAmount(status.amount, status.currency)} has been processed.
            </p>
          </>
        )}

        {status?.status === 'FAILED' && (
          <>
            <div className="w-20 h-20 bg-red-100 rounded-full flex items-center justify-center mx-auto mb-4">
              <svg className="w-10 h-10 text-red-600" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M6 18L18 6M6 6l12 12" />
              </svg>
            </div>
            <h1 className="text-2xl font-bold text-red-700 mb-2">Payment Failed</h1>
            <p className="text-gray-600 mb-4">
              {status.errorMessage || 'Your payment could not be processed. Please try again.'}
            </p>
          </>
        )}

        {(status?.status === 'PENDING' || status?.status === 'PROCESSING') && (
          <>
            <div className="w-20 h-20 bg-yellow-100 rounded-full flex items-center justify-center mx-auto mb-4 animate-pulse-slow">
              <svg className="w-10 h-10 text-yellow-600" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M12 8v4l3 3m6-3a9 9 0 11-18 0 9 9 0 0118 0z" />
              </svg>
            </div>
            <h1 className="text-2xl font-bold text-yellow-700 mb-2">Payment Pending</h1>
            <p className="text-gray-600 mb-4">
              Your payment is being processed. This may take a moment.
            </p>
          </>
        )}

        {status && (
          <div className="mt-6 pt-6 border-t border-gray-200 text-left text-sm">
            <div className="flex justify-between py-1">
              <span className="text-gray-500">Merchant</span>
              <span className="font-medium">{status.merchantName}</span>
            </div>
            <div className="flex justify-between py-1">
              <span className="text-gray-500">Amount</span>
              <span className="font-medium">{formatAmount(status.amount, status.currency)}</span>
            </div>
            <div className="flex justify-between py-1">
              <span className="text-gray-500">Payment ID</span>
              <span className="font-mono text-xs">{status.paymentId}</span>
            </div>
            <div className="flex justify-between py-1">
              <span className="text-gray-500">Method</span>
              <span className="font-medium">{status.method}</span>
            </div>
          </div>
        )}
      </div>

      <SecureFooter />
    </div>
  );
}
