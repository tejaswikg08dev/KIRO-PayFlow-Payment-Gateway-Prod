# Phase 4 Part 12d: Dashboard Settings

## Overview

Settings pages for the merchant dashboard: API key management (generate, view, revoke), webhook configuration, and profile settings.

## API Keys Management Page

```tsx
// src/pages/settings/ApiKeysPage.tsx
import { useState } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { merchantApi } from '@/api/merchants.api';
import { Button } from '@/components/common/Button';
import { Badge } from '@/components/common/Badge';
import { Modal } from '@/components/common/Modal';

export function ApiKeysPage() {
  const [showNewKey, setShowNewKey] = useState<string | null>(null);
  const [isGenerating, setIsGenerating] = useState(false);
  const queryClient = useQueryClient();

  const { data: keys, isLoading } = useQuery({
    queryKey: ['api-keys'],
    queryFn: () => merchantApi.getApiKeys(),
  });

  const generateMutation = useMutation({
    mutationFn: (type: 'LIVE' | 'TEST') => merchantApi.generateApiKey(type),
    onSuccess: (data) => {
      setShowNewKey(data.data.data.fullKey);
      queryClient.invalidateQueries(['api-keys']);
    },
  });

  const revokeMutation = useMutation({
    mutationFn: (keyId: string) => merchantApi.revokeApiKey(keyId),
    onSuccess: () => {
      queryClient.invalidateQueries(['api-keys']);
    },
  });

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold text-dark-900">API Keys</h1>
          <p className="text-dark-700 mt-1">
            Manage your API keys for integrating PayFlow into your application.
          </p>
        </div>
        <div className="flex gap-2">
          <Button
            variant="secondary"
            onClick={() => generateMutation.mutate('TEST')}
            loading={generateMutation.isLoading}
          >
            Generate Test Key
          </Button>
          <Button
            onClick={() => generateMutation.mutate('LIVE')}
            loading={generateMutation.isLoading}
          >
            Generate Live Key
          </Button>
        </div>
      </div>

      {/* New Key Modal */}
      {showNewKey && (
        <Modal onClose={() => setShowNewKey(null)} title="New API Key Generated">
          <div className="space-y-4">
            <div className="bg-yellow-50 border border-yellow-200 rounded-lg p-4">
              <p className="text-yellow-800 text-sm font-medium">
                ⚠️ Copy this key now. It won't be shown again.
              </p>
            </div>
            <div className="bg-dark-900 text-green-400 p-4 rounded-lg font-mono text-sm break-all">
              {showNewKey}
            </div>
            <Button
              onClick={() => {
                navigator.clipboard.writeText(showNewKey);
              }}
              variant="secondary"
              className="w-full"
            >
              📋 Copy to Clipboard
            </Button>
          </div>
        </Modal>
      )}

      {/* Keys Table */}
      <div className="bg-white rounded-xl shadow-sm overflow-hidden">
        <table className="w-full">
          <thead>
            <tr className="border-b">
              <th className="px-6 py-3 text-left text-xs font-medium text-dark-700 uppercase">Key</th>
              <th className="px-6 py-3 text-left text-xs font-medium text-dark-700 uppercase">Type</th>
              <th className="px-6 py-3 text-left text-xs font-medium text-dark-700 uppercase">Status</th>
              <th className="px-6 py-3 text-left text-xs font-medium text-dark-700 uppercase">Created</th>
              <th className="px-6 py-3 text-left text-xs font-medium text-dark-700 uppercase">Actions</th>
            </tr>
          </thead>
          <tbody className="divide-y">
            {keys?.data?.data?.map((key) => (
              <tr key={key.id}>
                <td className="px-6 py-4 font-mono text-sm">
                  {key.prefix}...
                </td>
                <td className="px-6 py-4">
                  <Badge variant={key.type === 'LIVE' ? 'success' : 'warning'}>
                    {key.type}
                  </Badge>
                </td>
                <td className="px-6 py-4">
                  <Badge variant={key.status === 'ACTIVE' ? 'success' : 'danger'}>
                    {key.status}
                  </Badge>
                </td>
                <td className="px-6 py-4 text-sm text-dark-700">
                  {formatDate(key.createdAt)}
                </td>
                <td className="px-6 py-4">
                  {key.status === 'ACTIVE' && (
                    <Button
                      variant="danger"
                      size="sm"
                      onClick={() => {
                        if (confirm('Revoke this key? This cannot be undone.')) {
                          revokeMutation.mutate(key.id);
                        }
                      }}
                    >
                      Revoke
                    </Button>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {/* Integration Guide */}
      <div className="bg-dark-50 rounded-xl p-6">
        <h3 className="font-semibold text-dark-900 mb-3">Quick Integration</h3>
        <pre className="bg-dark-900 text-green-400 p-4 rounded-lg text-sm overflow-x-auto">
{`// Add to your request headers
const response = await fetch('https://api.payflow.io/v1/orders', {
  method: 'POST',
  headers: {
    'Content-Type': 'application/json',
    'X-Api-Key': 'pk_live_your_key_here'
  },
  body: JSON.stringify({
    amount: 50000,  // ₹500.00 in paise
    currency: 'INR',
    description: 'Order #1234'
  })
});`}
        </pre>
      </div>
    </div>
  );
}
```

