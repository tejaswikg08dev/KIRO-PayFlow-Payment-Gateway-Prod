# Phase 4 · Part 16 — Hosted Checkout Page

| Field | Value |
|-------|-------|
| **Project** | PayFlow Payment Gateway |
| **Phase** | 4 — Frontend Development |
| **Part** | 16 — Hosted Checkout (Card, UPI, Net Banking) |
| **Previous** | [Part 15C — Settings Pages](phase4-part15c-frontend-settings.md) |
| **Next** | [Phase 5 — Testing](phase5-testing.md) |
| **Time** | ~3 hours |
| **Difficulty** | ★★★★☆ Intermediate-Advanced |
| **Prerequisites** | React forms, Tailwind, payment method concepts |

---

## Table of Contents

1. [Checkout Page Layout](#1-checkout-page-layout)
2. [Card Form with Luhn Validation](#2-card-form-with-luhn-validation)
3. [UPI Form with VPA Validation](#3-upi-form-with-vpa-validation)
4. [Net Banking Form](#4-net-banking-form)
5. [Payment Status Page](#5-payment-status-page)
6. [Mobile-Responsive Design](#6-mobile-responsive-design)
7. [What You Learned](#what-you-learned)
8. [Common Errors & Fixes](#common-errors--fixes)

---

## 1. Checkout Page Layout

The hosted checkout is a standalone page rendered for end-customers. It shows an order summary on the left and payment method tabs on the right.

```
+------------------------------------------------------------------+
|  PayFlow Checkout                                     🔒 Secure    |
+------------------------------------------------------------------+
|                          |                                         |
|   ORDER SUMMARY          |   PAYMENT METHOD                       |
|   ───────────────        |   [Card] [UPI] [Net Banking]           |
|   Product: Widget Pro    |   ─────────────────────────            |
|   Qty: 2                 |                                         |
|   Subtotal: ₹2,000      |   Card Number                          |
|   Tax: ₹360             |   [4111 1111 1111 1111    ]            |
|   ─────────────          |                                         |
|   TOTAL: ₹2,360         |   Expiry       CVV                     |
|                          |   [12/26]      [•••]                   |
|   Merchant: Acme Inc     |                                         |
|   Order: #ORD_abc123     |   Name on Card                        |
|                          |   [John Doe              ]             |
|                          |                                         |
|                          |   [        Pay ₹2,360        ]        |
|                          |                                         |
+------------------------------------------------------------------+
```

```tsx
// src/pages/Checkout.tsx
export function CheckoutPage() {
  const { sessionId } = useParams();
  const { data: session } = useQuery({
    queryKey: ['checkout-session', sessionId],
    queryFn: () => apiClient.get(`/v1/checkout/sessions/${sessionId}`),
  });

  const [method, setMethod] = useState<'card' | 'upi' | 'netbanking'>('card');

  return (
    <div className="min-h-screen bg-gray-50 flex items-center justify-center p-4">
      <div className="bg-white rounded-xl shadow-lg max-w-4xl w-full grid grid-cols-1 md:grid-cols-5">
        {/* Left: Order Summary */}
        <div className="md:col-span-2 p-6 bg-gray-50 rounded-l-xl border-r">
          <OrderSummary session={session} />
        </div>

        {/* Right: Payment Form */}
        <div className="md:col-span-3 p-6">
          <PaymentTabs active={method} onChange={setMethod} />
          <div className="mt-6">
            {method === 'card' && <CardForm session={session} />}
            {method === 'upi' && <UpiForm session={session} />}
            {method === 'netbanking' && <NetBankingForm session={session} />}
          </div>
        </div>
      </div>
    </div>
  );
}
```

---

## 2. Card Form with Luhn Validation

### The Luhn Algorithm Explained

The Luhn algorithm validates credit card numbers by checking a mathematical checksum:

```
Card Number: 4 5 3 2 0 1 5 1 1 2 3 4 5 6 7 8

Step 1: Starting from the RIGHT, double every SECOND digit:
        8  7  12 5  8  3  4  1  2  5  2  0  4  3  10 4

Step 2: If doubled value > 9, subtract 9:
        8  7  3  5  8  3  4  1  2  5  2  0  4  3  1  4

Step 3: Sum all digits = 60

Step 4: If sum % 10 === 0 → VALID ✓
```

### Card Form Implementation

```tsx
// src/components/checkout/CardForm.tsx
import { useState } from 'react';
import { useMutation } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';

function luhnCheck(cardNumber: string): boolean {
  const digits = cardNumber.replace(/\s/g, '').split('').map(Number);
  let sum = 0;
  let isSecond = false;

  for (let i = digits.length - 1; i >= 0; i--) {
    let d = digits[i];
    if (isSecond) {
      d *= 2;
      if (d > 9) d -= 9;
    }
    sum += d;
    isSecond = !isSecond;
  }
  return sum % 10 === 0;
}

function formatCardNumber(value: string): string {
  return value.replace(/\s/g, '').replace(/(\d{4})/g, '$1 ').trim();
}

export function CardForm({ session }) {
  const navigate = useNavigate();
  const [card, setCard] = useState({ number: '', expiry: '', cvv: '', name: '' });
  const [errors, setErrors] = useState<Record<string, string>>({});

  const payMutation = useMutation({
    mutationFn: (payload) => apiClient.post(`/v1/checkout/sessions/${session.id}/pay`, payload),
    onSuccess: (data) => navigate(`/checkout/status/${data.paymentId}`),
    onError: (err) => navigate(`/checkout/status/failed`),
  });

  function validate(): boolean {
    const errs: Record<string, string> = {};
    if (!luhnCheck(card.number)) errs.number = 'Invalid card number';
    if (!/^\d{2}\/\d{2}$/.test(card.expiry)) errs.expiry = 'Use MM/YY format';
    if (!/^\d{3,4}$/.test(card.cvv)) errs.cvv = 'Invalid CVV';
    if (!card.name.trim()) errs.name = 'Name is required';
    setErrors(errs);
    return Object.keys(errs).length === 0;
  }

  function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    if (!validate()) return;
    payMutation.mutate({ method: 'CARD', ...card });
  }

  return (
    <form onSubmit={handleSubmit} className="space-y-4">
      <div>
        <label className="block text-sm font-medium text-gray-700">Card Number</label>
        <input type="text" maxLength={19} placeholder="4111 1111 1111 1111"
               value={card.number}
               onChange={(e) => setCard(c => ({ ...c, number: formatCardNumber(e.target.value) }))}
               className={`input w-full ${errors.number ? 'border-red-500' : ''}`} />
        {errors.number && <p className="text-red-500 text-xs mt-1">{errors.number}</p>}
      </div>

      <div className="grid grid-cols-2 gap-4">
        <div>
          <label className="block text-sm font-medium text-gray-700">Expiry</label>
          <input type="text" placeholder="MM/YY" maxLength={5}
                 value={card.expiry}
                 onChange={(e) => setCard(c => ({ ...c, expiry: e.target.value }))}
                 className={`input w-full ${errors.expiry ? 'border-red-500' : ''}`} />
        </div>
        <div>
          <label className="block text-sm font-medium text-gray-700">CVV</label>
          <input type="password" placeholder="•••" maxLength={4}
                 value={card.cvv}
                 onChange={(e) => setCard(c => ({ ...c, cvv: e.target.value }))}
                 className={`input w-full ${errors.cvv ? 'border-red-500' : ''}`} />
        </div>
      </div>

      <div>
        <label className="block text-sm font-medium text-gray-700">Name on Card</label>
        <input type="text" placeholder="John Doe"
               value={card.name}
               onChange={(e) => setCard(c => ({ ...c, name: e.target.value }))}
               className={`input w-full ${errors.name ? 'border-red-500' : ''}`} />
      </div>

      <button type="submit" disabled={payMutation.isLoading}
              className="w-full py-3 bg-indigo-600 text-white rounded-lg font-medium hover:bg-indigo-700 disabled:opacity-50">
        {payMutation.isLoading ? 'Processing...' : `Pay ${formatCurrency(session.amount)}`}
      </button>
    </form>
  );
}
```

---

## 3. UPI Form with VPA Validation

UPI Virtual Payment Addresses follow the format: `username@bankhandle`

```tsx
// src/components/checkout/UpiForm.tsx
const VPA_REGEX = /^[a-zA-Z0-9._-]+@[a-zA-Z]{2,}$/;

export function UpiForm({ session }) {
  const [vpa, setVpa] = useState('');
  const [error, setError] = useState('');
  const navigate = useNavigate();

  const payMutation = useMutation({
    mutationFn: (payload) => apiClient.post(`/v1/checkout/sessions/${session.id}/pay`, payload),
    onSuccess: (data) => navigate(`/checkout/status/${data.paymentId}`),
  });

  function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    if (!VPA_REGEX.test(vpa)) {
      setError('Enter a valid UPI ID (e.g., user@paytm)');
      return;
    }
    setError('');
    payMutation.mutate({ method: 'UPI', vpa });
  }

  return (
    <form onSubmit={handleSubmit} className="space-y-4">
      <div>
        <label className="block text-sm font-medium text-gray-700">UPI ID (VPA)</label>
        <input type="text" placeholder="yourname@upi"
               value={vpa} onChange={(e) => setVpa(e.target.value)}
               className={`input w-full ${error ? 'border-red-500' : ''}`} />
        {error && <p className="text-red-500 text-xs mt-1">{error}</p>}
        <p className="text-gray-500 text-xs mt-1">Examples: user@paytm, user@ybl, user@okaxis</p>
      </div>

      <button type="submit" disabled={payMutation.isLoading}
              className="w-full py-3 bg-indigo-600 text-white rounded-lg font-medium hover:bg-indigo-700">
        {payMutation.isLoading ? 'Waiting for approval...' : `Pay ${formatCurrency(session.amount)}`}
      </button>
    </form>
  );
}
```

---

## 4. Net Banking Form

Bank selector displays popular banks in a grid, with a dropdown for others.

```tsx
// src/components/checkout/NetBankingForm.tsx
const POPULAR_BANKS = [
  { code: 'SBI', name: 'State Bank of India', logo: '/banks/sbi.svg' },
  { code: 'HDFC', name: 'HDFC Bank', logo: '/banks/hdfc.svg' },
  { code: 'ICICI', name: 'ICICI Bank', logo: '/banks/icici.svg' },
  { code: 'AXIS', name: 'Axis Bank', logo: '/banks/axis.svg' },
  { code: 'KOTAK', name: 'Kotak Mahindra', logo: '/banks/kotak.svg' },
  { code: 'PNB', name: 'Punjab National Bank', logo: '/banks/pnb.svg' },
];

export function NetBankingForm({ session }) {
  const [selectedBank, setSelectedBank] = useState('');
  const navigate = useNavigate();

  const payMutation = useMutation({
    mutationFn: (payload) => apiClient.post(`/v1/checkout/sessions/${session.id}/pay`, payload),
    onSuccess: (data) => navigate(`/checkout/status/${data.paymentId}`),
  });

  return (
    <form onSubmit={(e) => { e.preventDefault(); payMutation.mutate({ method: 'NET_BANKING', bankCode: selectedBank }); }}
          className="space-y-4">
      <p className="text-sm font-medium text-gray-700">Select your bank</p>

      {/* Popular Banks Grid */}
      <div className="grid grid-cols-3 gap-3">
        {POPULAR_BANKS.map(bank => (
          <button key={bank.code} type="button"
                  onClick={() => setSelectedBank(bank.code)}
                  className={`p-3 border rounded-lg text-center text-sm hover:border-indigo-500
                    ${selectedBank === bank.code ? 'border-indigo-600 bg-indigo-50' : 'border-gray-200'}`}>
            <img src={bank.logo} alt={bank.name} className="w-8 h-8 mx-auto mb-1" />
            {bank.name}
          </button>
        ))}
      </div>

      {/* Other Banks Dropdown */}
      <select onChange={(e) => setSelectedBank(e.target.value)} value={selectedBank}
              className="input w-full">
        <option value="">— Other Banks —</option>
        <option value="BOB">Bank of Baroda</option>
        <option value="CANARA">Canara Bank</option>
        <option value="UNION">Union Bank</option>
        <option value="IDBI">IDBI Bank</option>
      </select>

      <button type="submit" disabled={!selectedBank || payMutation.isLoading}
              className="w-full py-3 bg-indigo-600 text-white rounded-lg font-medium hover:bg-indigo-700 disabled:opacity-50">
        {payMutation.isLoading ? 'Redirecting...' : `Pay ${formatCurrency(session.amount)}`}
      </button>
    </form>
  );
}
```

---

## 5. Payment Status Page

After payment, users land on a status page with animated feedback.

```tsx
// src/pages/PaymentStatus.tsx
import { useParams } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { CheckCircleIcon, XCircleIcon } from '@heroicons/react/24/solid';

export function PaymentStatus() {
  const { paymentId } = useParams();
  const { data: status } = useQuery({
    queryKey: ['payment-status', paymentId],
    queryFn: () => apiClient.get(`/v1/checkout/status/${paymentId}`),
    refetchInterval: (data) => data?.status === 'PENDING' ? 2000 : false, // Poll while pending
  });

  if (!status) return <Spinner />;

  return (
    <div className="min-h-screen flex items-center justify-center bg-gray-50">
      <div className="bg-white p-8 rounded-xl shadow-lg text-center max-w-md w-full">
        {status.status === 'CAPTURED' ? (
          <>
            <CheckCircleIcon className="w-20 h-20 text-green-500 mx-auto animate-bounce" />
            <h1 className="text-2xl font-bold text-green-700 mt-4">Payment Successful!</h1>
            <p className="text-gray-600 mt-2">₹{status.amount} paid to {status.merchantName}</p>
            <p className="text-sm text-gray-500 mt-1">Transaction ID: {status.transactionId}</p>
          </>
        ) : status.status === 'FAILED' ? (
          <>
            <XCircleIcon className="w-20 h-20 text-red-500 mx-auto animate-pulse" />
            <h1 className="text-2xl font-bold text-red-700 mt-4">Payment Failed</h1>
            <p className="text-gray-600 mt-2">{status.failureReason || 'Transaction could not be processed'}</p>
            <button onClick={() => window.history.back()}
                    className="mt-4 px-6 py-2 bg-indigo-600 text-white rounded-lg">
              Try Again
            </button>
          </>
        ) : (
          <>
            <div className="w-20 h-20 mx-auto animate-spin rounded-full border-4 border-indigo-200 border-t-indigo-600" />
            <h1 className="text-xl font-bold mt-4">Processing Payment...</h1>
            <p className="text-gray-500 mt-2">Please wait while we confirm your payment</p>
          </>
        )}
      </div>
    </div>
  );
}
```

---

## 6. Mobile-Responsive Design

Tailwind breakpoints ensure the checkout works on all devices:

```css
/* Key responsive patterns used */
.grid.grid-cols-1.md\:grid-cols-5    /* Stacks on mobile, splits on tablet+ */
.max-w-4xl.w-full                     /* Full width on mobile, constrained on desktop */
.p-4.md\:p-6                          /* Less padding on mobile */
.text-sm.md\:text-base                /* Smaller text on mobile */
```

```
MOBILE (< 768px)              TABLET/DESKTOP (>= 768px)
+--------------------+        +----------------------------------+
| ORDER SUMMARY      |        | SUMMARY    |  PAYMENT FORM      |
| ₹2,360             |        | ₹2,360     |  [Card][UPI][NB]   |
+--------------------+        |            |                    |
| [Card][UPI][NB]    |        |            |  Card Number       |
| Card Number        |        |            |  [                ]|
| [                ] |        |            |  Expiry    CVV     |
| Expiry     CVV     |        |            |  [      ] [   ]   |
| [      ]  [   ]   |        |            |                    |
| [     Pay ₹2,360  ]|        |            |  [  Pay ₹2,360  ] |
+--------------------+        +----------------------------------+
```

---

## What You Learned

| # | Topic | Key Takeaway |
|---|-------|-------------|
| 1 | Checkout layout | Two-column layout (summary + form) is industry standard |
| 2 | Luhn algorithm | Mathematical checksum catches typos before hitting the server |
| 3 | VPA validation | Simple regex validates UPI address format: `user@handle` |
| 4 | Bank selector | Grid for popular + dropdown for others reduces choice paralysis |
| 5 | Payment polling | `refetchInterval` with condition polls until terminal state |
| 6 | Status animations | Animated icons give immediate visual feedback |
| 7 | Responsive design | Mobile-first with Tailwind breakpoints (md:, lg:) |

---

## Common Errors & Fixes

| Error | Cause | Fix |
|-------|-------|-----|
| Luhn validation always fails | Spaces not stripped before validation | `cardNumber.replace(/\s/g, '')` |
| VPA regex rejects valid IDs | Regex too strict for bank handles | Allow alphanumeric: `[a-zA-Z0-9]+` |
| Payment stuck on "Processing" | Poll interval not checking terminal states | Check `CAPTURED`, `FAILED`, not just `PENDING` |
| Bank grid misaligned on mobile | Fixed grid columns on small screens | Use `grid-cols-2 md:grid-cols-3` |
| Session expired error | Checkout session has TTL (30 min) | Show expiry timer, redirect on 410 response |

---

<div align="center">

**[← Part 15C: Settings Pages](phase4-part15c-frontend-settings.md)** | **[Documentation Index](../README.md)** | **[Phase 5: Testing →](phase5-testing.md)**

</div>
