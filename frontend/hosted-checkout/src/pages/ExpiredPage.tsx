import { SecureFooter } from '@/components/SecureFooter';

export default function ExpiredPage() {
  return (
    <div className="min-h-screen bg-gray-100 py-8 px-4 flex flex-col items-center justify-center">
      <div className="max-w-md w-full bg-white rounded-xl shadow-sm border border-gray-200 p-8 text-center">
        <div className="w-20 h-20 bg-gray-100 rounded-full flex items-center justify-center mx-auto mb-4">
          <svg className="w-10 h-10 text-gray-400" fill="none" viewBox="0 0 24 24" stroke="currentColor">
            <path
              strokeLinecap="round"
              strokeLinejoin="round"
              strokeWidth={2}
              d="M12 8v4l3 3m6-3a9 9 0 11-18 0 9 9 0 0118 0z"
            />
          </svg>
        </div>
        <h1 className="text-2xl font-bold text-gray-700 mb-2">Payment Link Expired</h1>
        <p className="text-gray-600 mb-6">
          This payment link has expired. Please contact the merchant to generate a new payment link.
        </p>
        <div className="text-sm text-gray-500">
          If you believe this is an error, please contact support.
        </div>
      </div>

      <SecureFooter />
    </div>
  );
}
