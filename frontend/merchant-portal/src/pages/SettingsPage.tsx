import { useState, useEffect } from 'react';
import { useQuery, useMutation } from '@tanstack/react-query';
import { merchantService } from '@/services/merchantService';
import { LoadingSpinner } from '@/components/common/LoadingSpinner';
import { Toast } from '@/components/common/Toast';
import { StatusBadge } from '@/components/common/StatusBadge';

export default function SettingsPage() {
  const [toast, setToast] = useState<{ message: string; type: 'success' | 'error' } | null>(null);
  const [formData, setFormData] = useState({
    businessName: '',
    email: '',
    phone: '',
    website: '',
  });

  const { data: merchant, isLoading } = useQuery({
    queryKey: ['merchantProfile'],
    queryFn: () => merchantService.getProfile(),
  });

  useEffect(() => {
    if (merchant) {
      setFormData({
        businessName: merchant.businessName,
        email: merchant.email,
        phone: merchant.phone,
        website: merchant.website,
      });
    }
  }, [merchant]);

  const updateMutation = useMutation({
    mutationFn: (data: typeof formData) => merchantService.updateProfile(data),
    onSuccess: () => {
      setToast({ message: 'Profile updated successfully', type: 'success' });
    },
    onError: () => {
      setToast({ message: 'Failed to update profile', type: 'error' });
    },
  });

  const handleChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    const { name, value } = e.target;
    setFormData((prev) => ({ ...prev, [name]: value }));
  };

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    updateMutation.mutate(formData);
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
      <h2 className="text-2xl font-bold text-gray-900 mb-6">Settings</h2>

      <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
        <div className="lg:col-span-2">
          <div className="card">
            <h3 className="text-lg font-semibold mb-4">Business Information</h3>
            <form onSubmit={handleSubmit} className="space-y-4">
              <div>
                <label className="block text-sm font-medium text-gray-700 mb-1">
                  Business Name
                </label>
                <input
                  type="text"
                  name="businessName"
                  value={formData.businessName}
                  onChange={handleChange}
                  className="input-field"
                />
              </div>
              <div>
                <label className="block text-sm font-medium text-gray-700 mb-1">Email</label>
                <input
                  type="email"
                  name="email"
                  value={formData.email}
                  onChange={handleChange}
                  className="input-field"
                />
              </div>
              <div>
                <label className="block text-sm font-medium text-gray-700 mb-1">Phone</label>
                <input
                  type="tel"
                  name="phone"
                  value={formData.phone}
                  onChange={handleChange}
                  className="input-field"
                />
              </div>
              <div>
                <label className="block text-sm font-medium text-gray-700 mb-1">Website</label>
                <input
                  type="url"
                  name="website"
                  value={formData.website}
                  onChange={handleChange}
                  className="input-field"
                  placeholder="https://"
                />
              </div>
              <button
                type="submit"
                disabled={updateMutation.isPending}
                className="btn-primary"
              >
                {updateMutation.isPending ? <LoadingSpinner size="sm" /> : 'Save Changes'}
              </button>
            </form>
          </div>
        </div>

        <div>
          <div className="card">
            <h3 className="text-lg font-semibold mb-4">Account Status</h3>
            <div className="space-y-3">
              <div className="flex items-center justify-between">
                <span className="text-sm text-gray-600">KYC Status</span>
                <StatusBadge status={merchant?.kycStatus || 'PENDING'} />
              </div>
              <div className="flex items-center justify-between">
                <span className="text-sm text-gray-600">Account</span>
                <StatusBadge status={merchant?.isActive ? 'ACTIVE' : 'INACTIVE'} />
              </div>
              <div className="flex items-center justify-between">
                <span className="text-sm text-gray-600">Business Type</span>
                <span className="text-sm font-medium">{merchant?.businessType}</span>
              </div>
            </div>
          </div>
        </div>
      </div>

      {toast && <Toast message={toast.message} type={toast.type} onClose={() => setToast(null)} />}
    </div>
  );
}
