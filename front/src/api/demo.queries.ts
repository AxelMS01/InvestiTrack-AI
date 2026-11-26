import { useMutation, useQuery, useQueryClient, type QueryClient } from '@tanstack/react-query';
import { subjectKey } from '../features/auth/access-policy';
import { useAuth } from '../features/auth/auth-context';
import { config } from '../lib/config';
import { demoSessionGeneration } from './auth.api';
import { ApiError } from './http-client';

export function useDemoQuery<T>(
  key: readonly unknown[],
  read: () => T | Promise<T>,
  enabled = true,
) {
  const { user } = useAuth();
  return useQuery({
    queryKey: ['workspace', user ? subjectKey(user) : 'anonymous', ...key],
    queryFn: read,
    enabled: config.demo && enabled && Boolean(user),
    retry: false,
  });
}

export function invalidateWorkflowQueries(client: QueryClient) {
  return client.invalidateQueries({
    predicate: (query) =>
      ['workspace', 'projects', 'proposals', 'assistant'].includes(String(query.queryKey[0])),
  });
}

export function useWorkflowMutation<T, Result>(write: (values: T) => Result | Promise<Result>) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: async (values: T) => {
      if (!config.demo || !navigator.locks) return write(values);
      const generation = demoSessionGeneration();
      // Serialize demo writes between tabs that share the same browser storage.
      return navigator.locks.request('invesctictrack-demo', () => {
        if (generation !== demoSessionGeneration())
          throw new ApiError(403, 'La sesión cambió antes de guardar.');
        return write(values);
      });
    },
    onSuccess: async () => {
      await invalidateWorkflowQueries(client);
    },
  });
}
