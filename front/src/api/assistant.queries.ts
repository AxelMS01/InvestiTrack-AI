import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useAuth } from '../features/auth/auth-context';
import { subjectKey } from '../features/auth/access-policy';
import { assistantApi } from './assistant.api';

function useScope() {
  const { user } = useAuth();
  return user ? subjectKey(user) : 'anonymous';
}
export function useAssistantHistory() {
  const scope = useScope();
  return useQuery({
    queryKey: ['assistant', scope, 'history'],
    queryFn: assistantApi.history,
    staleTime: 0,
  });
}
export function useAssistantAnswer(id: string) {
  const scope = useScope();
  return useQuery({
    queryKey: ['assistant', scope, 'answer', id],
    queryFn: () => assistantApi.answer(id),
    staleTime: 0,
  });
}
export function useAskAssistant() {
  const scope = useScope();
  const client = useQueryClient();
  return useMutation({
    mutationFn: assistantApi.ask,
    onSuccess: async () => {
      await client.invalidateQueries({ queryKey: ['assistant', scope] });
    },
  });
}
