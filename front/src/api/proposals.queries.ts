import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { proposalsApi } from './proposals.api';
import { useAuth } from '../features/auth/auth-context';
import { subjectKey } from '../features/auth/access-policy';

function useScope() {
  const { user } = useAuth();
  return user ? subjectKey(user) : 'anonymous';
}
export function useProposals() {
  const scope = useScope();
  return useQuery({ queryKey: ['proposals', scope], queryFn: proposalsApi.list });
}
export function useProposal(id: string) {
  const scope = useScope();
  return useQuery({ queryKey: ['proposals', scope, id], queryFn: () => proposalsApi.detail(id) });
}
export function useCreateProposal() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: proposalsApi.create,
    onSuccess: async () => {
      await client.invalidateQueries({ queryKey: ['proposals'] });
    },
  });
}
