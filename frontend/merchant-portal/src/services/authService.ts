import apiClient from './apiClient';
import { AuthResponse, LoginRequest, RegisterRequest } from '@/types/auth.types';
import { TOKEN_KEY, REFRESH_TOKEN_KEY, USER_KEY } from '@/utils/constants';

export const authService = {
  async login(data: LoginRequest): Promise<AuthResponse> {
    const response = await apiClient.post<AuthResponse>('/api/auth/login', data);
    const authData = response.data;
    localStorage.setItem(TOKEN_KEY, authData.accessToken);
    localStorage.setItem(REFRESH_TOKEN_KEY, authData.refreshToken);
    localStorage.setItem(USER_KEY, JSON.stringify(authData.user));
    return authData;
  },

  async register(data: RegisterRequest): Promise<AuthResponse> {
    const response = await apiClient.post<AuthResponse>('/api/auth/register', data);
    const authData = response.data;
    localStorage.setItem(TOKEN_KEY, authData.accessToken);
    localStorage.setItem(REFRESH_TOKEN_KEY, authData.refreshToken);
    localStorage.setItem(USER_KEY, JSON.stringify(authData.user));
    return authData;
  },

  async refreshToken(): Promise<AuthResponse> {
    const refreshToken = localStorage.getItem(REFRESH_TOKEN_KEY);
    const response = await apiClient.post<AuthResponse>('/api/auth/refresh', {
      refreshToken,
    });
    const authData = response.data;
    localStorage.setItem(TOKEN_KEY, authData.accessToken);
    localStorage.setItem(REFRESH_TOKEN_KEY, authData.refreshToken);
    return authData;
  },

  logout(): void {
    localStorage.removeItem(TOKEN_KEY);
    localStorage.removeItem(REFRESH_TOKEN_KEY);
    localStorage.removeItem(USER_KEY);
  },

  isAuthenticated(): boolean {
    return !!localStorage.getItem(TOKEN_KEY);
  },

  getStoredUser() {
    const user = localStorage.getItem(USER_KEY);
    return user ? JSON.parse(user) : null;
  },
};
