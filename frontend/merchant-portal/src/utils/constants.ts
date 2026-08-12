export const API_BASE_URL = 'http://localhost:8080';
export const TOKEN_KEY = 'payflow_access_token';
export const REFRESH_TOKEN_KEY = 'payflow_refresh_token';
export const USER_KEY = 'payflow_user';

export const PAYMENT_STATUSES = [
  'CREATED',
  'PROCESSING',
  'AUTHORIZED',
  'CAPTURED',
  'SETTLED',
  'FAILED',
  'REFUNDED',
  'PARTIALLY_REFUNDED',
  'CANCELLED',
] as const;

export const PAYMENT_METHODS = ['CARD', 'UPI', 'NET_BANKING', 'WALLET'] as const;

export const WEBHOOK_EVENTS = [
  'payment.created',
  'payment.authorized',
  'payment.captured',
  'payment.failed',
  'payment.refunded',
  'order.created',
  'settlement.processed',
] as const;

export const STATUS_COLORS: Record<string, string> = {
  CREATED: 'bg-gray-100 text-gray-800',
  PROCESSING: 'bg-yellow-100 text-yellow-800',
  AUTHORIZED: 'bg-blue-100 text-blue-800',
  CAPTURED: 'bg-green-100 text-green-800',
  SETTLED: 'bg-green-100 text-green-800',
  FAILED: 'bg-red-100 text-red-800',
  REFUNDED: 'bg-purple-100 text-purple-800',
  PARTIALLY_REFUNDED: 'bg-orange-100 text-orange-800',
  CANCELLED: 'bg-gray-100 text-gray-800',
  PENDING: 'bg-yellow-100 text-yellow-800',
  PROCESSED: 'bg-green-100 text-green-800',
};