## Webhook Configuration Page

```tsx
// src/pages/settings/WebhooksPage.tsx
import { useState } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { merchantApi } from '@/api/merchants.api';

const WEBHOOK_EVENTS = [
  { id: 'payment.created', label: 'Payment Created' },
  { id: 'payment.authorized', label: 'Payment Authorized' },
  { id: 'payment.captured', label: 'Payment Captured' },
  { id: 'payment.failed', label: 'Payment Failed' },
  { id: 'refund.initiated', label: 'Refund Initiated' },
  { id: 'refund.completed', label: 'Refund Completed' },
  { id: 'settlement.completed', label: 'Settlement Completed' },
];

const webhookSchema = z.object({
  url: z.string().url('Must be a valid HTTPS URL').startsWith('https://', 'URL must use HTTPS'),
  events: z.array(z.string()).min(1, 'Select at least one event'),
});

type WebhookFormData = z.infer<typeof webhookSchema>;

export function WebhooksPage() {
  const [showSecret, setShowSecret] = useState<string | null>(null);
  const queryClient = useQueryClient();

  const { data: webhooks } = useQuery({
    queryKey: ['webhooks'],
    queryFn: () => merchantApi.getWebhooks(),
  });

  const createMutation = useMutation({
    mutationFn: (data: WebhookFormData) => merchantApi.createWebhook(data),
    onSuccess: (data) => {
      setShowSecret(data.data.data.secret);
      queryClient.invalidateQueries(['webhooks']);
      reset();
    },
  });

  const { register, handleSubmit, formState: { errors }, reset, watch } = useForm<WebhookFormData>({
    resolver: zodResolver(webhookSchema),
    defaultValues: { events: [] },
  });

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-bold text-dark-900">Webhooks</h1>
        <p className="text-dark-700 mt-1">
          Configure endpoints to receive real-time notifications for payment events.
        </p>
      </div>

      {/* Create Webhook Form */}
      <div className="bg-white rounded-xl p-6 shadow-sm">
        <h2 className="text-lg font-semibold mb-4">Add Webhook Endpoint</h2>
        <form onSubmit={handleSubmit((data) => createMutation.mutate(data))} className="space-y-4">
          <div>
            <label className="block text-sm font-medium text-dark-700 mb-1">
              Endpoint URL
            </label>
            <input
              type="url"
              placeholder="https://your-server.com/webhooks/payflow"
              className="w-full px-4 py-2 border rounded-lg focus:ring-2 focus:ring-primary-500"
              {...register('url')}
            />
            {errors.url && <p className="text-red-500 text-sm mt-1">{errors.url.message}</p>}
          </div>

          <div>
            <label className="block text-sm font-medium text-dark-700 mb-2">
              Events to Subscribe
            </label>
            <div className="grid grid-cols-2 gap-2">
              {WEBHOOK_EVENTS.map(event => (
                <label key={event.id} className="flex items-center gap-2 p-2 rounded hover:bg-dark-50">
                  <input
                    type="checkbox"
                    value={event.id}
                    className="rounded text-primary-600"
                    {...register('events')}
                  />
                  <span className="text-sm">{event.label}</span>
                </label>
              ))}
            </div>
            {errors.events && <p className="text-red-500 text-sm mt-1">{errors.events.message}</p>}
          </div>

          <Button type="submit" loading={createMutation.isLoading}>
            Create Webhook
          </Button>
        </form>
      </div>

      {/* Existing Webhooks */}
      <div className="bg-white rounded-xl shadow-sm overflow-hidden">
        <div className="px-6 py-4 border-b">
          <h2 className="text-lg font-semibold">Active Webhooks</h2>
        </div>
        {webhooks?.data?.data?.map((wh) => (
          <div key={wh.id} className="px-6 py-4 border-b last:border-0">
            <div className="flex items-center justify-between">
              <div>
                <p className="font-mono text-sm">{wh.url}</p>
                <p className="text-xs text-dark-700 mt-1">
                  Secret: {wh.secret} • Events: {wh.events.join(', ')}
                </p>
              </div>
              <Badge variant={wh.status === 'ACTIVE' ? 'success' : 'danger'}>
                {wh.status}
              </Badge>
            </div>
          </div>
        ))}
      </div>

      {/* Verification Guide */}
      <div className="bg-dark-50 rounded-xl p-6">
        <h3 className="font-semibold mb-3">Verifying Webhook Signatures</h3>
        <pre className="bg-dark-900 text-green-400 p-4 rounded-lg text-sm overflow-x-auto">
{`// Node.js verification example
const crypto = require('crypto');

