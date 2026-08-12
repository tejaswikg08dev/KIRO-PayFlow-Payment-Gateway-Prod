import { useState } from 'react';
import { checkoutService } from '@/services/checkoutService';
import { BANKS } from '@/utils/constants';

interface NetBankingFormProps {
  orderId: string;
  isSubmitting: boolean;
  setIsSubmitting: (v: boolean) => void;
  onSuccess: (paymentId: string) => void;
}

export function NetBankingForm({
  orderId,
  isSubmitting,
  setIsSubmitting,
  onSuccess,
}: NetBankingFormProps) {
  const [selectedBank, setSelectedBank] = useState('');
  const [error, setError] = useState('');
  const [apiError, setApiError] = useState('');

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError('');
    setApiError('');

    if (!selectedBank) {
      setError('Please select a bank');
      return;
    }

    setIsSubmitting(true);
    try {
      const response = await checkoutService.submitPayment({
        orderId,
        method: 'NET_BANKING',
        bankCode: selectedBank,
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
        <label className="block text-sm font-medium text-gray-700 mb-2">Select Your Bank</label>
        <div className="grid grid-cols-2 gap-2">
          {BANKS.map((bank) => (
            <button
              key={bank.code}
              type="button"
              onClick={() => {
                setSelectedBank(bank.code);
                setError('');
              }}
              className={`p-3 border rounded-lg text-left text-sm transition-colors ${
                selectedBank === bank.code
                  ? 'border-primary-500 bg-primary-50 text-primary-700'
                  : 'border-gray-200 hover:border-gray-300 text-gray-700'
              }`}
            >
              {bank.name}
            </button>
          ))}
        </div>
        {error && <p className="text-red-500 text-xs mt-2">{error}</p>}
      </div>

      <div className="bg-gray-50 rounded-lg p-4 text-sm text-gray-600">
        <p>You will be redirected to your bank&apos;s secure login page to complete the payment.</p>
      </div>

      <button
        type="submit"
        disabled={isSubmitting || !selectedBank}
        className="w-full bg-primary-600 text-white py-3 rounded-lg font-medium hover:bg-primary-700 transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
      >
        {isSubmitting ? (
          <span className="flex items-center justify-center gap-2">
            <span className="animate-spin h-4 w-4 border-2 border-white border-t-transparent rounded-full" />
            Redirecting...
          </span>
        ) : (
          'Continue to Bank'
        )}
      </button>
    </form>
  );
}
