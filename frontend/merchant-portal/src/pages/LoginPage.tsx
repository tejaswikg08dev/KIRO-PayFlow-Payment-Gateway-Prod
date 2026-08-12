import { Link, useNavigate } from 'react-router-dom';
import { LoginForm } from '@/components/auth/LoginForm';

export default function LoginPage() {
  const navigate = useNavigate();

  return (
    <div className="min-h-screen flex items-center justify-center bg-gray-50 px-4">
      <div className="max-w-md w-full">
        <div className="text-center mb-8">
          <h1 className="text-3xl font-bold text-primary-600">PayFlow</h1>
          <p className="text-gray-600 mt-2">Sign in to your merchant account</p>
        </div>
        <div className="card">
          <LoginForm onSuccess={() => navigate('/dashboard')} />
          <div className="mt-4 text-center text-sm text-gray-600">
            Don&apos;t have an account?{' '}
            <Link to="/register" className="text-primary-600 hover:text-primary-700 font-medium">
              Register
            </Link>
          </div>
        </div>
      </div>
    </div>
  );
}
