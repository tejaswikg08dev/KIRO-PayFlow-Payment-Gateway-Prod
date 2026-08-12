# Phase 4 Part 13: Hosted Checkout Page

## Overview

The Hosted Checkout is a standalone payment page where customers enter card/UPI/net banking details. It handles form validation (Luhn algorithm for cards), payment submission, and displays payment status.

## Architecture

```
┌───────────────────────────────────────────────────────────┐
│ Merchant Website                                           │
│                                                            │
│ Customer clicks "Pay" → redirect to:                      │
│ https://checkout.payflow.io/pay/{orderId}                 │
└─────────────────────────────────┬─────────────────────────┘
                                  │
                                  ▼
┌───────────────────────────────────────────────────────────┐
│ HOSTED CHECKOUT PAGE                                       │
│                                                            │
│ ┌─────────┐ ┌─────────┐ ┌───────────────┐               │
│ │  Card   │ │   UPI   │ │  Net Banking  │               │
│ │  Form   │ │  Form   │ │    Form       │               │
│ └────┬────┘ └────┬────┘ └──────┬────────┘               │
│      │           │              │                         │
│      └───────────┴──────────────┘                         │
│                  │                                         │
│          POST /api/v1/orders/{id}/pay                     │
│                  │                                         │
│          ┌───────▼───────┐                                │
│          │ Payment Status │                               │
│          │   Page         │                               │
│          └───────────────┘                                │
└───────────────────────────────────────────────────────────┘
```

## Card Form with Luhn Validation

```tsx
// src/pages/checkout/CardForm.tsx
import { useState } from 'react';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';

// Luhn Algorithm Implementation
function luhnCheck(cardNumber: string): boolean {
  const digits = cardNumber.replace(/\s/g, '');
  if (!/^\d+$/.test(digits) || digits.length < 13 || digits.length > 19) {
    return false;
  }

  let sum = 0;
  let isEven = false;

  for (let i = digits.length - 1; i >= 0; i--) {
    let digit = parseInt(digits[i], 10);

    if (isEven) {
      digit *= 2;
      if (digit > 9) {
        digit -= 9;
      }
    }

    sum += digit;
    isEven = !isEven;
  }

  return sum % 10 === 0;
}

// Detect card network from BIN
function detectCardNetwork(number: string): string {
  const cleaned = number.replace(/\s/g, '');
  if (cleaned.startsWith('4')) return 'visa';
  if (cleaned.startsWith('5') || cleaned.startsWith('2')) return 'mastercard';
  if (cleaned.startsWith('6')) return 'rupay';
  if (cleaned.startsWith('37') || cleaned.startsWith('34')) return 'amex';
  return 'unknown';
}

const cardSchema = z.object({
  number: z.string()
    .min(13, 'Card number too short')
    .max(19, 'Card number too long')
    .refine((val) => luhnCheck(val.replace(/\s/g, '')), 'Invalid card number'),
  holderName: z.string().min(2, 'Cardholder name required'),
  expiryMonth: z.string().regex(/^(0[1-9]|1[0-2])$/, 'Invalid month (01-12)'),
  expiryYear: z.string().regex(/^\d{2}$/, 'Invalid year (YY)'),
  cvv: z.string().regex(/^\d{3,4}$/, 'Invalid CVV'),
}).refine((data) => {
  const now = new Date();
  const expiry = new Date(2000 + parseInt(data.expiryYear),
    parseInt(data.expiryMonth) - 1);
  return expiry > now;
}, { message: 'Card has expired', path: ['expiryYear'] });

type CardFormData = z.infer<typeof cardSchema>;

export function CardForm({ onSubmit, isProcessing }: {
  onSubmit: (data: CardFormData) => void;
  isProcessing: boolean;
}) {
  const [cardNetwork, setCardNetwork] = useState('unknown');

  const { register, handleSubmit, formState: { errors }, setValue, watch } =
    useForm<CardFormData>({
      resolver: zodResolver(cardSchema),
    });

  // Format card number with spaces (4242 4242 4242 4242)
  const formatCardNumber = (value: string) => {
    const cleaned = value.replace(/\D/g, '').slice(0, 16);
    const formatted = cleaned.replace(/(\d{4})(?=\d)/g, '$1 ');
    setValue('number', formatted);
    setCardNetwork(detectCardNetwork(cleaned));
  };

  return (
    <form onSubmit={handleSubmit(onSubmit)} className="space-y-4">
      {/* Card Number */}
      <div>
        <label className="block text-sm font-medium text-gray-700 mb-1">
          Card Number
        </label>
        <div className="relative">
          <input
            type="text"
            placeholder="4111 1111 1111 1111"
            className="w-full px-4 py-3 border rounded-lg focus:ring-2 focus:ring-blue-500 font-mono"
            maxLength={19}
            {...register('number', {
              onChange: (e) => formatCardNumber(e.target.value),
            })}
          />
          <div className="absolute right-3 top-3">
            <CardNetworkIcon network={cardNetwork} />
          </div>
        </div>
        {errors.number && (
          <p className="text-red-500 text-sm mt-1">{errors.number.message}</p>
        )}
      </div>

      {/* Cardholder Name */}
      <div>
        <label className="block text-sm font-medium text-gray-700 mb-1">
          Cardholder Name
        </label>
        <input
          type="text"
          placeholder="JOHN DOE"
          className="w-full px-4 py-3 border rounded-lg uppercase"
          {...register('holderName')}
        />
        {errors.holderName && (
          <p className="text-red-500 text-sm mt-1">{errors.holderName.message}</p>
        )}
      </div>

      {/* Expiry + CVV */}
      <div className="grid grid-cols-3 gap-3">
        <div>
          <label className="block text-sm font-medium text-gray-700 mb-1">Month</label>
          <input type="text" placeholder="MM" maxLength={2}
            className="w-full px-4 py-3 border rounded-lg text-center font-mono"
            {...register('expiryMonth')} />
          {errors.expiryMonth && <p className="text-red-500 text-xs mt-1">{errors.expiryMonth.message}</p>}
        </div>
        <div>
          <label className="block text-sm font-medium text-gray-700 mb-1">Year</label>
          <input type="text" placeholder="YY" maxLength={2}
            className="w-full px-4 py-3 border rounded-lg text-center font-mono"
            {...register('expiryYear')} />
          {errors.expiryYear && <p className="text-red-500 text-xs mt-1">{errors.expiryYear.message}</p>}
        </div>
        <div>
          <label className="block text-sm font-medium text-gray-700 mb-1">CVV</label>
          <input type="password" placeholder="•••" maxLength={4}
            className="w-full px-4 py-3 border rounded-lg text-center font-mono"
            {...register('cvv')} />
          {errors.cvv && <p className="text-red-500 text-xs mt-1">{errors.cvv.message}</p>}
        </div>
      </div>

      <button
        type="submit"
        disabled={isProcessing}
        className="w-full py-4 bg-blue-600 text-white rounded-lg font-semibold hover:bg-blue-700 disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
      >
        {isProcessing ? (
          <span className="flex items-center justify-center gap-2">
            <Spinner /> Processing...
          </span>
        ) : (
          `Pay ${formatCurrency(orderAmount)}`
        )}
      </button>
    </form>
  );
}
```

