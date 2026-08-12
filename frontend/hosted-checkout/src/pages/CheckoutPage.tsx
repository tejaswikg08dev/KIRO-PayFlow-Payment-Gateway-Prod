import { useState, useEffect } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { checkoutService } from '@/services/checkoutService';
import { OrderDetails, PaymentMethodType } from '@/types/checkout.types';
import { OrderSummary } from '@/components/OrderSummary';
import { PaymentMethodTabs } from '@/components/PaymentMethodTabs';
import { CardForm } from '@/components/CardForm';
import { UpiForm } from '@/components/UpiForm';
import { NetBankingForm } from '@/components/NetBankingForm';
import { SecureFooter } from '@/components/SecureFooter';

export default function CheckoutPage() {
  const { orderId } = useParams<{ orderId: string }>();
  const navigate = useNavigate();
  const [order, setOrder] = useState<OrderDetails | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [activeMethod, setActiveMethod] = useState<PaymentMethodType>('CARD');
  const [isSubmitting, setIsSubmitting] = useState(false);

  useEffect(() => {
    if (!orderId) return;

    const fetchOrder = async () => {
      try {
        const orderData = await checkoutService.getOrderDetails(orderId);
        if (orderData.status === 'EXPIRED') {
          navigate('/expired');
          return;
        }
        if (orderData.status === 'PAID') {
          navigate(`/status/${orderId}`);
          return;
        }
        setOrder(orderData);
      } catch {
        setError('Unable to load order details. Please try again.');
      } finally {
        setLoading(false);
      }
    };

    fetchOrder();
  }, [orderId, navigate]);

  const handlePaymentSuccess = (paymentId: string) => {
    navigate(`/status/${paymentId}`);
  };

  if (loading) {
    return (
      <div className="min-h-screen flex items-center justify-center">
        <div className="animate-spin h-8 w-8 border-2 border-primary-600 border-t-transparent rounded-full" />
      </div>
    );
  }

  if (error || !order) {
    return (
      <div className="min-h-screen flex items-center justify-center">
        <div className="text-center">
          <p className="text-red-600 mb-4">{error || 'Order not found'}</p>
        </div>
      </div>
    );
  }

  return (
    <div className="min-h-screen bg-gray-100 py-8 px-4">
      <div className="max-w-lg mx-auto">
        <OrderSummary order={order} />

        <div className="bg-white rounded-xl shadow-sm border border-gray-200 mt-6 overflow-hidden">
          <PaymentMethodTabs active={activeMethod} onChange={setActiveMethod} />

          <div className="p-6">
            {activeMethod === 'CARD' && (
              <CardForm
                orderId={order.id}
                isSubmitting={isSubmitting}
                setIsSubmitting={setIsSubmitting}
                onSuccess={handlePaymentSuccess}
              />
            )}
            {activeMethod === 'UPI' && (
              <UpiForm
                orderId={order.id}
                isSubmitting={isSubmitting}
                setIsSubmitting={setIsSubmitting}
                onSuccess={handlePaymentSuccess}
              />
            )}
            {activeMethod === 'NET_BANKING' && (
              <NetBankingForm
                orderId={order.id}
                isSubmitting={isSubmitting}
                setIsSubmitting={setIsSubmitting}
                onSuccess={handlePaymentSuccess}
              />
            )}
          </div>
        </div>

        <SecureFooter />
      </div>
    </div>
  );
}
