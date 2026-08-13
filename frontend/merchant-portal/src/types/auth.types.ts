export interface User {
  id: string;
  email: string;
  firstName: string;
  lastName: string;
  merchantId: string;
  role: 'ADMIN' | 'MEMBER' | 'VIEWER';
  createdAt: string;
}

export interface LoginRequest {
  email: string;
  password: string;
}

export interface RegisterRequest {
  email: string;
  password: string;
  firstName: string;
  lastName: string;
  businessName: string;
  businessType: string;
  fullName?: string;
}

export interface AuthResponse {
  accessToken: string;
  refreshToken: string;
  expiresIn: number;
  user: User;
}

export interface RefreshTokenRequest {
  refreshToken: string;
}