## UPI Payment Form

```tsx
// src/pages/checkout/UpiForm.tsx
import { z } from 'zod';

const upiSchema = z.object({
  vpa: z.string()
    .regex(/^[a-zA-Z0-9._-]+@[a-zA-Z0-9]+$/, 'Invalid UPI ID format')
    .min(5, 'UPI ID too short'),
});

export function UpiForm({ onSubmit, isProcessing }) {
  const { register, handleSubmit, formState: { errors } } = useForm({
    resolver: zodResolver(upiSchema),
  });

  return (
    <form onSubmit={handleSubmit(onSubmit)} className="space-y-4">
      <div>
        <label className="block text-sm font-medium text-gray-700 mb-1">
          UPI ID / VPA
        </label>
        <input
          type="text"
          placeholder="yourname@upi"
          className="w-full px-4 py-3 border rounded-lg"
          {...register('vpa')}
        />
        {errors.vpa && (
          <p className="text-red-500 text-sm mt-1">{errors.vpa.message}</p>
        )}
        <p className="text-xs text-gray-500 mt-1">
          Example: username@paytm, username@ybl, username@okaxis
        </p>
      </div>

      <button type="submit" disabled={isProcessing}
        className="w-full py-4 bg-purple-600 text-white rounded-lg font-semibold hover:bg-purple-700 disabled:opacity-50">
        {isProcessing ? 'Processing...' : `Pay via UPI`}
      </button>
    </form>
  );
}
```

## Net Banking Form

```tsx
// src/pages/checkout/NetBankingForm.tsx
const POPULAR_BANKS = [
  { code: 'SBI', name: 'State Bank of India', logo: '🏦' },
  { code: 'HDFC', name: 'HDFC Bank', logo: '🏦' },
  { code: 'ICICI', name: 'ICICI Bank', logo: '🏦' },
  { code: 'AXIS', name: 'Axis Bank', logo: '🏦' },
  { code: 'KOTAK', name: 'Kotak Mahindra Bank', logo: '🏦' },
  { code: 'PNB', name: 'Punjab National Bank', logo: '🏦' },
];

export function NetBankingForm({ onSubmit, isProcessing }) {
  const [selectedBank, setSelectedBank] = useState('');

  return (
    <div className="space-y-4">
      <p className="text-sm text-gray-700">Select your bank:</p>

      {/* Popular Banks Grid */}
      <div className="grid grid-cols-3 gap-2">
        {POPULAR_BANKS.map(bank => (
          <button
            key={bank.code}
            type="button"
            onClick={() => setSelectedBank(bank.code)}
            className={`p-3 border rounded-lg text-center text-sm transition-colors ${
              selectedBank === bank.code
                ? 'border-blue-500 bg-blue-50'
                : 'border-gray-200 hover:border-gray-300'
            }`}
          >
            <span className="text-2xl block mb-1">{bank.logo}</span>
            {bank.name}
          </button>
        ))}
      </div>

      {/* Other Banks Dropdown */}
      <select
        className="w-full px-4 py-3 border rounded-lg"
        value={selectedBank}
        onChange={(e) => setSelectedBank(e.target.value)}
      >
        <option value="">-- Other Banks --</option>
        {/* Full list of banks */}
      </select>

      <button
        onClick={() => onSubmit({ bankCode: selectedBank })}
        disabled={!selectedBank || isProcessing}
        className="w-full py-4 bg-green-600 text-white rounded-lg font-semibold hover:bg-green-700 disabled:opacity-50"
      >
        {isProcessing ? 'Redirecting...' : 'Proceed to Bank'}
      </button>
    </div>
  );
}
```

