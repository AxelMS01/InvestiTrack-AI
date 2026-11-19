import { useQuery } from '@tanstack/react-query';
import { projectsApi } from './projects.api';
import { useWorkflowMutation } from './demo.queries';
import { useSession } from './auth.queries';
import { subjectKey } from '../features/auth/access-policy';

function useScope() {
  const { data: user } = useSession();
  return user ? subjectKey(user) : 'anonymous';
}
export function useProjects(enabled = true) {
  const scope = useScope();
  return useQuery({
    queryKey: ['projects', scope],
    queryFn: ({ signal }) => projectsApi.list(signal),
    enabled,
  });
}
export function useProject(id: string) {
  const scope = useScope();
  return useQuery({
    queryKey: ['projects', scope, id],
    queryFn: ({ signal }) => projectsApi.detail(id, signal),
    enabled: Boolean(id),
  });
}
export function useCreateProject() {
  return useWorkflowMutation(projectsApi.create);
}
