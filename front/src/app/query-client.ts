import { MutationCache, QueryCache, QueryClient } from '@tanstack/react-query';
import { ApiError } from '../api/http-client';

function refreshExpiredSession(error: unknown) {
  if (
    !(error instanceof ApiError) ||
    error.status !== 401 ||
    !queryClient.getQueryData(['session'])
  )
    return;
  void queryClient.invalidateQueries({ queryKey: ['session'] });
}

export const queryClient = new QueryClient({
  queryCache: new QueryCache({
    onError: (error, query) => {
      if (query.queryKey[0] !== 'session') refreshExpiredSession(error);
    },
  }),
  mutationCache: new MutationCache({ onError: refreshExpiredSession }),
  defaultOptions: {
    queries: {
      staleTime: 60_000,
      retry: (count, error) => !(error instanceof ApiError && error.status < 500) && count < 2,
    },
    mutations: { retry: false },
  },
});