## Payment Status Page

```tsx
// src/pages/checkout/PaymentStatus.tsx
import { useEffect, useState } from 'react';
import { useParams } from 'react-router-dom';

type PaymentState = 'processing' | 'success' | 'failed' | 'timeout';

export function PaymentStatus() {
  const { orderId } = useParams();
  const [status, setStatus] = useState<PaymentState>('processing');
  const [orderDetails, setOrderDetails] = useState(null);

  // Poll for payment status
  useEffect(() => {
    const pollInterval = setInterval(async () => {
      const response = await fetch(`/api/v1/orders/${orderId}`);
      const data = await response.json();

      if (data.data.status === 'CAPTURED' || data.data.status === 'AUTHORIZED') {
        setStatus('success');
        setOrderDetails(data.data);
        clearInterval(pollInterval);
      } else if (data.data.status === 'FAILED') {
        setStatus('failed');
        setOrderDetails(data.data);
        clearInterval(pollInterval);
      }
    }, 2000);  // Poll every 2 seconds

    // Timeout after 60 seconds
    const timeout = setTimeout(() => {
      setStatus('timeout');
      clearInterval(pollInterval);
    }, 60000);

    return () => {
      clearInterval(pollInterval);
      clearTimeout(timeout);
    };
  }, [orderId]);

  return (
    <div className="min-h-screen flex items-center justify-center bg-gray-50 p-4">
      <div className="max-w-md w-full bg-white rounded-2xl shadow-lg p-8 text-center">
        {status === 'processing' && (
          <>
            <div className="animate-spin w-16 h-16 border-4 border-blue-200 border-t-blue-600 rounded-full mx-auto" />
            <h2 className="mt-6 text-xl font-semibold">Processing Payment</h2>
            <p className="text-gray-600 mt-2">Please wait while we confirm your payment...</p>
          </>
        )}

        {status === 'success' && (
          <>
            <div className="w-16 h-16 bg-green-100 rounded-full flex items-center justify-center mx-auto">
              <span className="text-3xl">✓</span>
            </div>
            <h2 className="mt-6 text-xl font-semibold text-green-700">Payment Successful!</h2>
            <p className="text-gray-600 mt-2">
              ₹{(orderDetails.amount / 100).toFixed(2)} paid successfully
            </p>
            <p className="text-sm text-gray-500 mt-4">
              Order ID: {orderId?.substring(0, 8)}
            </p>
          </>
        )}

        {status === 'failed' && (
          <>
            <div className="w-16 h-16 bg-red-100 rounded-full flex items-center justify-center mx-auto">
              <span className="text-3xl">✗</span>
            </div>
            <h2 className="mt-6 text-xl font-semibold text-red-700">Payment Failed</h2>
            <p className="text-gray-600 mt-2">Your payment could not be processed.</p>
            <button className="mt-6 px-6 py-2 bg-blue-600 text-white rounded-lg">
              Try Again
            </button>
          </>
        )}
      </div>
    </div>
  );
}
```

## Luhn Algorithm Explained

```
Card: 4111 1111 1111 1111

Step 1: Start from rightmost digit, double every second digit
        4  1  1  1  1  1  1  1  1  1  1  1  1  1  1  1
        ↓     ↓     ↓     ↓     ↓     ↓     ↓     ↓
        8  1  2  1  2  1  2  1  2  1  2  1  2  1  2  1

Step 2: If doubled value > 9, subtract 9
        8  1  2  1  2  1  2  1  2  1  2  1  2  1  2  1
        (no values > 9 in this case)

Step 3: Sum all digits
        8+1+2+1+2+1+2+1+2+1+2+1+2+1+2+1 = 30

Step 4: If sum % 10 == 0 → VALID ✓
        30 % 10 = 0 → Card number is valid
```

## Security Notes

| Concern | Handling |
|---------|----------|
| Card number | Never stored; sent directly to backend over HTTPS |
| CVV | Never logged, never stored |
| Form autocomplete | `autocomplete="cc-number"` for browser autofill |
| Input masking | CVV shown as dots, card partially masked |
| HTTPS only | Checkout page served over TLS only |
| CSP headers | Strict Content-Security-Policy |
| No third-party scripts | Minimize XSS attack surface |
