import { z } from 'zod';
import { config } from '../lib/config';
import { ApiError, request } from './http-client';
import { projectSchema, type Project } from '../types/project';
import { demoProjects } from '../demo/projects';

export const projectsApi = {
  list: async (signal?: AbortSignal): Promise<Project[]> =>
    config.demo ? demoProjects.list() : request('/projects', z.array(projectSchema), { signal }),
  detail: async (id: string, signal?: AbortSignal): Promise<Project> =>
    config.demo
      ? demoProjects.detail(id)
      : request(`/projects/${encodeURIComponent(id)}`, projectSchema, { signal }),
  create: async (proposalId: string): Promise<Project> => {
    if (!config.demo) throw new ApiError(503, 'El proyecto se genera al aprobar la propuesta.');
    return demoProjects.convert(proposalId);
  },
};
