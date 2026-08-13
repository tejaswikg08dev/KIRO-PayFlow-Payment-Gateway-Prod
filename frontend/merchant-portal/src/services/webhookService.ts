import apiClient from './apiClient';
import {
  WebhookConfig,
  WebhookCreateRequest,
  WebhookDeliveryLog,
} from '@/types/merchant.types';
import { authService } from './authService';

export const webhookService = {
  async getWebhooks(): Promise<WebhookConfig[]> {
    const merchantId = authService.getMerchantId();
    if (!merchantId) return [];
    const response = await apiClient.get<WebhookConfig[]>(`/v1/merchants/${merchantId}/webhooks`);
    return response.data;
  },

  async configureWebhook(data: WebhookCreateRequest): Promise<WebhookConfig> {
    const merchantId = authService.getMerchantId();
    if (!merchantId) throw new Error('No merchant profile found');
    const response = await apiClient.post<WebhookConfig>(
      `/v1/merchants/${merchantId}/webhooks`,
      data
    );
    return response.data;
  },

  async updateWebhook(id: string, data: Partial<WebhookCreateRequest>): Promise<WebhookConfig> {
    const merchantId = authService.getMerchantId();
    if (!merchantId) throw new Error('No merchant profile found');
    const response = await apiClient.put<WebhookConfig>(
      `/v1/merchants/${merchantId}/webhooks/${id}`,
      data
    );
    return response.data;
  },

  async deleteWebhook(id: string): Promise<void> {
    const merchantId = authService.getMerchantId();
    if (!merchantId) throw new Error('No merchant profile found');
    await apiClient.delete(`/v1/merchants/${merchantId}/webhooks/${id}`);
  },

  async getDeliveryLogs(webhookId: string): Promise<WebhookDeliveryLog[]> {
    const merchantId = authService.getMerchantId();
    if (!merchantId) return [];
    const response = await apiClient.get<WebhookDeliveryLog[]>(
      `/v1/merchants/${merchantId}/webhooks/${webhookId}/deliveries`
    );
    return response.data;
  },

  async retryDelivery(webhookId: string, deliveryId: string): Promise<void> {
    const merchantId = authService.getMerchantId();
    if (!merchantId) throw new Error('No merchant profile found');
    await apiClient.post(
      `/v1/merchants/${merchantId}/webhooks/${webhookId}/deliveries/${deliveryId}/retry`
    );
  },
};
