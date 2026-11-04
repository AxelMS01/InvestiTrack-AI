import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { projectsApi } from './projects.api';

export const projectKeys = {
  all: ['projects'] as const,
  detail: (id: string) => ['projects', id] as const,
};
export function useProjects() {
  return useQuery({ queryKey: projectKeys.all, queryFn: ({ signal }) => projectsApi.list(signal) });
}
export function useProject(id: string) {
  return useQuery({
    queryKey: projectKeys.detail(id),
    queryFn: ({ signal }) => projectsApi.detail(id, signal),
    enabled: Boolean(id),
  });
}
export function useCreateProject() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: projectsApi.create,
    onSuccess: async (project) => {
      client.setQueryData(projectKeys.detail(project.id), project);
      await client.invalidateQueries({ queryKey: projectKeys.all });
    },
  });
}
