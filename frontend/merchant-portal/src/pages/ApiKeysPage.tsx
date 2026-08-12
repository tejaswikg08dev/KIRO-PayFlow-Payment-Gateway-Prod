import { useState } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { apiKeyService } from '@/services/apiKeyService';
import { LoadingSpinner } from '@/components/common/LoadingSpinner';
import { StatusBadge } from '@/components/common/StatusBadge';
import { ConfirmModal } from '@/components/common/ConfirmModal';
import { Toast } from '@/components/common/Toast';
import { formatDate, maskApiKey } from '@/utils/formatters';
import { ApiKeyCreateRequest } from '@/types/merchant.types';

export default function ApiKeysPage() {
  const queryClient = useQueryClient();
  const [showCreateForm, setShowCreateForm] = useState(false);
  const [newKeyVisible, setNewKeyVisible] = useState<string | null>(null);
  const [revokeId, setRevokeId] = useState<string | null>(null);
  const [toast, setToast] = useState<{ message: string; type: 'success' | 'error' } | null>(null);
  const [formData, setFormData] = useState<ApiKeyCreateRequest>({
    name: '',
    mode: 'TEST',
    permissions: ['payments:read', 'payments:write'],
  });

  const { data: keys, isLoading } = useQuery({
    queryKey: ['apiKeys'],
    queryFn: () => apiKeyService.listKeys(),
  });

  const createMutation = useMutation({
    mutationFn: (data: ApiKeyCreateRequest) => apiKeyService.generateKey(data),
    onSuccess: (response) => {
      queryClient.invalidateQueries({ queryKey: ['apiKeys'] });
      setNewKeyVisible(response.key);
      setShowCreateForm(false);
      setFormData({ name: '', mode: 'TEST', permissions: ['payments:read', 'payments:write'] });
    },
    onError: () => {
      setToast({ message: 'Failed to create API key', type: 'error' });
    },
  });

  const revokeMutation = useMutation({
    mutationFn: (keyId: string) => apiKeyService.revokeKey(keyId),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['apiKeys'] });
      setToast({ message: 'API key revoked successfully', type: 'success' });
      setRevokeId(null);
    },
  });

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
        <h2 className="text-2xl font-bold text-gray-900">API Keys</h2>
        <button onClick={() => setShowCreateForm(true)} className="btn-primary">
          Generate New Key
        </button>
      </div>

      {newKeyVisible && (
        <div className="card mb-6 border-green-200 bg-green-50">
          <h4 className="font-semibold text-green-800 mb-2">New API Key Created</h4>
          <p className="text-sm text-green-700 mb-2">
            Copy this key now. You won&apos;t be able to see it again.
          </p>
          <code className="block p-3 bg-white rounded border text-sm font-mono break-all">
            {newKeyVisible}
          </code>
          <button
            onClick={() => setNewKeyVisible(null)}
            className="mt-3 text-sm text-green-700 hover:text-green-800"
          >
            Dismiss
          </button>
        </div>
      )}

      {showCreateForm && (
        <div className="card mb-6">
          <h3 className="text-lg font-semibold mb-4">Create API Key</h3>
          <form
            onSubmit={(e) => {
              e.preventDefault();
              createMutation.mutate(formData);
            }}
            className="space-y-4"
          >
            <div>
              <label className="block text-sm font-medium text-gray-700 mb-1">Key Name</label>
              <input
                type="text"
                value={formData.name}
                onChange={(e) => setFormData((prev) => ({ ...prev, name: e.target.value }))}
                className="input-field"
                placeholder="e.g. Production Server"
              />
            </div>
            <div>
              <label className="block text-sm font-medium text-gray-700 mb-1">Mode</label>
              <select
                value={formData.mode}
                onChange={(e) => setFormData((prev) => ({ ...prev, mode: e.target.value as 'TEST' | 'LIVE' }))}
                className="input-field"
              >
                <option value="TEST">Test</option>
                <option value="LIVE">Live</option>
              </select>
            </div>
            <div className="flex gap-3">
              <button type="submit" disabled={createMutation.isPending} className="btn-primary">
                {createMutation.isPending ? <LoadingSpinner size="sm" /> : 'Create Key'}
              </button>
              <button type="button" onClick={() => setShowCreateForm(false)} className="btn-secondary">
                Cancel
              </button>
            </div>
          </form>
        </div>
      )}

      <div className="card">
        <div className="space-y-4">
          {keys?.map((key) => (
            <div key={key.id} className="flex items-center justify-between p-4 border border-gray-200 rounded-lg">
              <div>
                <div className="flex items-center gap-2">
                  <span className="font-medium">{key.name}</span>
                  <StatusBadge status={key.isActive ? 'ACTIVE' : 'REVOKED'} />
                  <span className={`text-xs px-2 py-0.5 rounded ${key.mode === 'LIVE' ? 'bg-green-100 text-green-800' : 'bg-yellow-100 text-yellow-800'}`}>
                    {key.mode}
                  </span>
                </div>
                <p className="text-sm text-gray-500 font-mono mt-1">{maskApiKey(key.keyPrefix)}</p>
                <p className="text-xs text-gray-400 mt-1">Created: {formatDate(key.createdAt)}</p>
              </div>
              {key.isActive && (
                <button
                  onClick={() => setRevokeId(key.id)}
                  className="text-sm text-red-600 hover:text-red-700"
                >
                  Revoke
                </button>
              )}
            </div>
          ))}
        </div>
      </div>

      <ConfirmModal
        isOpen={!!revokeId}
        title="Revoke API Key"
        message="Are you sure you want to revoke this API key? This action cannot be undone."
        confirmLabel="Revoke"
        variant="danger"
        onConfirm={() => revokeId && revokeMutation.mutate(revokeId)}
        onCancel={() => setRevokeId(null)}
      />

      {toast && <Toast message={toast.message} type={toast.type} onClose={() => setToast(null)} />}
    </div>
  );
}
