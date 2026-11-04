import { z } from 'zod';
import { request } from './http-client';
import { evaluationSchema, activitySchema, documentSchema } from '../types';

function readResource<T>(endpoint: string, schema: z.ZodType<T>) {
  return {
    list: (signal?: AbortSignal) => request(endpoint, z.array(schema), { signal }),
    detail: (id: string, signal?: AbortSignal) =>
      request(`${endpoint}/${encodeURIComponent(id)}`, schema, { signal }),
  };
}

// Proposed read contracts; future screens opt into these hooks when their backend is ready.
export const evaluationsApi = readResource('/evaluations', evaluationSchema);
export const activitiesApi = readResource('/activities', activitySchema);
export const documentsApi = readResource('/documents', documentSchema);
