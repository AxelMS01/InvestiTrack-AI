import { useQuery } from '@tanstack/react-query';
import { evaluationsApi, activitiesApi, documentsApi } from './resources.api';

export const resourceKeys = {
  evaluations: ['evaluations'] as const,
  activities: ['activities'] as const,
  documents: ['documents'] as const,
};
// Disabled by default: these contracts are scaffolding, not implemented backend modules.
export function useEvaluations(enabled = false) {
  return useQuery({
    queryKey: resourceKeys.evaluations,
    queryFn: ({ signal }) => evaluationsApi.list(signal),
    enabled,
  });
}
export function useActivities(enabled = false) {
  return useQuery({
    queryKey: resourceKeys.activities,
    queryFn: ({ signal }) => activitiesApi.list(signal),
    enabled,
  });
}
export function useDocuments(enabled = false) {
  return useQuery({
    queryKey: resourceKeys.documents,
    queryFn: ({ signal }) => documentsApi.list(signal),
    enabled,
  });
}
