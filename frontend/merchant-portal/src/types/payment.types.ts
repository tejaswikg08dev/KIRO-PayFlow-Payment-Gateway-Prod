export type PaymentStatus =
  | 'CREATED'
  | 'PROCESSING'
  | 'AUTHORIZED'
  | 'CAPTURED'
  | 'SETTLED'
  | 'FAILED'
  | 'REFUNDED'
  | 'PARTIALLY_REFUNDED'
  | 'CANCELLED';

export type PaymentMethod = 'CARD' | 'UPI' | 'NET_BANKING' | 'WALLET';

export interface Order {
  id: string;
  merchantId: string;
  amount: number;
  currency: string;
  status: string;
  customerEmail: string;
  customerPhone: string;
  description: string;
  metadata: Record<string, string>;
  createdAt: string;
  updatedAt: string;
}

export interface Payment {
  id: string;
  orderId: string;
  merchantId: string;
  amount: number;
  currency: string;
  status: PaymentStatus;
  method: PaymentMethod;
  cardLast4?: string;
  cardBrand?: string;
  upiId?: string;
  bankName?: string;
  gatewayTransactionId: string;
  errorCode?: string;
  errorMessage?: string;
  createdAt: string;
  updatedAt: string;
}

export interface Refund {
  id: string;
  paymentId: string;
  amount: number;
  currency: string;
  status: 'PENDING' | 'PROCESSED' | 'FAILED';
  reason: string;
  createdAt: string;
}

export interface Transaction {
  id: string;
  orderId: string;
  payment: Payment;
  order: Order;
  refunds: Refund[];
  timeline: TransactionEvent[];
}

export interface TransactionEvent {
  event: string;
  status: string;
  timestamp: string;
  details?: string;
}

export interface TransactionFilter {
  status?: PaymentStatus;
  method?: PaymentMethod;
  startDate?: string;
  endDate?: string;
  search?: string;
  page: number;
  size: number;
}

export interface PaginatedResponse<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  page: number;
  size: number;
}

export interface RefundRequest {
  paymentId: string;
  amount: number;
  reason: string;
}
