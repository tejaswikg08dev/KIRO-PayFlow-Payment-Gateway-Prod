import { Routes, Route } from 'react-router-dom';
import CheckoutPage from './pages/CheckoutPage';
import PaymentStatusPage from './pages/PaymentStatusPage';
import ExpiredPage from './pages/ExpiredPage';

function App() {
  return (
    <Routes>
      <Route path="/checkout/:orderId" element={<CheckoutPage />} />
      <Route path="/status/:paymentId" element={<PaymentStatusPage />} />
      <Route path="/expired" element={<ExpiredPage />} />
    </Routes>
  );
}

export default App;