function verifyWebhook(payload, header, secret) {
  const [tPart, vPart] = header.split(',');
  const timestamp = tPart.replace('t=', '');
  const signature = vPart.replace('v1=', '');

  const signingString = timestamp + '.' + payload;
  const expected = crypto
    .createHmac('sha256', secret)
    .update(signingString)
    .digest('hex');

  // Timing-safe comparison
  return crypto.timingSafeEqual(
    Buffer.from(signature), Buffer.from(expected)
  );
}`}
        </pre>
      </div>
    </div>
  );
}
```

## Profile Settings Page

```tsx
// src/pages/settings/ProfilePage.tsx
import { useForm } from 'react-hook-form';
import { useMutation } from '@tanstack/react-query';
import { useAuth } from '@/context/AuthContext';
import { merchantApi } from '@/api/merchants.api';
import { Button } from '@/components/common/Button';
import { Input } from '@/components/common/Input';

export function ProfilePage() {
  const { user } = useAuth();

  const { register, handleSubmit } = useForm({
    defaultValues: {
      businessName: '',
      businessType: '',
      gstin: '',
      panNumber: '',
      settlementAccountNumber: '',
      settlementIfsc: '',
    },
  });

  const updateMutation = useMutation({
    mutationFn: (data: any) => merchantApi.updateProfile(data),
  });

  return (
    <div className="space-y-6">
      <h1 className="text-2xl font-bold text-dark-900">Profile Settings</h1>

      {/* Account Info */}
      <div className="bg-white rounded-xl p-6 shadow-sm">
        <h2 className="text-lg font-semibold mb-4">Account Information</h2>
        <div className="grid grid-cols-2 gap-4">
          <div>
            <label className="text-sm text-dark-700">Name</label>
            <p className="font-medium">{user?.fullName}</p>
          </div>
          <div>
            <label className="text-sm text-dark-700">Email</label>
            <p className="font-medium">{user?.email}</p>
          </div>
          <div>
            <label className="text-sm text-dark-700">Role</label>
            <p className="font-medium">{user?.role}</p>
          </div>
          <div>
            <label className="text-sm text-dark-700">Member Since</label>
            <p className="font-medium">{formatDate(user?.createdAt || '')}</p>
          </div>
        </div>
      </div>

      {/* Business Details */}
      <form onSubmit={handleSubmit((data) => updateMutation.mutate(data))}
            className="bg-white rounded-xl p-6 shadow-sm space-y-4">
        <h2 className="text-lg font-semibold mb-4">Business Details</h2>

        <div className="grid grid-cols-2 gap-4">
          <Input label="Business Name" {...register('businessName')} />
          <Input label="Business Type" {...register('businessType')} />
          <Input label="GSTIN" placeholder="22AAAAA0000A1Z5" {...register('gstin')} />
          <Input label="PAN Number" placeholder="ABCDE1234F" {...register('panNumber')} />
        </div>

        <h3 className="font-medium pt-4 border-t">Settlement Account</h3>
        <div className="grid grid-cols-2 gap-4">
          <Input label="Account Number" {...register('settlementAccountNumber')} />
          <Input label="IFSC Code" placeholder="SBIN0001234" {...register('settlementIfsc')} />
        </div>

        <Button type="submit" loading={updateMutation.isLoading}>
          Save Changes
        </Button>
      </form>
    </div>
  );
}
```

## Settings Page Navigation (Sidebar)

```tsx
const SETTINGS_LINKS = [
  { path: '/settings/api-keys', label: 'API Keys', icon: '🔑' },
  { path: '/settings/webhooks', label: 'Webhooks', icon: '🔔' },
  { path: '/settings/profile', label: 'Profile', icon: '👤' },
];
```
