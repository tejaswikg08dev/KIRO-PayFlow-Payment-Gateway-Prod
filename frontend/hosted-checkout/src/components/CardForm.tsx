import { useState } from 'react';
import { checkoutService } from '@/services/checkoutService';
import {
  luhnCheck,
  detectCardBrand,
  formatCardNumber,
  formatExpiry,
  isValidExpiry,
  isValidCvv,
} from '@/utils/cardValidation';

interface CardFormProps {
  orderId: string;
  isSubmitting: boolean;
  setIsSubmitting: (v: boolean) => void;
  onSuccess: (paymentId: string) => void;
}

export function CardForm({ orderId, isSubmitting, setIsSubmitting, onSuccess }: CardFormProps) {
  const [cardNumber, setCardNumber] = useState('');
  const [cardExpiry, setCardExpiry] = useState('');
  const [cardCvv, setCardCvv] = useState('');
  const [cardHolderName, setCardHolderName] = useState('');
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [apiError, setApiError] = useState('');

  const brand = detectCardBrand(cardNumber);

  const validate = (): boolean => {
    const newErrors: Record<string, string> = {};
    const cleanNumber = cardNumber.replace(/\s/g, '');

    if (!cleanNumber) {
      newErrors.cardNumber = 'Card number is required';
    } else if (!luhnCheck(cleanNumber)) {
      newErrors.cardNumber = 'Invalid card number';
    }

    if (!cardExpiry) {
      newErrors.cardExpiry = 'Expiry date is required';
    } else if (!isValidExpiry(cardExpiry)) {
      newErrors.cardExpiry = 'Invalid or expired date';
    }

    if (!cardCvv) {
      newErrors.cardCvv = 'CVV is required';
    } else if (!isValidCvv(cardCvv, brand)) {
      newErrors.cardCvv = `CVV must be ${brand === 'amex' ? '4' : '3'} digits`;
    }

    if (!cardHolderName.trim()) {
      newErrors.cardHolderName = 'Cardholder name is required';
    }

    setErrors(newErrors);
    return Object.keys(newErrors).length === 0;
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!validate()) return;

    setIsSubmitting(true);
    setApiError('');

    try {
      const response = await checkoutService.submitPayment({
        orderId,
        method: 'CARD',
        cardNumber: cardNumber.replace(/\s/g, ''),
        cardExpiry,
        cardCvv,
        cardHolderName,
      });
      onSuccess(response.paymentId);
    } catch (err: unknown) {
      const error = err as { response?: { data?: { message?: string } } };
      setApiError(error.response?.data?.message || 'Payment failed. Please try again.');
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
        <label className="block text-sm font-medium text-gray-700 mb-1">Card Number</label>
        <div className="relative">
          <input
            type="text"
            value={cardNumber}
            onChange={(e) => setCardNumber(formatCardNumber(e.target.value))}
            maxLength={19}
            className="w-full px-3 py-2.5 border border-gray-300 rounded-lg focus:outline-none focus:ring-2 focus:ring-primary-500 focus:border-transparent"
            placeholder="1234 5678 9012 3456"
            autoComplete="cc-number"
          />
          {brand !== 'unknown' && (
            <span className="absolute right-3 top-1/2 -translate-y-1/2 text-xs font-medium text-gray-500 uppercase">
              {brand}
            </span>
          )}
        </div>
        {errors.cardNumber && <p className="text-red-500 text-xs mt-1">{errors.cardNumber}</p>}
      </div>

      <div>
        <label className="block text-sm font-medium text-gray-700 mb-1">Cardholder Name</label>
        <input
          type="text"
          value={cardHolderName}
          onChange={(e) => setCardHolderName(e.target.value)}
          className="w-full px-3 py-2.5 border border-gray-300 rounded-lg focus:outline-none focus:ring-2 focus:ring-primary-500 focus:border-transparent"
          placeholder="Name on card"
          autoComplete="cc-name"
        />
        {errors.cardHolderName && <p className="text-red-500 text-xs mt-1">{errors.cardHolderName}</p>}
      </div>

      <div className="grid grid-cols-2 gap-4">
        <div>
          <label className="block text-sm font-medium text-gray-700 mb-1">Expiry</label>
          <input
            type="text"
            value={cardExpiry}
            onChange={(e) => setCardExpiry(formatExpiry(e.target.value))}
            maxLength={5}
            className="w-full px-3 py-2.5 border border-gray-300 rounded-lg focus:outline-none focus:ring-2 focus:ring-primary-500 focus:border-transparent"
            placeholder="MM/YY"
            autoComplete="cc-exp"
          />
          {errors.cardExpiry && <p className="text-red-500 text-xs mt-1">{errors.cardExpiry}</p>}
        </div>
        <div>
          <label className="block text-sm font-medium text-gray-700 mb-1">CVV</label>
          <input
            type="password"
            value={cardCvv}
            onChange={(e) => setCardCvv(e.target.value.replace(/\D/g, '').substring(0, 4))}
            maxLength={4}
            className="w-full px-3 py-2.5 border border-gray-300 rounded-lg focus:outline-none focus:ring-2 focus:ring-primary-500 focus:border-transparent"
            placeholder="•••"
            autoComplete="cc-csc"
          />
          {errors.cardCvv && <p className="text-red-500 text-xs mt-1">{errors.cardCvv}</p>}
        </div>
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
          'Pay Now'
        )}
      </button>
    </form>
  );
}
