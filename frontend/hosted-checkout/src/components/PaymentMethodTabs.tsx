import { PaymentMethodType } from '@/types/checkout.types';

interface PaymentMethodTabsProps {
  active: PaymentMethodType;
  onChange: (method: PaymentMethodType) => void;
}

const methods: { id: PaymentMethodType; label: string; icon: string }[] = [
  { id: 'CARD', label: 'Card', icon: '💳' },
  { id: 'UPI', label: 'UPI', icon: '📱' },
  { id: 'NET_BANKING', label: 'Net Banking', icon: '🏦' },
];

export function PaymentMethodTabs({ active, onChange }: PaymentMethodTabsProps) {
  return (
    <div className="flex border-b border-gray-200">
      {methods.map((method) => (
        <button
          key={method.id}
          onClick={() => onChange(method.id)}
          className={`flex-1 flex items-center justify-center gap-2 py-4 px-3 text-sm font-medium transition-colors ${
            active === method.id
              ? 'text-primary-600 border-b-2 border-primary-600 bg-primary-50'
              : 'text-gray-500 hover:text-gray-700 hover:bg-gray-50'
          }`}
        >
          <span>{method.icon}</span>
          <span>{method.label}</span>
        </button>
      ))}
    </div>
  );
}
