import { beforeEach, describe, expect, it, vi } from 'vitest';
vi.mock('../lib/config', () => ({ config: { demo: true, apiUrl: '/api' } }));
import { authApi } from '../api/auth.api';
import { projectsApi } from '../api/projects.api';
import { proposalsApi } from '../api/proposals.api';
import { can } from '../features/auth/model';
import { addDays } from '../lib/format';
import { approveProposal, pdf, validProposal, validRubric } from '../test/workflow-fixtures';
import { userSchema } from '../types/user';
import { demoActivities } from './activities';
import { demoAdministration } from './administration';
import { demoCoordination } from './coordination';
import { demoEvidences } from './evidences';
import { demoDocuments } from './documents';
import { demoEvaluations } from './evaluations';
import { demoProjects } from './projects';
import { demoReports } from './reports';

describe('Recorrido académico y autorización', () => {
  beforeEach(async () => {
    localStorage.clear();
    await authApi.logout();
  });

  it('guarda un borrador incompleto, versiona cambios y exige otra revisión tras correcciones', async () => {
    await authApi.demoLogin('researcher');
    const draft = await proposalsApi.save({
      details: { ...validProposal, title: '', declaration: false },
    });
    expect(draft.status).toBe('draft');
    const attachment = await demoDocuments.upload(
      { proposalId: draft.id, category: 'protocol' },
      pdf('protocolo.pdf'),
    );
    await expect(proposalsApi.submit(draft)).rejects.toMatchObject({ status: 422 });
    const updated = await proposalsApi.save({
      id: draft.id,
      version: draft.version,
      details: validProposal,
    });
    await expect(
      proposalsApi.save({ id: draft.id, version: draft.version, details: validProposal }),
    ).rejects.toMatchObject({ status: 409 });
    const submitted = await proposalsApi.submit(updated);
    await expect(
      proposalsApi.save({ id: draft.id, version: submitted.version, details: validProposal }),
    ).rejects.toMatchObject({ status: 403 });
    await authApi.demoLogin('reviewer');
    await expect(proposalsApi.detail(draft.id)).rejects.toMatchObject({ status: 404 });
    await expect(demoDocuments.download(attachment.id)).rejects.toMatchObject({ status: 404 });
    await authApi.demoLogin('coordinator');
    const assignment = { proposalId: draft.id, reviewerId: 'u4', dueDate: addDays(7) };
    const review = demoEvaluations.assign(assignment);
    expect(() => demoEvaluations.assign(assignment)).toThrow('ya tiene asignada');
    const decision = {
      id: draft.id,
      version: (await proposalsApi.detail(draft.id)).version,
      note: 'Amplía el objetivo del protocolo.',
      status: 'changes_requested' as const,
      allowedFields: ['objective' as const],
    };
    expect(() => demoEvaluations.decide(decision)).toThrow('deben estar completas');
    await authApi.demoLogin('reviewer');
    expect(await demoDocuments.download(attachment.id)).toMatchObject({ name: 'protocolo.pdf' });
    expect(
      demoEvaluations.save({
        id: review.id,
        rubric: { ...validRubric, recommendation: null },
        submit: false,
      }).status,
    ).toBe('in_progress');
    expect(() => demoEvaluations.decide(decision)).toThrow('permiso');
    demoEvaluations.save({ id: review.id, rubric: validRubric, submit: true });
    await authApi.demoLogin('coordinator');
    const returned = demoEvaluations.decide(decision);
    await authApi.demoLogin('researcher');
    await expect(
      proposalsApi.save({
        ...returned,
        details: { ...validProposal, title: 'Modificación no autorizada' },
      }),
    ).rejects.toMatchObject({ status: 403 });
    const corrected = await proposalsApi.save({
      ...returned,
      details: {
        ...validProposal,
        objective: validProposal.objective + ' Incluye mediciones estacionales.',
      },
    });
    await proposalsApi.submit(corrected);
    await authApi.demoLogin('reviewer');
    await expect(proposalsApi.detail(draft.id)).rejects.toMatchObject({ status: 404 });
    expect(demoEvaluations.results(review.id).evaluations.map((item) => item.id)).toEqual([
      review.id,
    ]);
    expect(demoEvaluations.results(review.id).proposalStatus).toBeUndefined();
    await authApi.demoLogin('coordinator');
    expect(demoEvaluations.assign(assignment).id).not.toBe(review.id);
  });

  it('gestiona estudiantes, tareas e hitos, protege versiones y exige presentar el informe antes de cerrar', async () => {
    const approved = await approveProposal();
    const project = await projectsApi.create(approved.id);
    expect(() => demoProjects.close({ id: project.id, confirmed: true })).toThrow(
      'Presenta el informe',
    );
    await authApi.demoLogin('researcher');
    const request = demoCoordination.propose({
      projectId: project.id,
      studentUserId: 'u5',
      rationale: 'Apoyo de campo.',
    });
    await authApi.demoLogin('coordinator');
    demoCoordination.decide({
      id: request.id,
      version: request.version,
      decision: 'APPROVED',
      reason: '',
    });
    await authApi.demoLogin('researcher');
    const fields = {
      projectId: project.id,
      title: 'Resumen de observaciones',
      description: 'Sintetiza las mediciones del periodo.',
      dueDate: addDays(8),
      status: 'pending' as const,
    };
    const milestone = demoActivities.save({
      values: { ...fields, assigneeId: 'u3', isMilestone: true },
    });
    const task = demoActivities.save({
      values: { ...fields, assigneeId: 'u5', milestoneId: milestone.id, isMilestone: false },
    });
    expect(() =>
      demoProjects.member({ id: project.id, userId: 'u5', responsibility: 'remove' }),
    ).toThrow('Reasigna');
    await authApi.demoLogin('student');
    expect(demoActivities.detail(task.id)).toMatchObject({
      assigner: 'Elena Torres',
      canUpdate: true,
    });
    expect(() => demoAdministration.audit()).toThrow('permiso');
    expect(() => demoActivities.save({ values: { ...task } })).toThrow('permiso');
    await expect(
      demoEvidences.create({
        activityId: task.id,
        title: 'Informe de campo',
        description: '',
        files: [new File(['texto'], 'falso.pdf', { type: 'application/pdf' })],
      }),
    ).rejects.toMatchObject({ status: 415 });
    const evidence = await demoEvidences.create({
      activityId: task.id,
      title: 'Informe de campo',
      description: '',
      files: [pdf('entrega.pdf')],
    });
    demoEvidences.submit(evidence);
    const fileId = demoEvidences.detail(evidence.id).versions[0]!.documentIds[0]!;
    expect(await demoDocuments.download(fileId)).toMatchObject({
      name: 'entrega.pdf',
      blob: expect.any(File),
    });
    demoActivities.updateStatus({ id: task.id, status: 'completed' });
    await authApi.demoLogin('admin');
    await expect(demoDocuments.download(fileId)).rejects.toMatchObject({ status: 403 });
    await authApi.demoLogin('researcher');
    demoActivities.updateStatus({ id: milestone.id, status: 'completed' });
    const report = await demoDocuments.upload(
      { projectId: project.id, category: 'report' },
      pdf('informe.pdf'),
    );
    demoReports.savePublication({
      projectId: project.id,
      title: 'Resultados del seguimiento',
      url: 'https://example.com/articulo',
      date: addDays(0),
    });
    expect(demoReports.publications()).toHaveLength(1);
    await authApi.demoLogin('coordinator');
    expect(() => demoProjects.close({ id: project.id, confirmed: true })).toThrow(
      'Presenta el informe',
    );
    await authApi.demoLogin('researcher');
    demoReports.submitFinalReport({ id: project.id, documentId: report.id });
    await expect(demoDocuments.remove(report.id)).rejects.toMatchObject({ status: 409 });
    await authApi.demoLogin('coordinator');
    expect(demoProjects.close({ id: project.id, confirmed: true })).toMatchObject({
      status: 'completed',
      progress: 100,
    });
    await authApi.demoLogin('researcher');
    expect(() =>
      demoProjects.member({ id: project.id, userId: 'u5', responsibility: 'remove' }),
    ).toThrow('abierto');
  });

  it('normaliza roles, aplica capacidades del servidor e impide que el administrador se eleve a sí mismo', async () => {
    const user = userSchema.parse({
      id: 'multi',
      displayName: 'Cuenta multirrol',
      email: 'multi@example.com',
      roles: ['RESEARCHER', 'reviewer'],
      capabilities: ['proposal:create'],
    });
    expect(user.roles).toEqual(['RESEARCHER', 'EVALUATOR']);
    expect(can(user, 'proposal:create')).toBe(true);
    expect(can(user, 'evaluation:create')).toBe(false);
    await authApi.demoLogin('admin');
    const account = demoAdministration.save({
      profile: { name: 'Usuario de prueba', email: 'cuenta@example.com' },
    });
    expect(account.status).toBe('PENDING');
    expect(() =>
      demoAdministration.assignRoles({ id: 'u1', roles: ['ADMIN', 'RESEARCHER'] }),
    ).toThrow('Otra persona');
    const updated = demoAdministration.assignRoles({
      id: account.id,
      roles: ['STUDENT', 'EVALUATOR'],
    });
    expect(updated.roles).toEqual(['STUDENT', 'EVALUATOR']);
    demoAdministration.changeStatus({
      id: account.id,
      status: 'ACTIVE',
      reason: 'Cuenta validada',
    });
    demoAdministration.changeStatus({
      id: account.id,
      status: 'INACTIVE',
      reason: 'Baja de la cuenta',
    });
    expect(demoAdministration.audit()).toHaveLength(4);
  });
});
