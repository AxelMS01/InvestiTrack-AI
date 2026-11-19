import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useEffect, useState, type ReactNode } from 'react';
import { config } from '../../lib/config';
import { demoStorageKey } from '../../demo/database';
import { invalidateWorkflowQueries } from '../../api/demo.queries';
import { authApi } from '../../api/auth.api';
import { sessionKey, useSession } from '../../api/auth.queries';
import { AuthContext } from './auth-context';
import { canCoordinateUnit } from './access-policy';
import { userRoles, type Role } from '../../types/user';

export function AuthProvider({ children }: { children: ReactNode }) {
  const client = useQueryClient();
  const session = useSession();
  useEffect(() => {
    if (!config.demo) return;
    function onStorage(event: StorageEvent) {
      if (event.key !== demoStorageKey && event.key !== null) return;
      void client
        .invalidateQueries({ queryKey: sessionKey })
        .then(() => invalidateWorkflowQueries(client));
    }
    window.addEventListener('storage', onStorage);
    return () => window.removeEventListener('storage', onStorage);
  }, [client]);
  const [selection, setSelection] = useState<{ id: string; role: Role }>();
  const [unitSelection, setUnitSelection] = useState<{ userId: string; id: string }>();
  const user = session.data ?? null;
  const unitId =
    user && unitSelection?.userId === user.id && canCoordinateUnit(user, unitSelection.id)
      ? unitSelection.id
      : undefined;
  const active =
    user && selection?.id === user.id && userRoles(user).includes(selection.role)
      ? selection.role
      : user?.role;
  const switcher = useMutation({
    mutationFn: async (profile: string) => {
      if (!config.demo) throw new Error('El simulador solo está disponible en demostración.');
      await client.cancelQueries();
      const user = await authApi.demoLogin(profile);
      client.removeQueries({ predicate: (query) => query.queryKey[0] !== 'session' });
      client.getMutationCache().clear();
      setSelection(undefined);
      setUnitSelection(undefined);
      client.setQueryData(sessionKey, user);
      return user;
    },
  });
  return (
    <AuthContext.Provider
      value={{
        user,
        workspace: active,
        unitId,
        switchUnit: (id) => {
          if (user && id && canCoordinateUnit(user, id)) setUnitSelection({ userId: user.id, id });
          else if (!id) setUnitSelection(undefined);
        },
        switchWorkspace: (role) => {
          if (user && userRoles(user).includes(role)) setSelection({ id: user.id, role });
        },
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
