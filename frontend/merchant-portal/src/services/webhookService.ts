import apiClient from './apiClient';
import {
  WebhookConfig,
  WebhookCreateRequest,
  WebhookDeliveryLog,
} from '@/types/merchant.types';

export const webhookService = {
  async getWebhooks(): Promise<WebhookConfig[]> {
    const response = await apiClient.get<WebhookConfig[]>('/v1/merchants/webhooks');
    return response.data;
  },

  async configureWebhook(data: WebhookCreateRequest): Promise<WebhookConfig> {
    const response = await apiClient.post<WebhookConfig>(
      '/v1/merchants/webhooks',
      data
    );
    return response.data;
  },

  async updateWebhook(id: string, data: Partial<WebhookCreateRequest>): Promise<WebhookConfig> {
    const response = await apiClient.put<WebhookConfig>(
      `/v1/merchants/webhooks/${id}`,
      data
    );
    return response.data;
  },

  async deleteWebhook(id: string): Promise<void> {
    await apiClient.delete(`/v1/merchants/webhooks/${id}`);
  },

  async getDeliveryLogs(webhookId: string): Promise<WebhookDeliveryLog[]> {
    const response = await apiClient.get<WebhookDeliveryLog[]>(
      `/v1/merchants/webhooks/${webhookId}/deliveries`
    );
    return response.data;
  },

  async retryDelivery(webhookId: string, deliveryId: string): Promise<void> {
    await apiClient.post(
      `/v1/merchants/webhooks/${webhookId}/deliveries/${deliveryId}/retry`
    );
  },
};
