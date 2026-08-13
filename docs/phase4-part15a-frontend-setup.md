# Phase 4 · Part 15A — Merchant Portal Setup

| Field | Value |
|-------|-------|
| **Project** | PayFlow Payment Gateway |
| **Phase** | 4 — Frontend |
| **Part** | 15A — Merchant Portal Setup (React + TypeScript) |
| **Previous** | [Part 14 — Docker & Containerization](./phase4-part14-docker.md) |
| **Next** | [Part 15B — Frontend Features](./phase4-part15b-frontend-features.md) |
| **Time** | ~2.5 hours |
| **Difficulty** | ★★★☆☆ (Intermediate) |
| **Prerequisites** | React basics, TypeScript, Node.js |
| **What You'll Build** | React project foundation with auth, routing, API client, and query setup |
| **Git Commit** | `feat(frontend): scaffold merchant portal with Vite, React, TanStack Query` |

---

## Table of Contents

1. [Project Setup — Vite + React 18 + TypeScript](#1-project-setup)
2. [Tailwind CSS Configuration](#2-tailwind-css)
3. [React Router v6 Setup](#3-react-router)
4. [TanStack Query Setup](#4-tanstack-query)
5. [Axios API Client with JWT Interceptor](#5-axios-api-client)
6. [AuthContext — Login, Logout, Token Refresh](#6-authcontext)
7. [Project Structure Overview](#7-project-structure)
8. [What You Learned](#8-what-you-learned)
9. [Common Errors & Fixes](#9-common-errors--fixes)
10. [Git Commit](#10-git-commit)

---

## What You'll Learn

- How to scaffold a production-grade React project with Vite (blazing-fast builds)
- How to configure Tailwind CSS for utility-first styling
- How React Router v6 handles nested routes with layouts
- How TanStack Query (React Query) manages server state vs local state
- How Axios interceptors automatically attach JWT and refresh expired tokens
- How to build an AuthContext that persists login across page reloads

---

## 1. Project Setup

```bash
# Create project with Vite (fastest React build tool)
# WHY Vite: 10-100x faster than Create React App (uses esbuild + native ES modules)
npm create vite@latest merchant-portal -- --template react-ts

cd merchant-portal

# Install core dependencies
npm install react-router-dom@6          # Routing
npm install @tanstack/react-query@5     # Server state management
npm install axios                        # HTTP client
npm install tailwindcss @tailwindcss/vite # Utility CSS

# Install dev dependencies
npm install -D @types/react @types/react-dom
npm install -D eslint prettier
```

### vite.config.ts

```typescript
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import tailwindcss from '@tailwindcss/vite';

// https://vite.dev/config/
export default defineConfig({
  plugins: [
    react(),
    tailwindcss(), // WHY plugin: Integrates Tailwind directly into Vite's pipeline
  ],
  server: {
    port: 3000,      // WHY 3000: Standard React dev port (matches docker-compose)
    proxy: {
      // WHY proxy: Avoid CORS during development
      // Frontend on :3000, API on :8080 → different origins → CORS error
      // Proxy makes browser think API is on :3000 too
      '/v1': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
  build: {
    outDir: 'dist',
    sourcemap: true,  // WHY: Debug production issues with original TypeScript
  },
});
```

---

## 2. Tailwind CSS

### src/index.css

```css
/* WHY @import: Tailwind v4 uses CSS imports instead of @tailwind directives */
@import "tailwindcss";

/* ═══════════════ CUSTOM THEME EXTENSIONS ═══════════════ */
@theme {
  /* WHY custom colors: PayFlow brand colors consistent across the portal */
  --color-payflow-primary: #4F46E5;
  --color-payflow-secondary: #7C3AED;
  --color-payflow-success: #10B981;
  --color-payflow-warning: #F59E0B;
  --color-payflow-danger: #EF4444;
  --color-payflow-neutral: #6B7280;
}

/* ═══════════════ BASE STYLES ═══════════════ */
@layer base {
  /* WHY: Set sensible defaults for the entire app */
  body {
    @apply bg-gray-50 text-gray-900 antialiased;
    font-family: 'Inter', system-ui, -apple-system, sans-serif;
  }

  /* WHY: Remove default browser focus styles and add custom ones */
  *:focus-visible {
    @apply outline-2 outline-offset-2 outline-payflow-primary;
  }
}

/* ═══════════════ COMPONENT STYLES ═══════════════ */
@layer components {
  /* WHY: Reusable button styles (used across many pages) */
  .btn-primary {
    @apply bg-payflow-primary text-white px-4 py-2 rounded-lg
           font-medium hover:bg-indigo-700 transition-colors
           disabled:opacity-50 disabled:cursor-not-allowed;
  }

  .btn-secondary {
    @apply bg-white text-gray-700 px-4 py-2 rounded-lg
           font-medium border border-gray-300 hover:bg-gray-50
           transition-colors;
  }

  /* WHY: Card component used on Dashboard, Transaction detail, etc. */
  .card {
    @apply bg-white rounded-xl shadow-sm border border-gray-100 p-6;
  }
}
```

---

## 3. React Router v6

### src/App.tsx

```tsx
import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { AuthProvider } from './context/AuthContext';
import { ProtectedRoute } from './components/ProtectedRoute';
import { DashboardLayout } from './layouts/DashboardLayout';

// Pages
import { LoginPage } from './pages/LoginPage';
import { DashboardPage } from './pages/DashboardPage';
import { TransactionsPage } from './pages/TransactionsPage';
import { TransactionDetailPage } from './pages/TransactionDetailPage';
import { AnalyticsPage } from './pages/AnalyticsPage';
import { SettingsPage } from './pages/SettingsPage';

// WHY: Create QueryClient outside component to prevent recreation on re-renders
const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      // WHY 5 minutes: Payment data is relatively stable; don't refetch too often
      staleTime: 5 * 60 * 1000,
      // WHY 3: Retry failed requests 3 times before showing error
      retry: 3,
      // WHY false: Don't refetch when user tabs back (avoids jarring UX)
      refetchOnWindowFocus: false,
    },
  },
});

function App() {
  return (
    // WHY: Provider order matters — Auth wraps everything, QueryClient provides data layer
    <QueryClientProvider client={queryClient}>
      <AuthProvider>
        <BrowserRouter>
          <Routes>
            {/* Public route — no auth required */}
            <Route path="/login" element={<LoginPage />} />

            {/* Protected routes — require JWT */}
            <Route element={<ProtectedRoute />}>
              {/* WHY DashboardLayout wraps all protected pages
                  (sidebar + header always visible) */}
              <Route element={<DashboardLayout />}>
                <Route path="/" element={<Navigate to="/dashboard" replace />} />
                <Route path="/dashboard" element={<DashboardPage />} />
                <Route path="/transactions" element={<TransactionsPage />} />
                <Route path="/transactions/:id" element={<TransactionDetailPage />} />
                <Route path="/analytics" element={<AnalyticsPage />} />
                <Route path="/settings" element={<SettingsPage />} />
              </Route>
            </Route>

            {/* Catch-all — redirect unknown paths to dashboard */}
            <Route path="*" element={<Navigate to="/dashboard" replace />} />
          </Routes>
        </BrowserRouter>
      </AuthProvider>
    </QueryClientProvider>
  );
}

export default App;
```

### ProtectedRoute Component

```tsx
import { Navigate, Outlet } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';

/**
 * WHY ProtectedRoute:
 * Wraps routes that require authentication.
 * If user is not logged in → redirect to /login
 * If logged in → render the child route (Outlet)
 */
export function ProtectedRoute() {
  const { isAuthenticated, isLoading } = useAuth();

  // WHY: Show nothing while checking if token exists/is valid
  // Prevents flash of login page on refresh
  if (isLoading) {
    return (
      <div className="h-screen flex items-center justify-center">
        <div className="animate-spin rounded-full h-8 w-8 border-b-2 border-payflow-primary" />
      </div>
    );
  }

  if (!isAuthenticated) {
    // WHY replace: Don't add login redirect to browser history
    return <Navigate to="/login" replace />;
  }

  // WHY Outlet: Renders the matched child route
  return <Outlet />;
}
```

---

## 4. TanStack Query

### src/hooks/useTransactions.ts

```typescript
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { apiClient } from '../lib/apiClient';
import type { Transaction, TransactionFilters, PaginatedResponse } from '../types';

/**
 * WHY TanStack Query (not useState + useEffect):
 * - Automatic caching (same data isn't fetched twice)
 * - Background refetching (data stays fresh)
 * - Loading/error states handled automatically
 * - Pagination and infinite scroll built-in
 * - Optimistic updates for mutations
 * - Request deduplication (10 components need same data = 1 API call)
 */

// WHY: Query keys as constants — prevents typos and enables cache invalidation
export const transactionKeys = {
  all: ['transactions'] as const,
  lists: () => [...transactionKeys.all, 'list'] as const,
  list: (filters: TransactionFilters) => [...transactionKeys.lists(), filters] as const,
  details: () => [...transactionKeys.all, 'detail'] as const,
  detail: (id: string) => [...transactionKeys.details(), id] as const,
};

export function useTransactions(filters: TransactionFilters) {
  return useQuery({
    // WHY: Query key includes filters — different filters = different cache entries
    queryKey: transactionKeys.list(filters),
    // WHY: queryFn is the actual API call
    // NOTE: Backend wraps all responses in ApiResponse { success, data, timestamp }
    // Extract actual data from response.data.data
    queryFn: async () => {
      const response = await apiClient.get(
        '/v1/payments',
        { params: filters }
      );
      return response.data.data;
    },
    // WHY: Keep previous data visible while fetching new page (no loading flash)
    placeholderData: (previousData) => previousData,
  });
}

export function useTransaction(id: string) {
  return useQuery({
    queryKey: transactionKeys.detail(id),
    queryFn: async () => {
      const response = await apiClient.get<Transaction>(`/v1/payments/${id}`);
      return response.data;
    },
    // WHY: Only fetch if we have an ID (prevents fetch with undefined)
    enabled: !!id,
  });
}

export function useRefundTransaction() {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: async ({ paymentId, amount }: { paymentId: string; amount: number }) => {
      const response = await apiClient.post(`/v1/payments/${paymentId}/refund`, { amount });
      return response.data;
    },
    onSuccess: (_, variables) => {
      // WHY: Invalidate the transaction detail (refetch to show new status)
      queryClient.invalidateQueries({
        queryKey: transactionKeys.detail(variables.paymentId),
      });
      // WHY: Also invalidate the list (transaction status changed)
      queryClient.invalidateQueries({
        queryKey: transactionKeys.lists(),
      });
    },
  });
}
```

---

## 5. Axios API Client

### src/lib/apiClient.ts

```typescript
import axios, { AxiosError, InternalAxiosRequestConfig } from 'axios';

// WHY: Create a configured Axios instance (vs using axios directly)
// - Base URL set once
// - Interceptors applied to all requests
// - Easy to mock in tests
const apiClient = axios.create({
  baseURL: import.meta.env.VITE_API_URL || 'http://localhost:8080',
  timeout: 15000,  // WHY 15s: Allow time for bank communication (can be slow)
  headers: {
    'Content-Type': 'application/json',
  },
});

// ═══════════════ REQUEST INTERCEPTOR ═══════════════
// Runs BEFORE every request — attaches JWT token
apiClient.interceptors.request.use(
  (config: InternalAxiosRequestConfig) => {
    const token = localStorage.getItem('accessToken');
    if (token) {
      // WHY: Attach JWT as Bearer token in Authorization header
      config.headers.Authorization = `Bearer ${token}`;
    }
    return config;
  },
  (error) => Promise.reject(error)
);

// ═══════════════ RESPONSE INTERCEPTOR ═══════════════
// Runs AFTER every response — handles token refresh
let isRefreshing = false;
let failedQueue: Array<{
  resolve: (token: string) => void;
  reject: (error: Error) => void;
}> = [];

const processQueue = (error: Error | null, token: string | null = null) => {
  failedQueue.forEach((prom) => {
    if (error) {
      prom.reject(error);
    } else {
      prom.resolve(token!);
    }
  });
  failedQueue = [];
};

apiClient.interceptors.response.use(
  // WHY: Success responses pass through unchanged
  (response) => response,
  async (error: AxiosError) => {
    const originalRequest = error.config as InternalAxiosRequestConfig & { _retry?: boolean };

    // WHY: Only handle 401 (Unauthorized) — means token expired
    if (error.response?.status === 401 && !originalRequest._retry) {
      // WHY _retry flag: Prevent infinite loop (if refresh also returns 401)
      originalRequest._retry = true;

      if (isRefreshing) {
        // WHY queue: If refresh is already in progress, queue this request
        // When refresh completes, all queued requests will retry with new token
        return new Promise((resolve, reject) => {
          failedQueue.push({
            resolve: (token: string) => {
              originalRequest.headers.Authorization = `Bearer ${token}`;
              resolve(apiClient(originalRequest));
            },
            reject: (err: Error) => reject(err),
          });
        });
      }

      isRefreshing = true;

      try {
        // WHY: Use refresh token to get new access token
        const refreshToken = localStorage.getItem('refreshToken');
        const response = await axios.post(
          `${import.meta.env.VITE_API_URL}/v1/auth/refresh`,
          { refreshToken }
        );

        const { accessToken, refreshToken: newRefreshToken } = response.data.data;

        // WHY: Store new tokens
        localStorage.setItem('accessToken', accessToken);
        localStorage.setItem('refreshToken', newRefreshToken);

        // WHY: Retry original request with new token
        originalRequest.headers.Authorization = `Bearer ${accessToken}`;

        // WHY: Process queued requests with new token
        processQueue(null, accessToken);

        return apiClient(originalRequest);

      } catch (refreshError) {
        // WHY: Refresh failed → token is completely invalid → force logout
        processQueue(refreshError as Error, null);
        localStorage.removeItem('accessToken');
        localStorage.removeItem('refreshToken');
        window.location.href = '/login';
        return Promise.reject(refreshError);

      } finally {
        isRefreshing = false;
      }
    }

    return Promise.reject(error);
  }
);

export { apiClient };
```

---

## 6. AuthContext

### src/context/AuthContext.tsx

```tsx
import { createContext, useContext, useState, useEffect, ReactNode } from 'react';
import { apiClient } from '../lib/apiClient';

interface User {
  id: string;
  email: string;
  fullName: string;
  role: string;
  createdAt: string;
}

interface AuthContextType {
  user: User | null;
  isAuthenticated: boolean;
  isLoading: boolean;
  login: (email: string, password: string) => Promise<void>;
  logout: () => void;
}

const AuthContext = createContext<AuthContextType | undefined>(undefined);

/**
 * WHY AuthProvider:
 * - Centralizes authentication state (single source of truth)
 * - Provides login/logout functions to entire app
 * - Checks for existing token on app load (persist across refreshes)
 * - Any component can access auth state via useAuth() hook
 */
export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<User | null>(null);
  const [isLoading, setIsLoading] = useState(true);

  // WHY useEffect on mount: Check if user was previously logged in
  // (token exists in localStorage from previous session)
  useEffect(() => {
    const initializeAuth = async () => {
      const token = localStorage.getItem('accessToken');
      if (token) {
        try {
          // WHY: Validate token by fetching user profile
          // If token expired, interceptor will try to refresh it
          const response = await apiClient.get('/v1/auth/profile');
          setUser(response.data.data);
        } catch {
          // WHY: Token invalid and refresh failed → clear everything
          localStorage.removeItem('accessToken');
          localStorage.removeItem('refreshToken');
        }
      }
      setIsLoading(false);
    };

    initializeAuth();
  }, []);

  const login = async (email: string, password: string) => {
    // WHY: Call auth endpoint, receive JWT tokens + user info
    // Backend wraps all responses in ApiResponse: { success, data, timestamp }
    const response = await apiClient.post('/v1/auth/login', { email, password });

    // Extract from ApiResponse wrapper — actual auth data is in response.data.data
    const { accessToken, refreshToken, user: userData } = response.data.data;

    // WHY localStorage: Persists across page reloads and browser restarts
    // Trade-off: Vulnerable to XSS (httpOnly cookies are more secure but harder with SPAs)
    localStorage.setItem('accessToken', accessToken);
    localStorage.setItem('refreshToken', refreshToken);
    setUser(userData);
  };

  const logout = () => {
    // WHY: Clean up all auth state
    localStorage.removeItem('accessToken');
    localStorage.removeItem('refreshToken');
    setUser(null);
    // WHY: Redirect to login page
    window.location.href = '/login';
  };

  return (
    <AuthContext.Provider value={{
      user,
      isAuthenticated: !!user,
      isLoading,
      login,
      logout,
    }}>
      {children}
    </AuthContext.Provider>
  );
}

/**
 * WHY custom hook: Type-safe context access with error if used outside Provider
 */
export function useAuth() {
  const context = useContext(AuthContext);
  if (context === undefined) {
    throw new Error('useAuth must be used within an AuthProvider');
  }
  return context;
}
```

---

## 7. Project Structure

```
frontend/merchant-portal/
├── public/
│   └── favicon.svg
├── src/
│   ├── components/          ← Reusable UI components
│   │   ├── DataTable.tsx
│   │   ├── LoadingSpinner.tsx
│   │   ├── Pagination.tsx
│   │   ├── ProtectedRoute.tsx
│   │   └── StatusBadge.tsx
│   ├── context/             ← React Context providers
│   │   └── AuthContext.tsx
│   ├── hooks/               ← Custom hooks (data fetching)
│   │   ├── useDashboard.ts
│   │   ├── useTransactions.ts
│   │   └── useAnalytics.ts
│   ├── layouts/             ← Page layouts (sidebar, header)
│   │   └── DashboardLayout.tsx
│   ├── lib/                 ← Utilities and configurations
│   │   └── apiClient.ts
│   ├── pages/               ← Route-level page components
│   │   ├── LoginPage.tsx
│   │   ├── DashboardPage.tsx
│   │   ├── TransactionsPage.tsx
│   │   ├── TransactionDetailPage.tsx
│   │   ├── AnalyticsPage.tsx
│   │   └── SettingsPage.tsx
│   ├── types/               ← TypeScript type definitions
│   │   └── index.ts
│   ├── App.tsx              ← Root component (routes + providers)
│   ├── index.css            ← Tailwind imports + custom styles
│   └── main.tsx             ← Entry point (renders App)
├── index.html
├── package.json
├── tsconfig.json
├── vite.config.ts
└── tailwind.config.ts
```

### Types Definition

```typescript
// src/types/index.ts

export interface Transaction {
  id: string;
  merchantId: string;
  amount: number;
  currency: string;
  status: TransactionStatus;
  paymentMethod: string;
  cardLast4?: string;
  cardBrand?: string;
  customerEmail: string;
  customerName: string;
  createdAt: string;
  updatedAt: string;
  metadata?: Record<string, string>;
}

export type TransactionStatus =
  | 'INITIATED'
  | 'AUTHORIZED'
  | 'CAPTURED'
  | 'SETTLED'
  | 'FAILED'
  | 'REFUNDED';

export interface TransactionFilters {
  page?: number;
  size?: number;
  status?: TransactionStatus;
  startDate?: string;
  endDate?: string;
  search?: string;
}

export interface PaginatedResponse<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  currentPage: number;
  pageSize: number;
}

export interface DashboardMetrics {
  totalRevenue: number;
  transactionCount: number;
  successRate: number;
  averageTicket: number;
  revenueGrowth: number;      // % change from previous period
  transactionGrowth: number;
}

export interface AnalyticsData {
  revenueByDay: { date: string; amount: number }[];
  volumeByDay: { date: string; count: number }[];
  methodDistribution: { method: string; count: number; percentage: number }[];
}
```

---

## 8. What You Learned

| # | Topic | Key Takeaway |
|---|-------|-------------|
| 1 | Vite | 10-100x faster than CRA (esbuild + native ES modules) |
| 2 | Tailwind CSS v4 | `@import "tailwindcss"` + `@theme` for custom design tokens |
| 3 | React Router v6 | Nested routes with layouts via `<Outlet />` |
| 4 | ProtectedRoute | Checks auth → renders children or redirects to login |
| 5 | TanStack Query | Cache + background refetch + deduplication for server state |
| 6 | Query keys | Array-based keys enable granular cache invalidation |
| 7 | Axios interceptors | Auto-attach JWT (request) + auto-refresh token (response) |
| 8 | Token refresh queue | Multiple 401s → one refresh → all requests retry |
| 9 | AuthContext | Centralized auth state with localStorage persistence |
| 10 | Project structure | pages/ hooks/ components/ lib/ for clear separation |

---

## 9. Common Errors & Fixes

| Error | Cause | Fix |
|-------|-------|-----|
| CORS error in browser | API on different port without proxy | Add proxy in vite.config.ts |
| `useAuth` throws "must be within AuthProvider" | Component outside provider tree | Ensure AuthProvider wraps the entire app |
| Infinite refresh loop | Refresh token endpoint also returns 401 | Add `_retry` flag to prevent retry on refresh call |
| TanStack Query refetches on every render | Query key changes on every render | Memoize filter object or use stable references |
| Tailwind classes not working | CSS not imported | Ensure `@import "tailwindcss"` in index.css |
| TypeScript error "Property does not exist" | API response type not defined | Define interface in types/index.ts |
| `localStorage` not available (SSR) | Server-side rendering | Check `typeof window !== 'undefined'` before access |
| Token lost on page refresh | Not stored in localStorage | Verify localStorage.setItem is called after login |
| Vite HMR not working | File not imported in component tree | Check import chain from main.tsx |
| Build fails with "module not found" | Missing dependency | Run `npm install` or check package.json |

---

## 10. Git Commit

```bash
# Navigate to frontend directory
cd frontend/merchant-portal

# Stage all frontend setup files
git add .

# Commit
git commit -m "feat(frontend): scaffold merchant portal with Vite, React, TanStack Query

- Vite + React 18 + TypeScript project setup
- Tailwind CSS v4 with custom PayFlow theme colors
- React Router v6 with nested routes and DashboardLayout
- TanStack Query v5 with staleTime and retry config
- Axios apiClient with JWT interceptor + auto token refresh
- AuthContext: localStorage persistence, login/logout
- ProtectedRoute: redirect to login if not authenticated
- TypeScript types: Transaction, Filters, PaginatedResponse
- Project structure: pages/ hooks/ components/ lib/ context/"

# Push
git push origin feature/phase4-frontend
```

---

## Document Index

| # | Document | Status |
|---|----------|--------|
| 01 | Project Overview & Architecture | ✅ |
| 02 | Development Environment Setup | ✅ |
| 03 | Merchant Service (CRUD + Auth) | ✅ |
| 04 | Payment Service (Core Processing) | ✅ |
| 05 | API Gateway (Routing + Security) | ✅ |
| 06 | Kafka Event Streaming | ✅ |
| 07 | Redis Caching & Idempotency | ✅ |
| 08 | Ledger Service (Double-Entry) | ✅ |
| 09a | ISO 8583 Message Parsing | ✅ |
| 09b | Netty TCP Client | ✅ |
| 09c | Fraud Detection & Smart Routing | ✅ |
| 10 | Bank Simulator | ✅ |
| 11 | Settlement Service | ✅ |
| 12 | Webhook Service | ✅ |
| 13 | Notification Service | ✅ |
| 14 | Docker & Containerization | ✅ |
| **15a** | **Frontend Setup** | **📍 Current** |
| 15b | Frontend Features | 🔜 Next |

---

## Next Steps

In **Part 15B**, we'll build the actual **merchant portal pages**:
- Dashboard with revenue cards and live metrics
- Transactions table with filters and pagination
- Analytics charts (Recharts: line, bar, pie)
- Transaction detail with refund functionality

---

[← Previous: Part 14 — Docker](./phase4-part14-docker.md) | [Next: Part 15B — Frontend Features →](./phase4-part15b-frontend-features.md)
