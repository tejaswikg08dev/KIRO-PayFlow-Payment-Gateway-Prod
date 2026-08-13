# Phase 4 · Part 15C — Frontend Settings & Configuration Pages

| Field | Value |
|-------|-------|
| **Project** | PayFlow Payment Gateway |
| **Phase** | 4 — Frontend Development |
| **Part** | 15C — Settings Pages (API Keys, Webhooks, Profile) |
| **Previous** | [Part 15B — Feature Pages](phase4-part15b-frontend-features.md) |
| **Next** | [Part 16 — Hosted Checkout](phase4-part16-hosted-checkout.md) |
| **Time** | ~2 hours |
| **Difficulty** | ★★★☆☆ Intermediate |
| **Prerequisites** | React, TanStack Query mutations, Modal components |

---

## Table of Contents

1. [API Keys Page](#1-api-keys-page)
2. [Webhooks Page](#2-webhooks-page)
3. [Settings Page](#3-settings-page)
4. [Toast Notifications](#4-toast-notifications)
5. [What You Learned](#what-you-learned)
6. [Common Errors & Fixes](#common-errors--fixes)

---

## 1. API Keys Page

Merchants generate and manage API keys for programmatic access. Keys are masked after creation (only shown once).

```
+------------------------------------------------------------------+
|  API KEYS                                    [+ Generate New Key]  |
+------------------------------------------------------------------+
|  Name        | Key                  | Created     | Actions       |
|  Production  | pk_live_****a3f9     | 2024-01-15  | [Revoke]      |
|  Test Key    | pk_test_****b2c1     | 2024-01-10  | [Revoke]      |
+------------------------------------------------------------------+
```

### Generate Key Flow

```tsx
// src/pages/ApiKeys.tsx
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { apiClient } from '@/lib/apiClient';
import { toast } from 'react-hot-toast';
import { ConfirmModal } from '@/components/ui/ConfirmModal';

export function ApiKeysPage() {
  const queryClient = useQueryClient();
  const [newKey, setNewKey] = useState<string | null>(null);
  const [revokeTarget, setRevokeTarget] = useState<string | null>(null);

  const { data: keys, isLoading } = useQuery({
    queryKey: ['api-keys'],
    queryFn: () => apiClient.get('/v1/merchants/api-keys'),
  });

  const generateMutation = useMutation({
    mutationFn: (name: string) => apiClient.post('/v1/merchants/api-keys', { name }),
    onSuccess: (data) => {
      setNewKey(data.key); // Show full key ONCE
      queryClient.invalidateQueries(['api-keys']);
      toast.success('API key generated successfully');
    },
    onError: () => toast.error('Failed to generate API key'),
  });

  const revokeMutation = useMutation({
    mutationFn: (keyId: string) => apiClient.delete(`/v1/merchants/api-keys/${keyId}`),
    onSuccess: () => {
      queryClient.invalidateQueries(['api-keys']);
      toast.success('API key revoked');
      setRevokeTarget(null);
    },
  });

  return (
    <div className="p-6 space-y-4">
      <div className="flex justify-between items-center">
        <h1 className="text-2xl font-bold">API Keys</h1>
        <button onClick={() => generateMutation.mutate('New Key')}
                className="btn btn-primary">
          + Generate New Key
        </button>
      </div>

      {/* Show new key banner (only once) */}
      {newKey && (
        <div className="bg-yellow-50 border border-yellow-200 p-4 rounded-lg">
          <p className="font-medium text-yellow-800">Copy your key now — it won't be shown again!</p>
          <code className="block mt-2 bg-white p-2 rounded font-mono text-sm">{newKey}</code>
          <button onClick={() => { navigator.clipboard.writeText(newKey); toast.success('Copied!'); }}
                  className="mt-2 text-sm text-blue-600 hover:underline">
            Copy to clipboard
          </button>
        </div>
      )}

      {/* Keys Table */}
      <table className="w-full bg-white rounded-lg shadow">
        <thead className="bg-gray-50">
          <tr>
            <th className="px-4 py-3 text-left">Name</th>
            <th className="px-4 py-3 text-left">Key</th>
            <th className="px-4 py-3 text-left">Created</th>
            <th className="px-4 py-3 text-left">Actions</th>
          </tr>
        </thead>
        <tbody>
          {keys?.map((key) => (
            <tr key={key.id} className="border-t">
              <td className="px-4 py-3">{key.name}</td>
              <td className="px-4 py-3 font-mono text-sm">{key.maskedKey}</td>
              <td className="px-4 py-3">{formatDate(key.createdAt)}</td>
              <td className="px-4 py-3">
                <button onClick={() => setRevokeTarget(key.id)}
                        className="text-red-600 hover:text-red-800 text-sm">
                  Revoke
                </button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>

      {/* Revoke Confirmation Modal */}
      <ConfirmModal
        isOpen={!!revokeTarget}
        title="Revoke API Key"
        message="This action cannot be undone. Any integrations using this key will stop working."
        confirmText="Revoke Key"
        onConfirm={() => revokeMutation.mutate(revokeTarget!)}
        onCancel={() => setRevokeTarget(null)}
      />
    </div>
  );
}
```

---

## 2. Webhooks Page

Merchants register webhook URLs, select event types, and view delivery logs.

### Webhook Configuration

```tsx
// src/pages/Webhooks.tsx
export function WebhooksPage() {
  const [showForm, setShowForm] = useState(false);
  const { data: webhooks } = useQuery({ queryKey: ['webhooks'], queryFn: fetchWebhooks });

  const EVENTS = [
    'payment.authorized', 'payment.captured', 'payment.failed',
    'refund.created', 'refund.processed', 'settlement.completed',
  ];

  const createMutation = useMutation({
    mutationFn: (payload) => apiClient.post('/v1/merchants/webhooks', payload),
    onSuccess: () => {
      queryClient.invalidateQueries(['webhooks']);
      toast.success('Webhook endpoint added');
      setShowForm(false);
    },
    onError: () => toast.error('Failed to add webhook'),
  });

  return (
    <div className="p-6 space-y-4">
      <div className="flex justify-between items-center">
        <h1 className="text-2xl font-bold">Webhooks</h1>
        <button onClick={() => setShowForm(true)} className="btn btn-primary">
          + Add Endpoint
        </button>
      </div>

      {/* Webhook List */}
      {webhooks?.map((wh) => (
        <div key={wh.id} className="bg-white p-4 rounded-lg shadow">
          <div className="flex justify-between">
            <code className="text-sm">{wh.url}</code>
            <StatusBadge status={wh.active ? 'ACTIVE' : 'DISABLED'} />
          </div>
          <div className="mt-2 flex gap-2 flex-wrap">
            {wh.events.map(e => (
              <span key={e} className="px-2 py-0.5 bg-gray-100 rounded text-xs">{e}</span>
            ))}
          </div>
          <Link to={`/settings/webhooks/${wh.id}/logs`} className="text-sm text-blue-600 mt-2 block">
            View delivery logs →
          </Link>
        </div>
      ))}

      {/* Add Webhook Modal */}
      {showForm && (
        <WebhookForm events={EVENTS} onSubmit={createMutation.mutate} onCancel={() => setShowForm(false)} />
      )}
    </div>
  );
}
```

### Delivery Logs View

```tsx
// src/pages/WebhookLogs.tsx
export function WebhookLogs() {
  const { webhookId } = useParams();
  const { data: logs } = useQuery({
    queryKey: ['webhook-logs', webhookId],
    queryFn: () => apiClient.get(`/v1/merchants/webhooks/${webhookId}/deliveries`),
  });

  return (
    <div className="p-6">
      <h2 className="text-xl font-bold mb-4">Delivery Logs</h2>
      <table className="w-full bg-white rounded-lg shadow">
        <thead><tr>
          <th>Event</th><th>Status</th><th>Response Code</th><th>Time</th>
        </tr></thead>
        <tbody>
          {logs?.map(log => (
            <tr key={log.id} className="border-t">
              <td className="px-4 py-2">{log.eventType}</td>
              <td className="px-4 py-2"><StatusBadge status={log.status} /></td>
              <td className="px-4 py-2">{log.httpStatus || '—'}</td>
              <td className="px-4 py-2">{formatDateTime(log.attemptedAt)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
```

---

## 3. Settings Page

Profile and business information updates.

```tsx
// src/pages/Settings.tsx
export function SettingsPage() {
  const { data: merchant } = useQuery({ queryKey: ['merchant-profile'], queryFn: fetchProfile });

  const updateMutation = useMutation({
    mutationFn: (data) => apiClient.put('/v1/merchants/profile', data),
    onSuccess: () => {
      queryClient.invalidateQueries(['merchant-profile']);
      toast.success('Settings saved successfully');
    },
    onError: () => toast.error('Failed to save settings'),
  });

  return (
    <div className="p-6 max-w-2xl space-y-6">
      <h1 className="text-2xl font-bold">Settings</h1>

      {/* Profile Section */}
      <form onSubmit={handleSubmit(updateMutation.mutate)} className="bg-white p-6 rounded-lg shadow space-y-4">
        <h2 className="font-semibold text-lg">Profile</h2>
        <InputField label="Business Name" name="businessName" defaultValue={merchant?.businessName} />
        <InputField label="Contact Email" name="email" type="email" defaultValue={merchant?.email} />
        <InputField label="Phone" name="phone" defaultValue={merchant?.phone} />

        <h2 className="font-semibold text-lg mt-6">Business Information</h2>
        <InputField label="GST Number" name="gstNumber" defaultValue={merchant?.gstNumber} />
        <InputField label="PAN" name="pan" defaultValue={merchant?.pan} />
        <InputField label="Website URL" name="websiteUrl" defaultValue={merchant?.websiteUrl} />

        <button type="submit" className="btn btn-primary" disabled={updateMutation.isLoading}>
          {updateMutation.isLoading ? 'Saving...' : 'Save Changes'}
        </button>
      </form>
    </div>
  );
}
```

---

## 4. Toast Notifications

PayFlow uses `react-hot-toast` for non-blocking feedback on all mutations.

```tsx
// src/main.tsx — Add Toaster at root
import { Toaster } from 'react-hot-toast';

ReactDOM.createRoot(document.getElementById('root')!).render(
  <QueryClientProvider client={queryClient}>
    <BrowserRouter>
      <App />
      <Toaster
        position="top-right"
        toastOptions={{
          duration: 4000,
          style: { borderRadius: '8px', padding: '12px 16px' },
          success: { iconTheme: { primary: '#10B981', secondary: '#fff' } },
          error: { iconTheme: { primary: '#EF4444', secondary: '#fff' } },
        }}
      />
    </BrowserRouter>
  </QueryClientProvider>
);
```

**Usage pattern across the app:**

```tsx
// On success
toast.success('Payment refunded successfully');

// On error
toast.error('Failed to process refund. Try again.');

// Loading state
const toastId = toast.loading('Processing...');
// ...later
toast.dismiss(toastId);
toast.success('Done!');
```

---

## What You Learned

| # | Topic | Key Takeaway |
|---|-------|-------------|
| 1 | API Key management | Keys are shown once at creation; stored masked afterward |
| 2 | Confirm modals | Destructive actions (revoke) require explicit user confirmation |
| 3 | Webhook configuration | Event-type selection gives merchants fine-grained control |
| 4 | Delivery logs | Transparency into webhook success/failure builds trust |
| 5 | Profile settings | Simple CRUD form with mutation + cache invalidation |
| 6 | Toast notifications | Non-blocking feedback at the UI layer for every action |

---

## Common Errors & Fixes

| Error | Cause | Fix |
|-------|-------|-----|
| Key shows as `undefined` after generate | Not capturing response from mutation | Use `onSuccess: (data) => setNewKey(data.key)` |
| Revoke modal doesn't close | `onCancel` not wired | Pass `onCancel={() => setRevokeTarget(null)}` to modal |
| Webhook form submits empty events array | Checkbox state not tracked | Use `useState<string[]>([])` for selected events |
| Toast appears behind modal | z-index conflict | Set Toaster z-index higher: `containerStyle={{ zIndex: 9999 }}` |
| Settings form loses data on re-render | `defaultValue` doesn't update | Use `key={merchant?.id}` on form or switch to controlled inputs |

---

<div align="center">

**[← Part 15B: Feature Pages](phase4-part15b-frontend-features.md)** | **[Documentation Index](../README.md)** | **[Part 16: Hosted Checkout →](phase4-part16-hosted-checkout.md)**

</div>
