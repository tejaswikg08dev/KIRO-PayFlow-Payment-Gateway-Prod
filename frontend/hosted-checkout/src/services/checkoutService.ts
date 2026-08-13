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
    const response = await apiClient.get<OrderDetails>(`/v1/orders/${orderId}`);
    return response.data;
  },

  async submitPayment(data: PaymentRequest): Promise<PaymentStatusResponse> {
    const response = await apiClient.post<PaymentStatusResponse>(
      '/v1/payments/authorize',
      data
    );
    return response.data;
  },

  async getPaymentStatus(paymentId: string): Promise<PaymentStatusResponse> {
    const response = await apiClient.get<PaymentStatusResponse>(
      `/v1/payments/${paymentId}`
    );
    return response.data;
  },
};
