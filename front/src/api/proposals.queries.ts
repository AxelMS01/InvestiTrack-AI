import { useQuery } from '@tanstack/react-query';
import { proposalsApi } from './proposals.api';
import { useAuth } from '../features/auth/auth-context';
import { subjectKey } from '../features/auth/access-policy';
import { useWorkflowMutation } from './demo.queries';

function useScope() {
  const { user } = useAuth();
  return user ? subjectKey(user) : 'anonymous';
}
export function useProposals(enabled = true) {
  const scope = useScope();
  return useQuery({ queryKey: ['proposals', scope], queryFn: proposalsApi.list, enabled });
}
export function useProposal(id: string) {
  const scope = useScope();
  return useQuery({
    queryKey: ['proposals', scope, id],
    queryFn: () => proposalsApi.detail(id),
    enabled: Boolean(id),
  });
}
export function useCreateProposal() {
  return useWorkflowMutation(proposalsApi.save);
}
