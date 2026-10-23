import { beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('../lib/config', () => ({ config: { demo: true, apiUrl: '/api' } }));
import { projectsApi } from './projects.api';
import { authApi } from './auth.api';
import type { ProjectInput } from '../types/project';

const input: ProjectInput = {
  title: 'Investigación de prueba',
  description: 'Estudio institucional sobre la conservación de los ecosistemas locales.',
  area: 'Ciencias ambientales',
  budget: 125000,
  startDate: '2026-10-01',
  endDate: '2027-06-01',
};
describe('Flujo de proyectos en demostración', () => {
  beforeEach(async () => {
    localStorage.clear();
    await authApi.logout();
  });
  it('requiere una sesión para listar proyectos', async () => {
    await expect(projectsApi.list()).rejects.toMatchObject({ status: 401 });
  });
  it('registra, persiste y recupera el proyecto con el responsable de la sesión', async () => {
    await authApi.demoLogin('researcher');
    const before = await projectsApi.list();
    const project = await projectsApi.create(input);
    expect(project).toMatchObject({ ...input, leadId: 'u3', progress: 0, status: 'planning' });
    expect(await projectsApi.detail(project.id)).toEqual(project);
    expect(await projectsApi.list()).toHaveLength(before.length + 1);
    await authApi.logout();
    await authApi.demoLogin('admin');
    expect(await projectsApi.detail(project.id)).toEqual(project);
  });
  it('impide registrar proyectos al evaluador', async () => {
    await authApi.demoLogin('reviewer');
    await expect(projectsApi.create(input)).rejects.toMatchObject({ status: 403 });
  });
  it('distingue un proyecto inexistente', async () => {
    await authApi.demoLogin('admin');
    await expect(projectsApi.detail('inexistente')).rejects.toMatchObject({ status: 404 });
  });
  it('informa si los datos persistidos están corruptos', async () => {
    await authApi.demoLogin('admin');
    localStorage.setItem('invesctictrack.demo.projects.v1', '{invalid');
    await expect(projectsApi.list()).rejects.toThrow('datos de demostración están dañados');
  });
});
