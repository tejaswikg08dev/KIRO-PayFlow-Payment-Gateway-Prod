# Phase 4 Part 12a: Dashboard Setup

## Overview

The Merchant Dashboard is built with React 18, TypeScript, Vite, Tailwind CSS, and TanStack Query. It provides merchants with transaction monitoring, analytics, and configuration management.

## Tech Stack

| Tool | Purpose | Why |
|------|---------|-----|
| React 18 | UI Framework | Component-based, ecosystem |
| TypeScript | Type Safety | Catch errors at compile time |
| Vite | Build Tool | Fast HMR, modern bundling |
| Tailwind CSS | Styling | Utility-first, rapid development |
| TanStack Query | Server State | Caching, refetch, pagination |
| React Router 6 | Routing | Client-side navigation |
| Axios | HTTP Client | Interceptors, request/response |
| Recharts | Charts | React-native charting library |
| React Hook Form | Forms | Performant forms, validation |
| Zod | Validation | Schema-based validation |

## Project Setup

```bash
# Create Vite project
npm create vite@latest merchant-dashboard -- --template react-ts
cd merchant-dashboard

# Install core dependencies
npm install react-router-dom @tanstack/react-query axios
npm install react-hook-form @hookform/resolvers zod
npm install recharts date-fns

# Install Tailwind CSS
npm install -D tailwindcss postcss autoprefixer
npx tailwindcss init -p

# Install dev utilities
npm install -D @types/react @types/react-dom
npm install -D eslint prettier eslint-config-prettier
```

## Project Structure

```
merchant-dashboard/
├── public/
│   └── favicon.svg
├── src/
│   ├── api/
│   │   ├── axios.ts              # Axios instance + interceptors
│   │   ├── auth.api.ts           # Auth endpoints
│   │   ├── orders.api.ts         # Orders endpoints
│   │   ├── merchants.api.ts      # Merchant endpoints
│   │   └── analytics.api.ts      # Analytics endpoints
│   ├── components/
│   │   ├── common/
│   │   │   ├── Button.tsx
│   │   │   ├── Input.tsx
│   │   │   ├── Card.tsx
│   │   │   ├── Badge.tsx
│   │   │   ├── Modal.tsx
│   │   │   ├── Table.tsx
│   │   │   ├── Pagination.tsx
│   │   │   └── LoadingSpinner.tsx
│   │   ├── layout/
│   │   │   ├── DashboardLayout.tsx
│   │   │   ├── Sidebar.tsx
│   │   │   ├── Header.tsx
│   │   │   └── MobileNav.tsx
│   │   └── charts/
│   │       ├── RevenueChart.tsx
│   │       ├── TransactionVolume.tsx
│   │       └── PaymentMethodPie.tsx
│   ├── context/
│   │   └── AuthContext.tsx
│   ├── hooks/
│   │   ├── useAuth.ts
│   │   ├── useOrders.ts
│   │   └── useAnalytics.ts
│   ├── pages/
│   │   ├── auth/
│   │   │   ├── LoginPage.tsx
│   │   │   └── RegisterPage.tsx
│   │   ├── dashboard/
│   │   │   └── DashboardPage.tsx
│   │   ├── transactions/
│   │   │   ├── TransactionsPage.tsx
│   │   │   └── TransactionDetail.tsx
│   │   ├── settings/
│   │   │   ├── ApiKeysPage.tsx
│   │   │   ├── WebhooksPage.tsx
│   │   │   └── ProfilePage.tsx
│   │   └── NotFoundPage.tsx
│   ├── types/
│   │   ├── auth.types.ts
│   │   ├── order.types.ts
│   │   └── merchant.types.ts
│   ├── utils/
│   │   ├── formatters.ts
│   │   └── constants.ts
│   ├── App.tsx
│   ├── main.tsx
│   └── index.css
├── tailwind.config.js
├── tsconfig.json
├── vite.config.ts
└── package.json
```

## Tailwind Configuration

```js
// tailwind.config.js
/** @type {import('tailwindcss').Config} */
export default {
  content: ['./index.html', './src/**/*.{js,ts,jsx,tsx}'],
  theme: {
    extend: {
      colors: {
        primary: {
          50: '#f0fdf4',
          100: '#dcfce7',
          200: '#bbf7d0',
          300: '#86efac',
          400: '#4ade80',
          500: '#22c55e',   // PayFlow green
          600: '#16a34a',
          700: '#15803d',
          800: '#166534',
          900: '#14532d',
        },
        dark: {
          50: '#f8fafc',
          100: '#f1f5f9',
          200: '#e2e8f0',
          700: '#334155',
          800: '#1e293b',
          900: '#0f172a',
        }
      },
      fontFamily: {
        sans: ['Inter', 'system-ui', 'sans-serif'],
        mono: ['JetBrains Mono', 'monospace'],
      },
    },
  },
  plugins: [],
}
```

## Vite Configuration

```ts
// vite.config.ts
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import path from 'path';

export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: {
      '@': path.resolve(__dirname, './src'),
      '@components': path.resolve(__dirname, './src/components'),
      '@pages': path.resolve(__dirname, './src/pages'),
      '@api': path.resolve(__dirname, './src/api'),
      '@hooks': path.resolve(__dirname, './src/hooks'),
      '@types': path.resolve(__dirname, './src/types'),
    },
  },
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
});
```

## TanStack Query Setup

