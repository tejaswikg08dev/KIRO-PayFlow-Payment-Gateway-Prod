import { createContext, useState, useEffect, ReactNode } from 'react';
import { User, LoginRequest, RegisterRequest, AuthResponse } from '@/types/auth.types';
import { authService } from '@/services/authService';
import { TOKEN_KEY } from '@/utils/constants';

interface AuthContextType {
  user: User | null;
  isAuthenticated: boolean;
  isLoading: boolean;
  login: (data: LoginRequest) => Promise<AuthResponse>;
  register: (data: RegisterRequest) => Promise<AuthResponse>;
  logout: () => void;
}

export const AuthContext = createContext<AuthContextType | undefined>(undefined);

interface AuthProviderProps {
  children: ReactNode;
}

export function AuthProvider({ children }: AuthProviderProps) {
  const [user, setUser] = useState<User | null>(null);
  const [isLoading, setIsLoading] = useState(true);

  useEffect(() => {
    const token = localStorage.getItem(TOKEN_KEY);
    if (token) {
      const storedUser = authService.getStoredUser();
      if (storedUser) {
        setUser(storedUser);
      }
    }
    setIsLoading(false);
  }, []);

  const login = async (data: LoginRequest): Promise<AuthResponse> => {
    const response = await authService.login(data);
    setUser(response.user);
    return response;
  };

  const register = async (data: RegisterRequest): Promise<AuthResponse> => {
    const response = await authService.register(data);
    setUser(response.user);
    return response;
  };

  const logout = () => {
    authService.logout();
    setUser(null);
  };

  return (
    <AuthContext.Provider
      value={{
        user,
        isAuthenticated: !!user,
        isLoading,
        login,
        register,
        logout,
      }}
    >
      {children}
    </AuthContext.Provider>
  );
}
