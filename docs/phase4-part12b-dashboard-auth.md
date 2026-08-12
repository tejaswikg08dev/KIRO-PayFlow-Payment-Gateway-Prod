# Phase 4 Part 12b: Dashboard Authentication

## Overview

This document covers the authentication flow in the merchant dashboard: login, registration, AuthContext for state management, protected routes, and JWT interceptor for automatic token management.

## Auth Context

```tsx
// src/context/AuthContext.tsx
import { createContext, useContext, useState, useEffect, ReactNode } from 'react';
import { useNavigate } from 'react-router-dom';
import { authApi } from '@/api/auth.api';
import type { User, LoginRequest, RegisterRequest } from '@/types/auth.types';

interface AuthContextType {
  user: User | null;
  isAuthenticated: boolean;
  isLoading: boolean;
  login: (data: LoginRequest) => Promise<void>;
  register: (data: RegisterRequest) => Promise<void>;
  logout: () => void;
}

const AuthContext = createContext<AuthContextType | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<User | null>(null);
  const [isLoading, setIsLoading] = useState(true);
  const navigate = useNavigate();

  // Check for existing session on mount
  useEffect(() => {
    const token = localStorage.getItem('accessToken');
    if (token) {
      fetchCurrentUser();
    } else {
      setIsLoading(false);
    }
  }, []);

  const fetchCurrentUser = async () => {
    try {
      const response = await authApi.getMe();
      setUser(response.data.data);
    } catch {
      localStorage.clear();
    } finally {
      setIsLoading(false);
    }
  };

  const login = async (data: LoginRequest) => {
    const response = await authApi.login(data);
    const { accessToken, refreshToken, user: userData } = response.data.data;

    localStorage.setItem('accessToken', accessToken);
    localStorage.setItem('refreshToken', refreshToken);
    setUser(userData);
    navigate('/dashboard');
  };

  const register = async (data: RegisterRequest) => {
    const response = await authApi.register(data);
    const { accessToken, refreshToken, user: userData } = response.data.data;

    localStorage.setItem('accessToken', accessToken);
    localStorage.setItem('refreshToken', refreshToken);
    setUser(userData);
    navigate('/dashboard');
  };

  const logout = () => {
    authApi.logout().catch(() => {}); // Best effort
    localStorage.clear();
    setUser(null);
    navigate('/login');
  };

  return (
    <AuthContext.Provider value={{
      user,
      isAuthenticated: !!user,
      isLoading,
      login,
      register,
      logout,
    }}>
      {children}
    </AuthContext.Provider>
  );
}

export function useAuth() {
  const context = useContext(AuthContext);
  if (!context) {
    throw new Error('useAuth must be used within AuthProvider');
  }
  return context;
}
```

## Login Page

```tsx
// src/pages/auth/LoginPage.tsx
import { useState } from 'react';
import { Link } from 'react-router-dom';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { useAuth } from '@/context/AuthContext';
import { Button } from '@/components/common/Button';
import { Input } from '@/components/common/Input';

const loginSchema = z.object({
  email: z.string().email('Invalid email address'),
  password: z.string().min(8, 'Password must be at least 8 characters'),
});

type LoginFormData = z.infer<typeof loginSchema>;

export function LoginPage() {
  const { login } = useAuth();
  const [error, setError] = useState('');
  const [isSubmitting, setIsSubmitting] = useState(false);

  const { register, handleSubmit, formState: { errors } } = useForm<LoginFormData>({
    resolver: zodResolver(loginSchema),
  });

  const onSubmit = async (data: LoginFormData) => {
    setError('');
    setIsSubmitting(true);
    try {
      await login(data);
    } catch (err: any) {
      setError(err.response?.data?.message || 'Login failed');
    } finally {
      setIsSubmitting(false);
    }
  };

  return (
    <div className="min-h-screen flex items-center justify-center bg-dark-50 px-4">
      <div className="max-w-md w-full space-y-8">
        {/* Logo */}
        <div className="text-center">
          <h1 className="text-3xl font-bold text-primary-600">PayFlow</h1>
          <p className="mt-2 text-dark-700">Sign in to your merchant dashboard</p>
        </div>

        {/* Form */}
        <form onSubmit={handleSubmit(onSubmit)} className="bg-white p-8 rounded-xl shadow-sm space-y-6">
          {error && (
            <div className="bg-red-50 text-red-600 p-3 rounded-lg text-sm">
              {error}
            </div>
          )}

          <Input
            label="Email"
            type="email"
            placeholder="you@example.com"
            error={errors.email?.message}
            {...register('email')}
          />

          <Input
            label="Password"
            type="password"
            placeholder="••••••••"
            error={errors.password?.message}
            {...register('password')}
          />

          <Button type="submit" className="w-full" loading={isSubmitting}>
            Sign In
          </Button>

          <p className="text-center text-sm text-dark-700">
            Don't have an account?{' '}
            <Link to="/register" className="text-primary-600 hover:text-primary-700 font-medium">
              Create one
            </Link>
          </p>
        </form>
      </div>
    </div>
  );
}
```

## Register Page

