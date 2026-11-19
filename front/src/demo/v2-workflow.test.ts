import { beforeEach, describe, expect, it, vi } from 'vitest';
vi.mock('../lib/config', () => ({ config: { demo: true, apiUrl: '/api' } }));
import { authApi } from '../api/auth.api';
import { assistantApi } from '../api/assistant.api';
import { projectsApi } from '../api/projects.api';
import { approveProposal, pdf } from '../test/workflow-fixtures';
import { demoActivities } from './activities';
import { demoCoordination } from './coordination';
import { demoDocuments } from './documents';
import { demoEvidences } from './evidences';
import { demoProjects } from './projects';
import { addDays } from '../lib/format';
import type { GradeInput } from '../types/evidence';

describe('V2 · unidades, incorporación y evidencias', () => {
  beforeEach(async () => {
    localStorage.clear();
    await authApi.logout();
  });

  it('espera la decisión de la coordinación autorizada y evita incorporaciones duplicadas', async () => {
    const approved = await approveProposal();
    const project = await projectsApi.create(approved.id);
    await authApi.demoLogin('researcher');
    expect(() =>
      demoProjects.member({ id: project.id, userId: 'u7', responsibility: 'student' }),
    ).toThrow('autorización');
    const data = {
      projectId: project.id,
      studentUserId: 'u7',
      rationale: 'Participará en observaciones de campo.',
    };
    const pending = demoCoordination.propose(data);
    expect(pending.status).toBe('PENDING');
    expect(() => demoCoordination.propose(data)).toThrow('pendiente');
    await authApi.demoLogin('u7');
    await expect(projectsApi.detail(project.id)).rejects.toMatchObject({ status: 404 });
    await authApi.demoLogin('u6');
    expect(demoCoordination.units().map((item) => item.id)).toEqual(['engineering', 'health']);
    expect(() => demoCoordination.directory('environment')).toThrow('no está disponible');
    expect(() => demoCoordination.request(pending.id)).toThrow('no está disponible');
    const decision = {
      id: pending.id,
      version: pending.version,
      decision: 'APPROVED' as const,
      reason: 'Participación autorizada.',
    };
    expect(() => demoCoordination.decide(decision)).toThrow('no está disponible');
    await authApi.demoLogin('coordinator');
    expect(demoCoordination.decide(decision)).toMatchObject({
      status: 'APPROVED',
      decidedBy: 'u2',
    });
    expect(() => demoCoordination.decide(decision)).toThrow('datos cambiaron');
    expect(
      (await projectsApi.detail(project.id)).members?.filter((item) => item.userId === 'u7'),
    ).toHaveLength(1);
    await authApi.demoLogin('u7');
    expect((await projectsApi.detail(project.id)).members).toEqual([
      { userId: 'u7', name: 'Mateo Cruz', responsibility: 'student' },
    ]);
    expect(demoEvidences.options()).toEqual([]);
  });

  it('aplica unidad y conflicto de interés al asignar evaluadores y al cambiar la investigación principal', async () => {
    await authApi.demoLogin('u8');
    await expect(projectsApi.detail('p1')).rejects.toMatchObject({ status: 404 });
    await authApi.demoLogin('u6');
    expect(() =>
      demoCoordination.assignEvaluator({ projectId: 'p1', evaluatorUserId: 'u8', reason: '' }),
    ).toThrow('no está disponible');
    await authApi.demoLogin('coordinator');
    const candidates = demoCoordination.assignmentContext('p1').candidates.map((item) => item.id);
    expect(candidates).toContain('u8');
    expect(candidates).not.toContain('u3');
    expect(() =>
      demoCoordination.assignEvaluator({ projectId: 'p1', evaluatorUserId: 'u3', reason: '' }),
    ).toThrow('conflicto');
    expect(
      demoCoordination.assignEvaluator({
        projectId: 'p1',
        evaluatorUserId: 'u8',
        reason: 'Revisión externa.',
      }).status,
    ).toBe('ACTIVE');
    expect(() =>
      demoCoordination.assignEvaluator({ projectId: 'p1', evaluatorUserId: 'u8', reason: '' }),
    ).toThrow('vigente');
    await authApi.demoLogin('u8');
    expect((await projectsApi.detail('p1')).id).toBe('p1');
    expect(demoActivities.detail('a1').activity.id).toBe('a1');
    await expect(demoDocuments.download('demo-field-file-3')).resolves.toMatchObject({
      name: 'demo-field-file-3.pdf',
    });
    expect(
      (
        await assistantApi.ask({ prompt: 'Consulta los archivos del proyecto', projectId: 'p1' })
      ).sources.map((item) => item.documentId),
    ).toContain('demo-field-file-3');
    await expect(projectsApi.detail('p2')).rejects.toMatchObject({ status: 404 });
    await authApi.demoLogin('coordinator');
    expect(
      demoCoordination.changeLead({ projectId: 'p1', previousLeadId: 'u3', userId: 'u9' }).leadId,
    ).toBe('u9');
    expect(() =>
      demoCoordination.changeLead({ projectId: 'p1', previousLeadId: 'u3', userId: 'u9' }),
    ).toThrow('cambió');
    await authApi.demoLogin('researcher');
    expect(() =>
      demoCoordination.propose({ projectId: 'p1', studentUserId: 'u7', rationale: 'Apoyo.' }),
    ).toThrow('No puedes');
  });

  it('guarda entregas nombradas, conserva archivos anteriores y protege notas ante correcciones concurrentes', async () => {
    await authApi.demoLogin('student');
    await expect(
      demoEvidences.create({
        activityId: 'ajena',
        title: 'Entrega ajena',
        description: '',
        files: [],
      }),
    ).rejects.toMatchObject({ status: 403 });
    const draft = await demoEvidences.create({
      activityId: 'a1',
      title: 'Resultados del experimento',
      description: 'Controles y mediciones.',
      files: [],
    });
    expect(draft).toMatchObject({
      status: 'DRAFT',
      currentVersionNumber: 0,
      evidenceNumber: expect.stringMatching(/^EVD-/),
    });
    await authApi.demoLogin('reviewer');
    expect(() => demoEvidences.detail(draft.id)).toThrow('no está disponible');
    await authApi.demoLogin('student');
    const ready = await demoEvidences.addVersion({
      id: draft.id,
      version: draft.version,
      files: [pdf('resultados_v2_final.pdf'), pdf('anexo.pdf')],
      notes: 'Entrega inicial',
    });
    const sent = demoEvidences.submit(ready);
    const v1 = demoEvidences.detail(sent.id).versions[0]!;
    expect(v1.documents.map((item) => item.name)).toEqual(['resultados_v2_final.pdf', 'anexo.pdf']);
    await authApi.demoLogin('researcher');
    const correction = {
      id: sent.id,
      version: sent.version,
      evidenceVersionId: v1.id,
      outcome: 'CHANGES_REQUESTED',
      feedback: 'Agrega las unidades de medida.',
    };
    expect(demoEvidences.review(correction).reviewKind).toBe('FORMATIVE');
    await authApi.demoLogin('student');
    const needsChanges = demoEvidences.detail(sent.id).evidence;
    const pending = await Promise.allSettled([
      demoEvidences.addVersion({
        ...needsChanges,
        files: [pdf('mediciones.pdf')],
        notes: 'Unidades incluidas.',
      }),
      demoEvidences.addVersion({
        ...needsChanges,
        files: [pdf('duplicado.pdf')],
        notes: 'Conflicto de concurrencia.',
      }),
    ]);
    expect(pending.filter((item) => item.status === 'fulfilled')).toHaveLength(1);
    expect(pending.find((item) => item.status === 'rejected')).toMatchObject({
      reason: { status: 412 },
    });
    const resubmitted = demoEvidences.submit(demoEvidences.detail(sent.id).evidence);
    expect(resubmitted.status).toBe('RESUBMITTED');
    await authApi.demoLogin('reviewer');
    const detail = demoEvidences.detail(sent.id);
    const context = detail.gradeContext!;
    const grade: GradeInput = {
      evaluatorAssignmentId: context.assignment.id,
      rubricId: context.rubric.id,
      rubricScores: context.rubric.criteria.map(({ id }) => ({
        criterionId: id,
        score: 7,
        comment: '',
      })),
      decision: 'ACCEPTED',
      feedback: 'Comentario reservado de la calificación.',
    };
    const claimed = demoEvidences.beginReview(resubmitted);
    await authApi.demoLogin('researcher');
    expect(() =>
      demoEvidences.review({
        ...correction,
        version: claimed.version,
        evidenceVersionId: context.versionId,
      }),
    ).toThrow('en curso');
    const submission = {
      id: sent.id,
      version: claimed.version,
      evidenceVersionId: context.versionId,
      grade,
    };
    expect(() => demoEvidences.grade(submission)).toThrow('sin conflicto');
    await authApi.demoLogin('u8');
    expect(() => demoEvidences.grade(submission)).toThrow('no está disponible');
    await authApi.demoLogin('reviewer');
    expect(demoEvidences.grade(submission)).toMatchObject({
      totalScore: 7,
      publishedAt: null,
      status: 'SUBMITTED',
    });
    expect(() => demoEvidences.grade(submission)).toThrow('cambió');
    expect(() =>
      demoEvidences.grade({
        ...submission,
        version: demoEvidences.detail(sent.id).evidence.version,
      }),
    ).toThrow('resuelta');
    expect(demoEvidences.detail(sent.id).grades).toHaveLength(1);
    await authApi.demoLogin('student');
    const receipt = demoEvidences.detail(sent.id);
    expect(receipt.versions.map((item) => item.versionNumber)).toEqual([2, 1]);
    expect(receipt.reviews).toHaveLength(1);
    expect(receipt.grades).toEqual([]);
    expect(JSON.stringify(receipt)).not.toContain('Comentario reservado');
    await expect(demoDocuments.download(v1.documentIds[0]!)).resolves.toMatchObject({
      name: 'resultados_v2_final.pdf',
    });
    await expect(demoDocuments.remove(v1.documentIds[0]!)).rejects.toMatchObject({ status: 409 });
    await expect(
      demoDocuments.upload(
        { projectId: 'p1', activityId: 'a1', category: 'evidence' },
        pdf('directa.pdf'),
      ),
    ).rejects.toMatchObject({ status: 409 });
    expect(demoEvidences.detail('demo-field-evidence-1').grades).toHaveLength(1);
    expect(demoEvidences.detail('demo-field-evidence-2').grades).toEqual([]);
    await authApi.demoLogin('u7');
    await expect(demoDocuments.download(v1.documentIds[0]!)).rejects.toMatchObject({ status: 404 });
    await authApi.demoLogin('admin');
    await expect(demoDocuments.download(v1.documentIds[0]!)).rejects.toMatchObject({ status: 403 });
  });

  it('mantiene la nota anterior al reentregar tras una decisión oficial de corrección', async () => {
    await authApi.demoLogin('reviewer');
    const seed = demoEvidences.detail('demo-field-evidence-3');
    const context = seed.gradeContext!;
    demoEvidences.grade({
      id: seed.evidence.id,
      version: seed.evidence.version,
      evidenceVersionId: context.versionId,
      grade: {
        evaluatorAssignmentId: context.assignment.id,
        rubricId: context.rubric.id,
        decision: 'REDO_REQUESTED',
        feedback: 'Privado',
        rubricScores: context.rubric.criteria.map((item) => ({
          criterionId: item.id,
          score: 2,
          comment: '',
        })),
      },
    });
    await authApi.demoLogin('student');
    const evidence = demoEvidences.detail(seed.evidence.id);
    expect(evidence.evidence.status).toBe('REDO_REQUESTED');
    expect(JSON.stringify(evidence)).not.toContain('Privado');
    const staged = await demoEvidences.addVersion({
      ...evidence.evidence,
      files: [pdf('segunda.pdf')],
      notes: '',
    });
    demoEvidences.submit(staged);
    await authApi.demoLogin('reviewer');
    const again = demoEvidences.detail(seed.evidence.id);
    expect(again.grades).toHaveLength(1);
    expect(again.grades[0]?.evidenceVersionId).toBe(context.versionId);
    expect(again.gradeContext?.versionId).not.toBe(context.versionId);
    expect(again.reviews.map((item) => item.reviewKind)).toEqual(['OFFICIAL']);
  });

  it('limita el alta de evidencias a tareas asignadas aunque el usuario ya sea integrante', async () => {
    await authApi.demoLogin('researcher');
    const task = demoActivities.save({
      values: {
        projectId: 'p1',
        title: 'Catálogo preliminar',
        description: 'Preparar el inventario de campo.',
        dueDate: addDays(1),
        assigneeId: 'u3',
        status: 'pending',
        isMilestone: false,
      },
    });
    await authApi.demoLogin('student');
    await expect(
      demoEvidences.create({
        activityId: task.id,
        title: 'Entrega indebida',
        description: '',
        files: [],
      }),
    ).rejects.toMatchObject({ status: 403 });
  });
});
