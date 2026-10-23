import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { authApi } from './auth.api';

export const sessionKey = ['session'] as const;
export function useSession() {
  return useQuery({ queryKey: sessionKey, queryFn: authApi.me, retry: false, staleTime: 30_000 });
}
export function useLogout() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: authApi.logout,
    onSuccess: async () => {
      await client.cancelQueries();
      client.removeQueries({ predicate: (query) => query.queryKey[0] !== 'session' });
      client.setQueryData(sessionKey, null);
    },
  });
}