```tsx
// src/main.tsx
import React from 'react';
import ReactDOM from 'react-dom/client';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { ReactQueryDevtools } from '@tanstack/react-query-devtools';
import { BrowserRouter } from 'react-router-dom';
import App from './App';
import './index.css';

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      staleTime: 5 * 60 * 1000,       // 5 minutes
      retry: 2,
      refetchOnWindowFocus: false,
    },
  },
});

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <QueryClientProvider client={queryClient}>
      <BrowserRouter>
        <App />
      </BrowserRouter>
      <ReactQueryDevtools initialIsOpen={false} />
    </QueryClientProvider>
  </React.StrictMode>
);
```

## App Router

```tsx
// src/App.tsx
import { Routes, Route, Navigate } from 'react-router-dom';
import { AuthProvider } from './context/AuthContext';
import { ProtectedRoute } from './components/common/ProtectedRoute';
import { DashboardLayout } from './components/layout/DashboardLayout';
import { LoginPage } from './pages/auth/LoginPage';
import { RegisterPage } from './pages/auth/RegisterPage';
import { DashboardPage } from './pages/dashboard/DashboardPage';
import { TransactionsPage } from './pages/transactions/TransactionsPage';
import { ApiKeysPage } from './pages/settings/ApiKeysPage';
import { WebhooksPage } from './pages/settings/WebhooksPage';
import { ProfilePage } from './pages/settings/ProfilePage';

export default function App() {
  return (
    <AuthProvider>
      <Routes>
        {/* Public routes */}
        <Route path="/login" element={<LoginPage />} />
        <Route path="/register" element={<RegisterPage />} />

        {/* Protected routes */}
        <Route element={<ProtectedRoute />}>
          <Route element={<DashboardLayout />}>
            <Route path="/" element={<Navigate to="/dashboard" />} />
            <Route path="/dashboard" element={<DashboardPage />} />
            <Route path="/transactions" element={<TransactionsPage />} />
            <Route path="/settings/api-keys" element={<ApiKeysPage />} />
            <Route path="/settings/webhooks" element={<WebhooksPage />} />
            <Route path="/settings/profile" element={<ProfilePage />} />
          </Route>
        </Route>
      </Routes>
    </AuthProvider>
  );
}
```

## Base Axios Instance

```ts
// src/api/axios.ts
import axios from 'axios';

const API_BASE_URL = import.meta.env.VITE_API_URL || '/api/v1';

export const apiClient = axios.create({
  baseURL: API_BASE_URL,
  headers: {
    'Content-Type': 'application/json',
  },
});

// Request interceptor: attach JWT
apiClient.interceptors.request.use((config) => {
  const token = localStorage.getItem('accessToken');
  if (token) {
    config.headers.Authorization = `Bearer ${token}`;
  }
  return config;
});

// Response interceptor: handle 401 (token refresh)
apiClient.interceptors.response.use(
  (response) => response,
  async (error) => {
    const originalRequest = error.config;

    if (error.response?.status === 401 && !originalRequest._retry) {
      originalRequest._retry = true;

      try {
        const refreshToken = localStorage.getItem('refreshToken');
        const { data } = await axios.post(`${API_BASE_URL}/auth/refresh`, {
          refreshToken,
        });

        localStorage.setItem('accessToken', data.data.accessToken);
        localStorage.setItem('refreshToken', data.data.refreshToken);

        originalRequest.headers.Authorization =
          `Bearer ${data.data.accessToken}`;
        return apiClient(originalRequest);
      } catch {
        localStorage.clear();
        window.location.href = '/login';
      }
    }

    return Promise.reject(error);
  }
);
```

## Common Components

```tsx
// src/components/common/Button.tsx
import { ButtonHTMLAttributes, forwardRef } from 'react';
import { clsx } from 'clsx';

interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: 'primary' | 'secondary' | 'danger' | 'ghost';
  size?: 'sm' | 'md' | 'lg';
  loading?: boolean;
}

export const Button = forwardRef<HTMLButtonElement, ButtonProps>(
  ({ variant = 'primary', size = 'md', loading, children, className, ...props }, ref) => {
    const baseClasses = 'inline-flex items-center justify-center font-medium rounded-lg transition-colors focus:outline-none focus:ring-2 focus:ring-offset-2 disabled:opacity-50';
    
    const variants = {
      primary: 'bg-primary-600 text-white hover:bg-primary-700 focus:ring-primary-500',
      secondary: 'bg-dark-100 text-dark-700 hover:bg-dark-200 focus:ring-dark-500',
      danger: 'bg-red-600 text-white hover:bg-red-700 focus:ring-red-500',
      ghost: 'text-dark-700 hover:bg-dark-100 focus:ring-dark-500',
    };

    const sizes = {
      sm: 'px-3 py-1.5 text-sm',
      md: 'px-4 py-2 text-sm',
      lg: 'px-6 py-3 text-base',
    };

    return (
      <button
        ref={ref}
        className={clsx(baseClasses, variants[variant], sizes[size], className)}
        disabled={loading || props.disabled}
        {...props}
      >
        {loading && <LoadingSpinner className="mr-2 h-4 w-4" />}
        {children}
      </button>
    );
  }
);
```

## Type Definitions

```ts
// src/types/order.types.ts
export interface Order {
  id: string;
  merchantId: string;
  amount: number;
  currency: string;
  status: OrderStatus;
  description?: string;
  customerEmail?: string;
  paymentMethod?: string;
  createdAt: string;
}

export type OrderStatus =
  | 'CREATED'
  | 'PROCESSING'
  | 'AUTHORIZED'
  | 'CAPTURED'
  | 'FAILED'
  | 'REFUNDED';

export interface PageResponse<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  page: number;
  size: number;
}
```
