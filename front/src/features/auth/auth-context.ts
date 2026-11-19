import { createContext, useContext } from 'react';
import type { Role, User } from '../../types';

export interface AuthContextValue {
  user: User | null;
  loading: boolean;
  error: Error | null;
  simulatorEnabled: boolean;
  switching: boolean;
  switchError: Error | null;
  workspace?: Role;
  switchWorkspace?: (role: Role) => void;
  unitId?: string;
  switchUnit?: (id: string | undefined) => void;
  switchRole: (profile: string) => void;
  retry: () => void;
}
export const AuthContext = createContext<AuthContextValue | null>(null);
export function useAuth() {
  const context = useContext(AuthContext);
  if (!context) throw new Error('useAuth requiere AuthProvider.');
  return context;
}
