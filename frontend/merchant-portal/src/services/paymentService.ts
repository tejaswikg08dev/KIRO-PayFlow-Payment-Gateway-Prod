import apiClient from './apiClient';
import {
  Payment,
  Transaction,
  TransactionFilter,
  PaginatedResponse,
  RefundRequest,
  Refund,
} from '@/types/payment.types';

export const paymentService = {
  async getTransactions(filter: TransactionFilter): Promise<PaginatedResponse<Payment>> {
    const params = new URLSearchParams();
    params.append('page', filter.page.toString());
    params.append('size', filter.size.toString());
    if (filter.status) params.append('status', filter.status);
    if (filter.method) params.append('method', filter.method);
    if (filter.startDate) params.append('startDate', filter.startDate);
    if (filter.endDate) params.append('endDate', filter.endDate);
    if (filter.search) params.append('search', filter.search);

    const response = await apiClient.get<PaginatedResponse<Payment>>(
      `/v1/payments?${params.toString()}`
    );
    return response.data;
  },

  async getTransaction(id: string): Promise<Transaction> {
    const response = await apiClient.get<Transaction>(`/v1/payments/${id}`);
    return response.data;
  },

  async refundPayment(data: RefundRequest): Promise<Refund> {
    const response = await apiClient.post<Refund>(
      `/v1/payments/${data.paymentId}/refund`,
      {
        amount: data.amount,
        reason: data.reason,
      }
    );
    return response.data;
  },
};
