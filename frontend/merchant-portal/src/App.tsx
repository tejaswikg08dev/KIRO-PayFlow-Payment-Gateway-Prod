import { Routes, Route, Navigate } from 'react-router-dom';
import { ProtectedRoute } from '@/components/auth/ProtectedRoute';
import { PageLayout } from '@/components/layout/PageLayout';
import LoginPage from '@/pages/LoginPage';
import RegisterPage from '@/pages/RegisterPage';
import DashboardPage from '@/pages/DashboardPage';
import TransactionsPage from '@/pages/TransactionsPage';
import TransactionDetailPage from '@/pages/TransactionDetailPage';
import AnalyticsPage from '@/pages/AnalyticsPage';
import ApiKeysPage from '@/pages/ApiKeysPage';
import WebhooksPage from '@/pages/WebhooksPage';
import SettlementsPage from '@/pages/SettlementsPage';
import SettingsPage from '@/pages/SettingsPage';

function App() {
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route path="/register" element={<RegisterPage />} />
      <Route
        element={
          <ProtectedRoute>
            <PageLayout />
          </ProtectedRoute>
        }
      >
        <Route path="/dashboard" element={<DashboardPage />} />
        <Route path="/transactions" element={<TransactionsPage />} />
        <Route path="/transactions/:id" element={<TransactionDetailPage />} />
        <Route path="/analytics" element={<AnalyticsPage />} />
        <Route path="/api-keys" element={<ApiKeysPage />} />
        <Route path="/webhooks" element={<WebhooksPage />} />
        <Route path="/settlements" element={<SettlementsPage />} />
        <Route path="/settings" element={<SettingsPage />} />
      </Route>
      <Route path="/" element={<Navigate to="/dashboard" replace />} />
      <Route path="*" element={<Navigate to="/dashboard" replace />} />
    </Routes>
  );
}

export default App;
