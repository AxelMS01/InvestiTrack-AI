import { beforeEach, describe, expect, it, vi } from 'vitest';
vi.mock('../lib/config', () => ({ config: { demo: true, apiUrl: '/api' } }));
import { projectsApi } from './projects.api';
import { authApi } from './auth.api';
import { proposalsApi } from './proposals.api';
import { approveProposal, validProposal } from '../test/workflow-fixtures';

describe('Flujo de proyectos en demostración', () => {
  beforeEach(async () => {
    localStorage.clear();
    await authApi.logout();
  });
  it('requiere una sesión para listar proyectos', async () => {
    await expect(projectsApi.list()).rejects.toMatchObject({ status: 401 });
  });
  it('crea un proyecto único desde la propuesta aprobada y conserva su responsable', async () => {
    const proposal = await approveProposal();
    const project = await projectsApi.create(proposal.id);
    expect(project).toMatchObject({
      title: proposal.title,
      proposalId: proposal.id,
      leadId: 'u3',
      progress: 0,
      status: 'active',
    });
    await expect(projectsApi.create(proposal.id)).rejects.toMatchObject({ status: 409 });
    await authApi.demoLogin('researcher');
    expect(await projectsApi.detail(project.id)).toEqual(project);
    await authApi.demoLogin('admin');
    await expect(projectsApi.detail(project.id)).rejects.toMatchObject({ status: 403 });
  });
  it('impide convertir propuestas sin aprobación o desde una cuenta evaluadora', async () => {
    await authApi.demoLogin('researcher');
    const draft = await proposalsApi.save({ details: validProposal });
    await expect(projectsApi.create(draft.id)).rejects.toMatchObject({ status: 409 });
    await authApi.demoLogin('reviewer');
    await expect(projectsApi.create(draft.id)).rejects.toMatchObject({ status: 403 });
  });
  it('distingue un proyecto inexistente u oculto a una cuenta académica', async () => {
    await authApi.demoLogin('researcher');
    await expect(projectsApi.detail('inexistente')).rejects.toMatchObject({ status: 404 });
    await expect(projectsApi.detail('p5')).rejects.toMatchObject({ status: 404 });
  });
  it('informa si los datos persistidos están corruptos', async () => {
    await authApi.demoLogin('admin');
    localStorage.setItem('invesctictrack.demo.projects.v1', '{invalid');
    await expect(projectsApi.list()).rejects.toThrow('datos de demostración están dañados');
  });
});
