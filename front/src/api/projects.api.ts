import { z } from 'zod';
import { config } from '../lib/config';
import { ApiError, request } from './http-client';
import { authApi } from './auth.api';
import { can } from '../features/auth/model';
import { canReadProject } from '../features/auth/access-policy';
import { readDemoProjects, writeDemoProjects } from '../features/projects/demo';
import {
  projectInputSchema,
  projectSchema,
  type ProjectInput,
  type Project,
} from '../types/project';

export const projectsApi = {
  list: async (signal?: AbortSignal): Promise<Project[]> => {
    if (!config.demo) return request('/projects', z.array(projectSchema), { signal });
    const user = await authApi.me();
    if (!user) throw new ApiError(401, 'Inicia sesión para consultar proyectos.');
    return readDemoProjects().filter((project) => canReadProject(user, project));
  },
  detail: async (id: string, signal?: AbortSignal): Promise<Project> => {
    if (!config.demo)
      return request(`/projects/${encodeURIComponent(id)}`, projectSchema, { signal });
    const project = (await projectsApi.list(signal)).find((item) => item.id === id);
    if (!project) throw new ApiError(404, 'No se encontró el proyecto.');
    return project;
  },
  create: async (input: ProjectInput): Promise<Project> => {
    const valid = projectInputSchema.parse(input);
    if (!config.demo)
      return request('/projects', projectSchema, { method: 'POST', body: JSON.stringify(valid) });
    const user = await authApi.me();
    if (!user || !can(user.role, 'createProject'))
      throw new ApiError(403, 'Tu perfil no puede registrar proyectos.');
    const projects = readDemoProjects();
    const id = crypto.randomUUID();
    const project: Project = {
      ...valid,
      id,
      code: `INV-${new Date().getFullYear()}-${id.slice(0, 8).toUpperCase()}`,
      status: 'planning',
      progress: 0,
      lead: user.name,
      leadId: user.id,
      departmentId: user.departmentId,
    };
    writeDemoProjects([project, ...projects]);
    return project;
  },
};
