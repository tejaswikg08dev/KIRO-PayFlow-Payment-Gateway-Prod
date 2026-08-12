import { useState } from 'react';
import { checkoutService } from '@/services/checkoutService';

interface UpiFormProps {
  orderId: string;
  isSubmitting: boolean;
  setIsSubmitting: (v: boolean) => void;
  onSuccess: (paymentId: string) => void;
}

export function UpiForm({ orderId, isSubmitting, setIsSubmitting, onSuccess }: UpiFormProps) {
  const [upiId, setUpiId] = useState('');
  const [error, setError] = useState('');
  const [apiError, setApiError] = useState('');

  const validateUpiId = (id: string): boolean => {
    const upiRegex = /^[\w.-]+@[\w]+$/;
    return upiRegex.test(id);
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError('');
    setApiError('');

    if (!upiId) {
      setError('UPI ID is required');
      return;
    }

    if (!validateUpiId(upiId)) {
      setError('Invalid UPI ID format (e.g. name@upi)');
      return;
    }

    setIsSubmitting(true);
    try {
      const response = await checkoutService.submitPayment({
        orderId,
        method: 'UPI',
        upiId,
      });
      onSuccess(response.paymentId);
    } catch (err: unknown) {
      const axiosError = err as { response?: { data?: { message?: string } } };
      setApiError(axiosError.response?.data?.message || 'Payment failed. Please try again.');
    } finally {
      setIsSubmitting(false);
    }
  };

  return (
    <form onSubmit={handleSubmit} className="space-y-4">
      {apiError && (
        <div className="p-3 bg-red-50 text-red-700 rounded-lg text-sm">{apiError}</div>
      )}

      <div>
        <label className="block text-sm font-medium text-gray-700 mb-1">UPI ID</label>
        <input
          type="text"
          value={upiId}
          onChange={(e) => {
            setUpiId(e.target.value);
            setError('');
          }}
          className="w-full px-3 py-2.5 border border-gray-300 rounded-lg focus:outline-none focus:ring-2 focus:ring-primary-500 focus:border-transparent"
          placeholder="yourname@upi"
        />
        {error && <p className="text-red-500 text-xs mt-1">{error}</p>}
      </div>

      <div className="bg-gray-50 rounded-lg p-4 text-sm text-gray-600">
        <p className="font-medium mb-2">How it works:</p>
        <ol className="list-decimal list-inside space-y-1">
          <li>Enter your UPI ID (e.g. name@okhdfcbank)</li>
          <li>You&apos;ll receive a payment request on your UPI app</li>
          <li>Approve the payment to complete the transaction</li>
        </ol>
      </div>

      <button
        type="submit"
        disabled={isSubmitting}
        className="w-full bg-primary-600 text-white py-3 rounded-lg font-medium hover:bg-primary-700 transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
      >
        {isSubmitting ? (
          <span className="flex items-center justify-center gap-2">
            <span className="animate-spin h-4 w-4 border-2 border-white border-t-transparent rounded-full" />
            Processing...
          </span>
        ) : (
          'Pay via UPI'
        )}
      </button>
    </form>
  );
}
