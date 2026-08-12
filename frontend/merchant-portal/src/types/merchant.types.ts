export interface Merchant {
  id: string;
  businessName: string;
  businessType: string;
  email: string;
  phone: string;
  website: string;
  address: MerchantAddress;
  kycStatus: 'PENDING' | 'VERIFIED' | 'REJECTED';
  isActive: boolean;
  createdAt: string;
}

export interface MerchantAddress {
  line1: string;
  line2?: string;
  city: string;
  state: string;
  postalCode: string;
  country: string;
}

export interface ApiKey {
  id: string;
  name: string;
  keyPrefix: string;
  mode: 'TEST' | 'LIVE';
  permissions: string[];
  isActive: boolean;
  lastUsedAt?: string;
  createdAt: string;
  expiresAt?: string;
}

export interface ApiKeyCreateRequest {
  name: string;
  mode: 'TEST' | 'LIVE';
  permissions: string[];
  expiresInDays?: number;
}

export interface ApiKeyCreateResponse {
  id: string;
  name: string;
  key: string;
  mode: 'TEST' | 'LIVE';
  createdAt: string;
}

export interface WebhookConfig {
  id: string;
  url: string;
  events: string[];
  isActive: boolean;
  secret: string;
  createdAt: string;
  updatedAt: string;
}

export interface WebhookDeliveryLog {
  id: string;
  webhookId: string;
  event: string;
  url: string;
  requestBody: string;
  responseStatus: number;
  responseBody: string;
  success: boolean;
  attemptNumber: number;
  deliveredAt: string;
}

export interface WebhookCreateRequest {
  url: string;
  events: string[];
}
