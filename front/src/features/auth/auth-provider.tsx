import { useMutation, useQueryClient } from '@tanstack/react-query';
import type { ReactNode } from 'react';
import { config } from '../../lib/config';
import { authApi } from '../../api/auth.api';
import { sessionKey, useSession } from '../../api/auth.queries';
import { AuthContext } from './auth-context';
import type { Role } from '../../types';

export function AuthProvider({ children }: { children: ReactNode }) {
  const client = useQueryClient();
  const session = useSession();
  const switcher = useMutation({
    mutationFn: async (role: Role) => {
      if (!config.demo) throw new Error('El simulador solo está disponible en demostración.');
      await client.cancelQueries();
      const user = await authApi.demoLogin(role);
      client.removeQueries({ predicate: (query) => query.queryKey[0] !== 'session' });
      client.setQueryData(sessionKey, user);
      return user;
    },
  });
  return (
    <AuthContext.Provider
      value={{
        user: session.data ?? null,
        loading: session.isPending,
        error: session.error,
        simulatorEnabled: config.demo,
        switching: switcher.isPending,
        switchError: switcher.error,
        switchRole: switcher.mutate,
        retry: () => {
          void session.refetch();
        },
      }}
    >
      {children}
    </AuthContext.Provider>
  );
}
