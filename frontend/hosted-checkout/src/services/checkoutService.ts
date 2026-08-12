import axios from 'axios';
import { API_BASE_URL } from '@/utils/constants';
import { OrderDetails, PaymentRequest, PaymentStatusResponse } from '@/types/checkout.types';

const apiClient = axios.create({
  baseURL: API_BASE_URL,
  headers: {
    'Content-Type': 'application/json',
  },
  timeout: 30000,
});

export const checkoutService = {
  async getOrderDetails(orderId: string): Promise<OrderDetails> {
    const response = await apiClient.get<OrderDetails>(`/api/checkout/orders/${orderId}`);
    return response.data;
  },

  async submitPayment(data: PaymentRequest): Promise<PaymentStatusResponse> {
    const response = await apiClient.post<PaymentStatusResponse>(
      '/api/checkout/pay',
      data
    );
    return response.data;
  },

  async getPaymentStatus(paymentId: string): Promise<PaymentStatusResponse> {
    const response = await apiClient.get<PaymentStatusResponse>(
      `/api/checkout/payments/${paymentId}/status`
    );
    return response.data;
  },
};