```tsx
// src/pages/auth/RegisterPage.tsx
import { useState } from 'react';
import { Link } from 'react-router-dom';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { useAuth } from '@/context/AuthContext';
import { Button } from '@/components/common/Button';
import { Input } from '@/components/common/Input';

const registerSchema = z.object({
  fullName: z.string().min(2, 'Name must be at least 2 characters'),
  email: z.string().email('Invalid email address'),
  password: z.string()
    .min(8, 'Password must be at least 8 characters')
    .regex(/[A-Z]/, 'Must contain uppercase letter')
    .regex(/[0-9]/, 'Must contain a number'),
  confirmPassword: z.string(),
}).refine(data => data.password === data.confirmPassword, {
  message: "Passwords don't match",
  path: ['confirmPassword'],
});

type RegisterFormData = z.infer<typeof registerSchema>;

export function RegisterPage() {
  const { register: registerUser } = useAuth();
  const [error, setError] = useState('');
  const [isSubmitting, setIsSubmitting] = useState(false);

  const { register, handleSubmit, formState: { errors } } = useForm<RegisterFormData>({
    resolver: zodResolver(registerSchema),
  });

  const onSubmit = async (data: RegisterFormData) => {
    setError('');
    setIsSubmitting(true);
    try {
      await registerUser({
        fullName: data.fullName,
        email: data.email,
        password: data.password,
      });
    } catch (err: any) {
      setError(err.response?.data?.message || 'Registration failed');
    } finally {
      setIsSubmitting(false);
    }
  };

  return (
    <div className="min-h-screen flex items-center justify-center bg-dark-50 px-4">
      <div className="max-w-md w-full space-y-8">
        <div className="text-center">
          <h1 className="text-3xl font-bold text-primary-600">PayFlow</h1>
          <p className="mt-2 text-dark-700">Create your merchant account</p>
        </div>

        <form onSubmit={handleSubmit(onSubmit)} className="bg-white p-8 rounded-xl shadow-sm space-y-6">
          {error && (
            <div className="bg-red-50 text-red-600 p-3 rounded-lg text-sm">{error}</div>
          )}

          <Input label="Full Name" error={errors.fullName?.message} {...register('fullName')} />
          <Input label="Email" type="email" error={errors.email?.message} {...register('email')} />
          <Input label="Password" type="password" error={errors.password?.message} {...register('password')} />
          <Input label="Confirm Password" type="password" error={errors.confirmPassword?.message} {...register('confirmPassword')} />

          <Button type="submit" className="w-full" loading={isSubmitting}>
            Create Account
          </Button>

          <p className="text-center text-sm text-dark-700">
            Already have an account?{' '}
            <Link to="/login" className="text-primary-600 hover:text-primary-700 font-medium">
              Sign in
            </Link>
          </p>
        </form>
      </div>
    </div>
  );
}
```

## Protected Route Component

```tsx
// src/components/common/ProtectedRoute.tsx
import { Navigate, Outlet } from 'react-router-dom';
import { useAuth } from '@/context/AuthContext';
import { LoadingSpinner } from './LoadingSpinner';

export function ProtectedRoute() {
  const { isAuthenticated, isLoading } = useAuth();

  if (isLoading) {
    return (
      <div className="min-h-screen flex items-center justify-center">
        <LoadingSpinner size="lg" />
      </div>
    );
  }

  if (!isAuthenticated) {
    return <Navigate to="/login" replace />;
  }

  return <Outlet />;
}
```

## JWT Interceptor (Token Refresh Flow)

```
┌──────────────────────────────────────────────────────────────┐
│                  TOKEN REFRESH FLOW                            │
└──────────────────────────────────────────────────────────────┘

Request with expired token
        │
        ▼
┌───────────────┐     ┌───────────────┐
│  API Request  │────▶│   API Server  │
│  (Bearer JWT) │     │               │
└───────────────┘     └───────┬───────┘
                              │
                     401 Unauthorized
                              │
                              ▼
                    ┌──────────────────┐
                    │ Interceptor sees │
                    │ 401 response     │
                    └────────┬─────────┘
                             │
                    ┌────────▼─────────┐
                    │ POST /auth/refresh│
                    │ (refresh token)   │
                    └────────┬─────────┘
                             │
                    ┌────────▼─────────┐
                    │ New access token  │
                    │ + refresh token   │
                    └────────┬─────────┘
                             │
                    ┌────────▼─────────┐
                    │ Retry original    │
                    │ request with new  │
                    │ access token      │
                    └──────────────────┘
```

## Auth API Layer

```ts
// src/api/auth.api.ts
import { apiClient } from './axios';
import type { LoginRequest, RegisterRequest, AuthResponse } from '@/types/auth.types';

export const authApi = {
  login: (data: LoginRequest) =>
    apiClient.post<{ data: AuthResponse }>('/auth/login', data),

  register: (data: RegisterRequest) =>
    apiClient.post<{ data: AuthResponse }>('/auth/register', data),

  refresh: (refreshToken: string) =>
    apiClient.post<{ data: AuthResponse }>('/auth/refresh', { refreshToken }),

  logout: () =>
    apiClient.post('/auth/logout'),

  getMe: () =>
    apiClient.get<{ data: { id: string; email: string; fullName: string; role: string } }>('/auth/me'),
};
```

## Auth Types

```ts
// src/types/auth.types.ts
export interface User {
  id: string;
  email: string;
  fullName: string;
  role: 'MERCHANT' | 'ADMIN';
  status: string;
  createdAt: string;
}

export interface LoginRequest {
  email: string;
  password: string;
}

export interface RegisterRequest {
  fullName: string;
  email: string;
  password: string;
}

export interface AuthResponse {
  accessToken: string;
  refreshToken: string;
  expiresIn: number;
  user: User;
}
```

## Security Considerations

| Concern | Implementation |
|---------|---------------|
| Token Storage | localStorage (acceptable for demo; HttpOnly cookies preferred in production) |
| Token Expiry | 15 minutes (access), 7 days (refresh) |
| Auto-refresh | Interceptor handles 401 → refresh → retry |
| Logout | Clear local storage + revoke server-side |
| Protected Routes | Redirect to /login if no valid session |
| Form Validation | Client-side (Zod) + Server-side validation |
| Password Rules | Min 8 chars, uppercase, number required |
