import { useState } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { webhookService } from '@/services/webhookService';
import { LoadingSpinner } from '@/components/common/LoadingSpinner';
import { StatusBadge } from '@/components/common/StatusBadge';
import { ConfirmModal } from '@/components/common/ConfirmModal';
import { Toast } from '@/components/common/Toast';
import { formatDate } from '@/utils/formatters';
import { WEBHOOK_EVENTS } from '@/utils/constants';
import { WebhookCreateRequest } from '@/types/merchant.types';

export default function WebhooksPage() {
  const queryClient = useQueryClient();
  const [showForm, setShowForm] = useState(false);
  const [deleteId, setDeleteId] = useState<string | null>(null);
  const [toast, setToast] = useState<{ message: string; type: 'success' | 'error' } | null>(null);
  const [formData, setFormData] = useState<WebhookCreateRequest>({
    url: '',
    events: [],
  });

  const { data: webhooks, isLoading } = useQuery({
    queryKey: ['webhooks'],
    queryFn: () => webhookService.getWebhooks(),
  });

  const createMutation = useMutation({
    mutationFn: (data: WebhookCreateRequest) => webhookService.configureWebhook(data),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['webhooks'] });
      setShowForm(false);
      setFormData({ url: '', events: [] });
      setToast({ message: 'Webhook configured successfully', type: 'success' });
    },
    onError: () => {
      setToast({ message: 'Failed to configure webhook', type: 'error' });
    },
  });

  const deleteMutation = useMutation({
    mutationFn: (id: string) => webhookService.deleteWebhook(id),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['webhooks'] });
      setToast({ message: 'Webhook deleted', type: 'success' });
      setDeleteId(null);
    },
  });

  const toggleEvent = (event: string) => {
    setFormData((prev) => ({
      ...prev,
      events: prev.events.includes(event)
        ? prev.events.filter((e) => e !== event)
        : [...prev.events, event],
    }));
  };

  if (isLoading) {
    return (
      <div className="flex items-center justify-center h-64">
        <LoadingSpinner size="lg" />
      </div>
    );
  }

  return (
    <div>
      <div className="flex items-center justify-between mb-6">
        <h2 className="text-2xl font-bold text-gray-900">Webhooks</h2>
        <button onClick={() => setShowForm(true)} className="btn-primary">
          Add Webhook
        </button>
      </div>

      {showForm && (
        <div className="card mb-6">
          <h3 className="text-lg font-semibold mb-4">Configure Webhook</h3>
          <form
            onSubmit={(e) => {
              e.preventDefault();
              createMutation.mutate(formData);
            }}
            className="space-y-4"
          >
            <div>
              <label className="block text-sm font-medium text-gray-700 mb-1">Endpoint URL</label>
              <input
                type="url"
                value={formData.url}
                onChange={(e) => setFormData((prev) => ({ ...prev, url: e.target.value }))}
                className="input-field"
                placeholder="https://your-server.com/webhook"
              />
            </div>
            <div>
              <label className="block text-sm font-medium text-gray-700 mb-2">Events</label>
              <div className="grid grid-cols-2 gap-2">
                {WEBHOOK_EVENTS.map((event) => (
                  <label key={event} className="flex items-center gap-2 text-sm">
                    <input
                      type="checkbox"
                      checked={formData.events.includes(event)}
                      onChange={() => toggleEvent(event)}
                      className="rounded border-gray-300 text-primary-600"
                    />
                    {event}
                  </label>
                ))}
              </div>
            </div>
            <div className="flex gap-3">
              <button type="submit" disabled={createMutation.isPending} className="btn-primary">
                {createMutation.isPending ? <LoadingSpinner size="sm" /> : 'Save Webhook'}
              </button>
              <button type="button" onClick={() => setShowForm(false)} className="btn-secondary">
                Cancel
              </button>
            </div>
          </form>
        </div>
      )}

      <div className="space-y-4">
        {webhooks?.map((webhook) => (
          <div key={webhook.id} className="card">
            <div className="flex items-center justify-between">
              <div>
                <div className="flex items-center gap-2">
                  <span className="font-mono text-sm">{webhook.url}</span>
                  <StatusBadge status={webhook.isActive ? 'ACTIVE' : 'INACTIVE'} />
                </div>
                <p className="text-xs text-gray-500 mt-1">
                  Events: {webhook.events.join(', ')}
                </p>
                <p className="text-xs text-gray-400 mt-1">Created: {formatDate(webhook.createdAt)}</p>
              </div>
              <button
                onClick={() => setDeleteId(webhook.id)}
                className="text-sm text-red-600 hover:text-red-700"
              >
                Delete
              </button>
            </div>
          </div>
        ))}
      </div>

      <ConfirmModal
        isOpen={!!deleteId}
        title="Delete Webhook"
        message="Are you sure you want to delete this webhook? You will stop receiving events."
        confirmLabel="Delete"
        variant="danger"
        onConfirm={() => deleteId && deleteMutation.mutate(deleteId)}
        onCancel={() => setDeleteId(null)}
      />

      {toast && <Toast message={toast.message} type={toast.type} onClose={() => setToast(null)} />}
    </div>
  );
}
