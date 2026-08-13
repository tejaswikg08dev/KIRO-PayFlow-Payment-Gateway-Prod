import apiClient from './apiClient';
import { AuthResponse, LoginRequest, RegisterRequest } from '@/types/auth.types';
import { TOKEN_KEY, REFRESH_TOKEN_KEY, USER_KEY } from '@/utils/constants';

const MERCHANT_ID_KEY = 'payflow_merchant_id';

export const authService = {
  async login(data: LoginRequest): Promise<AuthResponse> {
    const response = await apiClient.post('/v1/auth/login', data);
    const authData = response.data.data as AuthResponse;
    localStorage.setItem(TOKEN_KEY, authData.accessToken);
    localStorage.setItem(REFRESH_TOKEN_KEY, authData.refreshToken);
    localStorage.setItem(USER_KEY, JSON.stringify(authData.user));
    // Fetch merchant profile after login
    await this.fetchAndStoreMerchantId();
    return authData;
  },

  async register(data: RegisterRequest): Promise<AuthResponse> {
    const payload = {
      fullName: `${data.firstName} ${data.lastName}`.trim(),
      email: data.email,
      password: data.password,
      role: 'MERCHANT',
    };
    const response = await apiClient.post('/v1/auth/register', payload);
    const authData = response.data.data as AuthResponse;
    localStorage.setItem(TOKEN_KEY, authData.accessToken);
    localStorage.setItem(REFRESH_TOKEN_KEY, authData.refreshToken);
    localStorage.setItem(USER_KEY, JSON.stringify(authData.user));

    // Auto-create merchant after registration
    try {
      const merchantPayload = {
        name: data.businessName || `${data.firstName} ${data.lastName}`.trim(),
        email: data.email,
        businessType: data.businessType || 'Individual',
        mdrRate: 2.0,
      };
      const merchantRes = await apiClient.post('/v1/merchants', merchantPayload);
      const merchantId = merchantRes.data.data?.id;
      if (merchantId) {
        localStorage.setItem(MERCHANT_ID_KEY, merchantId);
      }
    } catch {
      // Merchant creation may fail if already exists — not critical
    }

    return authData;
  },

  async fetchAndStoreMerchantId(): Promise<string | null> {
    try {
      const response = await apiClient.get('/v1/merchants');
      const merchants = response.data.data;
      if (Array.isArray(merchants) && merchants.length > 0) {
        const merchantId = merchants[0].id;
        localStorage.setItem(MERCHANT_ID_KEY, merchantId);
        return merchantId;
      }
    } catch {
      // Not critical — user may not have a merchant yet
    }
    return null;
  },

  getMerchantId(): string | null {
    return localStorage.getItem(MERCHANT_ID_KEY);
  },

  async refreshToken(): Promise<AuthResponse> {
    const refreshToken = localStorage.getItem(REFRESH_TOKEN_KEY);
    const response = await apiClient.post('/v1/auth/refresh', {
      refreshToken,
    });
    const authData = response.data.data as AuthResponse;
    localStorage.setItem(TOKEN_KEY, authData.accessToken);
    localStorage.setItem(REFRESH_TOKEN_KEY, authData.refreshToken);
    return authData;
  },

  logout(): void {
    localStorage.removeItem(TOKEN_KEY);
    localStorage.removeItem(REFRESH_TOKEN_KEY);
    localStorage.removeItem(USER_KEY);
    localStorage.removeItem(MERCHANT_ID_KEY);
  },

  isAuthenticated(): boolean {
    return !!localStorage.getItem(TOKEN_KEY);
  },

  getStoredUser() {
    const user = localStorage.getItem(USER_KEY);
    return user ? JSON.parse(user) : null;
  },
};
