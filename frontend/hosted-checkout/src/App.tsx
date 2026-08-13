import { Routes, Route } from 'react-router-dom';
import CheckoutPage from './pages/CheckoutPage';
import PaymentStatusPage from './pages/PaymentStatusPage';
import ExpiredPage from './pages/ExpiredPage';

function NotFoundPage() {
  return (
    <div className="min-h-screen flex items-center justify-center bg-gray-100">
      <div className="text-center max-w-md mx-auto p-8">
        <div className="mb-6">
          <svg className="mx-auto h-16 w-16 text-gray-400" fill="none" viewBox="0 0 24 24" stroke="currentColor" aria-hidden="true">
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={1.5} d="M2.25 8.25h19.5M2.25 9h19.5m-16.5 5.25h6m-6 2.25h3m-3.75 3h15a2.25 2.25 0 002.25-2.25V6.75A2.25 2.25 0 0019.5 4.5h-15a2.25 2.25 0 00-2.25 2.25v10.5A2.25 2.25 0 004.5 19.5z" />
          </svg>
        </div>
        <h1 className="text-2xl font-semibold text-gray-900 mb-2">PayFlow Checkout</h1>
        <p className="text-gray-600 mb-4">
          This page requires a valid order ID to proceed with payment.
        </p>
        <p className="text-sm text-gray-500">
          If you were directed here from a merchant, please use the checkout link provided to you.
        </p>
      </div>
    </div>
  );
}

function App() {
  return (
    <Routes>
      <Route path="/checkout/:orderId" element={<CheckoutPage />} />
      <Route path="/status/:paymentId" element={<PaymentStatusPage />} />
      <Route path="/expired" element={<ExpiredPage />} />
      <Route path="*" element={<NotFoundPage />} />
    </Routes>
  );
}

export default App;
