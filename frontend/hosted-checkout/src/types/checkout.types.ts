export interface OrderDetails {
  id: string;
  merchantName: string;
  merchantLogo?: string;
  amount: number;
  currency: string;
  description: string;
  customerEmail: string;
  customerPhone: string;
  expiresAt: string;
  status: 'ACTIVE' | 'EXPIRED' | 'PAID';
}

export interface CardPaymentRequest {
  orderId: string;
  method: 'CARD';
  cardNumber: string;
  cardExpiry: string;
  cardCvv: string;
  cardHolderName: string;
}

export interface UpiPaymentRequest {
  orderId: string;
  method: 'UPI';
  upiId: string;
}

export interface NetBankingPaymentRequest {
  orderId: string;
  method: 'NET_BANKING';
  bankCode: string;
}

export type PaymentRequest = CardPaymentRequest | UpiPaymentRequest | NetBankingPaymentRequest;

export interface PaymentStatusResponse {
  paymentId: string;
  orderId: string;
  status: 'SUCCESS' | 'FAILED' | 'PENDING' | 'PROCESSING';
  amount: number;
  currency: string;
  method: string;
  merchantName: string;
  errorMessage?: string;
  createdAt: string;
}

export type PaymentMethodType = 'CARD' | 'UPI' | 'NET_BANKING';

export interface BankOption {
  code: string;
  name: string;
  logo?: string;
}
