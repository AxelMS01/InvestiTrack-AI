import { z } from 'zod';
import { demoSessionGeneration } from '../api/auth.api';
import { canReadProject } from '../features/auth/access-policy';
import { can } from '../features/auth/model';
import type { Document } from '../types/document';
import type { Project, User } from '../types';
import {
  evidenceInputSchema,
  gradeInputSchema,
  reviewOutcome,
  type Evidence,
  type EvidenceVersion,
  type GradeInput,
} from '../types/evidence';
import { fail, requireDemoUser, visibleProject } from './access';
import { readDatabase, updateDatabase, type DemoDatabase } from './database';
import { fileType, validateContent } from './documents';
import { demoFiles } from './files';
import {
  canReadEvidence,
  canReadEvidenceVersion,
  canReviewFormatively,
  evidenceAssignment,
  ownsEvidence,
} from './evidence-policy';

const reviewable = ['SUBMITTED', 'RESUBMITTED', 'UNDER_REVIEW'];
const changes = ['CHANGES_REQUESTED', 'REDO_REQUESTED'];
const available = (project: Project) => project.status !== 'completed';

function evidenceDetail(data: DemoDatabase, actor: User, id: string) {
  const evidence = data.evidences.find((item) => item.id === id);
  const project = data.projects.find((item) => item.id === evidence?.projectId);
  const activity = data.activities.find((item) => item.id === evidence?.activityId);
  if (!evidence || !project || !activity || !canReadEvidence(data, actor, project, evidence))
    fail(404, 'La evidencia no está disponible para tu cuenta.');
  return { evidence, project, activity };
}
function studentActivity(data: DemoDatabase, user: User, id: string) {
  const activity = data.activities.find(
    (item) =>
      item.id === id &&
      item.assigneeId === user.id &&
      item.status !== 'cancelled' &&
      !item.isMilestone,
  );
  const project = data.projects.find((item) => item.id === activity?.projectId);
  if (
    !activity ||
    !project ||
    !available(project) ||
    !canReadProject(user, project) ||
    !project.members?.some(
      (member) => member.userId === user.id && member.responsibility === 'student',
    )
  )
    fail(403, 'Selecciona una actividad asignada de un proyecto abierto.');
  return { activity, project };
}
function checkRevision(evidence: Evidence, version: number) {
  if (evidence.version !== version)
    fail(412, 'La evidencia cambió. Actualiza la pantalla y revisa la versión vigente.');
}
function latest(data: DemoDatabase, evidence: Evidence) {
  return data.evidenceVersions.find(
    (item) =>
      item.evidenceId === evidence.id && item.versionNumber === evidence.currentVersionNumber,
  );
}
function bump(evidence: Evidence, actor: User, status = evidence.status) {
  if (evidence.status !== status)
    evidence.history.push({
      actorId: actor.id,
      at: new Date().toISOString(),
      status,
      versionNumber: evidence.currentVersionNumber,
    });
  evidence.status = status;
  evidence.version++;
  evidence.updatedAt = new Date().toISOString();
}
function activeReviewOwner(data: DemoDatabase, project: Project, evidence: Evidence) {
  const owner = data.users.find((item) => item.id === evidence.reviewerInProgressId);
  return owner && evidenceAssignment(data, owner, project, evidence) ? owner.id : undefined;
}
function enterReview(evidence: Evidence, actor: User) {
  if (evidence.status !== 'UNDER_REVIEW') bump(evidence, actor, 'UNDER_REVIEW');
}
function stagedAllowed(data: DemoDatabase, user: User, evidence: Evidence) {
  return (
    ownsEvidence(user, evidence) &&
    (evidence.status === 'DRAFT' || changes.includes(evidence.status)) &&
    (!latest(data, evidence) || Boolean(latest(data, evidence)?.submittedAt))
  );
}
function appendVersion(
  data: DemoDatabase,
  evidence: Evidence,
  actor: User,
  files: Document[],
  notes: string,
) {
  if (!files.length) return;
  const version: EvidenceVersion = {
    id: crypto.randomUUID(),
    evidenceId: evidence.id,
    versionNumber: evidence.currentVersionNumber + 1,
    documentIds: files.map((file) => file.id),
    submittedBy: actor.id,
    createdAt: new Date().toISOString(),
    submissionNotes: notes,
  };
  data.evidenceVersions.push(version);
  data.documents.push(
    ...files.map((file) => ({
      ...file,
      activityId: evidence.activityId,
      projectId: evidence.projectId,
      evidenceVersionId: version.id,
    })),
  );
  evidence.currentVersionNumber = version.versionNumber;
  bump(evidence, actor);
}
async function stageFiles<T>(actor: User, files: File[], commit: (files: Document[]) => T) {
  const generation = demoSessionGeneration();
  if (files.length > 5)
    fail(422, 'Puedes adjuntar hasta cinco archivos por versión en la demostración.');
  const documents: Document[] = [];
  try {
    for (const file of files) {
      const id = crypto.randomUUID();
      const mimeType = fileType(file, 'evidence');
      await validateContent(file, mimeType);
      documents.push({
        id,
        name: file.name,
        mimeType,
        sizeBytes: file.size,
        version: 1,
        uploadedBy: actor.id,
        uploadedAt: new Date().toISOString(),
        category: 'evidence',
      });
      await demoFiles
        .put(id, file)
        .catch(() => fail(507, 'No pudimos guardar los archivos en este navegador.'));
    }
    const current = requireDemoUser('project:evidence-submit');
    if (current.id !== actor.id || generation !== demoSessionGeneration())
      fail(403, 'La sesión cambió durante la carga.');
    return commit(documents);
  } catch (cause) {
    await Promise.allSettled(documents.map((file) => demoFiles.remove(file.id)));
    throw cause;
  }
}
function audit(data: DemoDatabase, user: User, evidence: Evidence, action: string) {
  data.audit.unshift({
    id: crypto.randomUUID(),
    actorId: user.id,
    targetId: evidence.id,
    action,
    at: new Date().toISOString(),
  });
}

export const demoEvidences = {
  options() {
    const actor = requireDemoUser('project:evidence-submit');
    const data = readDatabase();
    return data.activities.flatMap((activity) => {
      const project = data.projects.find((item) => item.id === activity.projectId);
      return project &&
        available(project) &&
        canReadProject(actor, project) &&
        project.members?.some(
          (item) => item.userId === actor.id && item.responsibility === 'student',
        ) &&
        activity.assigneeId === actor.id &&
        !activity.isMilestone &&
        activity.status !== 'cancelled'
        ? [
            {
              id: activity.id,
              title: activity.title,
              projectId: project.id,
              projectTitle: project.title,
            },
          ]
        : [];
    });
  },
  list() {
    const actor = requireDemoUser('project:evidence-read');
    const data = readDatabase();
    return data.evidences
      .flatMap((evidence) => {
        const project = data.projects.find((item) => item.id === evidence.projectId);
        const activity = data.activities.find((item) => item.id === evidence.activityId);
        if (!project || !activity || !canReadEvidence(data, actor, project, evidence)) return [];
        const version = latest(data, evidence);
        const grades = can(actor, 'project:evidence-grade-read')
          ? data.evidenceGrades.filter(
              (item) =>
                item.evidenceId === evidence.id &&
                (!ownsEvidence(actor, evidence) || item.publishedAt),
            )
          : [];
        return [
          {
            ...evidence,
            history: [],
            currentVersionNumber:
              version && !version.submittedAt && !ownsEvidence(actor, evidence)
                ? version.versionNumber - 1
                : evidence.currentVersionNumber,
            projectTitle: project.title,
            activityTitle: activity.title,
            ownerName: data.users.find((item) => item.id === evidence.ownerUserId)?.name,
            assigned: Boolean(evidenceAssignment(data, actor, project, evidence)),
            pendingGrade: Boolean(
              evidenceAssignment(data, actor, project, evidence) &&
              available(project) &&
              reviewable.includes(evidence.status) &&
              !data.evidenceGrades.some((item) => item.evidenceVersionId === version?.id),
            ),
            gradeCount: grades.length,
          },
        ];
      })
      .sort((a, b) => b.updatedAt.localeCompare(a.updatedAt));
  },
  detail(id: string) {
    const actor = requireDemoUser('project:evidence-read');
    const data = readDatabase();
    const { evidence, project, activity } = evidenceDetail(data, actor, id);
    const owner = ownsEvidence(actor, evidence);
    const versions = data.evidenceVersions
      .filter((item) => item.evidenceId === id && canReadEvidenceVersion(data, actor, item))
      .map((item) => ({
        ...item,
        documents: data.documents.filter((entry) => item.documentIds.includes(entry.id)),
      }))
      .reverse();
    const reviews = data.evidenceReviews.filter((item) => item.evidenceId === id);
    const grades = can(actor, 'project:evidence-grade-read')
      ? data.evidenceGrades.filter(
          (item) => item.evidenceId === evidence.id && (!owner || item.publishedAt),
        )
      : [];
    const current = latest(data, evidence);
    const assignment = evidenceAssignment(data, actor, project, evidence);
    const formal = Boolean(assignment && can(actor, 'project:evidence-revise'));
    const formative = canReviewFormatively(actor, project, evidence);
    const rubric = data.rubrics[0];
    const open = available(project);
    const reviewOwner = activeReviewOwner(data, project, evidence);
    const canGrade = Boolean(
      open &&
      assignment &&
      rubric &&
      current?.submittedAt &&
      reviewable.includes(evidence.status) &&
      (!reviewOwner || reviewOwner === actor.id) &&
      !grades.some((item) => item.evidenceVersionId === current.id),
    );
    const people = new Set([
      evidence.ownerUserId,
      ...reviews.map((item) => item.reviewerUserId),
      ...grades.map((item) => item.evaluatorUserId),
      ...evidence.history.map((item) => item.actorId),
    ]);
    return {
      evidence: {
        ...evidence,
        reviewerInProgressId: reviewOwner,
        currentVersionNumber: versions[0]?.versionNumber ?? 0,
      },
      projectTitle: project.title,
      activityTitle: activity.title,
      versions,
      reviews,
      grades,
      people: Object.fromEntries(
        data.users.filter((item) => people.has(item.id)).map((user) => [user.id, user.name]),
      ),
      canAttach:
        open &&
        owner &&
        activity.assigneeId === actor.id &&
        activity.status !== 'cancelled' &&
        stagedAllowed(data, actor, evidence),
      canSubmit:
        open &&
        owner &&
        activity.assigneeId === actor.id &&
        activity.status !== 'cancelled' &&
        Boolean(current && !current.submittedAt),
      canReview:
        open &&
        Boolean(current?.submittedAt) &&
        (formal || formative) &&
        evidence.status !== 'DRAFT',
      canRequest:
        open &&
        Boolean(current?.submittedAt) &&
        reviewable.includes(evidence.status) &&
        (!reviewOwner || reviewOwner === actor.id) &&
        (formal || formative),
      gradeContext:
        canGrade && assignment && rubric && current
          ? { assignment, rubric, versionId: current.id }
          : undefined,
    };
  },
  async create(input: { activityId: string; title: string; description: string; files: File[] }) {
    const actor = requireDemoUser('project:evidence-submit');
    const parsed = evidenceInputSchema.safeParse(input);
    if (!parsed.success) fail(422, 'Escribe un nombre y revisa la longitud de la descripción.');
    studentActivity(readDatabase(), actor, input.activityId);
    return stageFiles(actor, input.files, (files) =>
      updateDatabase((data) => {
        const current = requireDemoUser('project:evidence-submit');
        const { activity, project } = studentActivity(data, current, input.activityId);
        const id = crypto.randomUUID();
        const evidenceNumber = 'EVD-' + id.slice(0, 13).toUpperCase();
        if (data.evidences.some((item) => item.evidenceNumber === evidenceNumber))
          fail(409, 'El código generado ya existe. Vuelve a guardar el borrador.');
        const at = new Date().toISOString();
        const evidence: Evidence = {
          ...parsed.data,
          id,
          evidenceNumber,
          ownerUserId: current.id,
          projectId: project.id,
          activityId: activity.id,
          status: 'DRAFT',
          currentVersionNumber: 0,
          version: 1,
          createdAt: at,
          updatedAt: at,
          history: [{ status: 'DRAFT', at, actorId: current.id, versionNumber: 0 }],
        };
        appendVersion(data, evidence, current, files, '');
        data.evidences.unshift(evidence);
        return evidence;
      }),
    );
  },
  async addVersion(input: { id: string; version: number; files: File[]; notes: string }) {
    const actor = requireDemoUser('project:evidence-submit');
    const notes = z.string().trim().max(2000).safeParse(input.notes);
    if (!notes.success || !input.files.length)
      fail(422, 'Selecciona archivos y revisa las observaciones.');
    const data = readDatabase();
    const { evidence } = evidenceDetail(data, actor, input.id);
    checkRevision(evidence, input.version);
    studentActivity(data, actor, evidence.activityId);
    if (!stagedAllowed(data, actor, evidence))
      fail(409, 'Esta evidencia ya tiene una versión en preparación.');
    return stageFiles(actor, input.files, (files) =>
      updateDatabase((current) => {
        const user = requireDemoUser('project:evidence-submit');
        const { evidence } = evidenceDetail(current, user, input.id);
        checkRevision(evidence, input.version);
        studentActivity(current, user, evidence.activityId);
        if (!stagedAllowed(current, user, evidence))
          fail(409, 'Esta evidencia ya tiene una versión en preparación.');
        appendVersion(current, evidence, user, files, notes.data);
        return evidence;
      }),
    );
  },
  submit(input: { id: string; version: number }) {
    const actor = requireDemoUser('project:evidence-submit');
    return updateDatabase((data) => {
      const { evidence } = evidenceDetail(data, actor, input.id);
      studentActivity(data, actor, evidence.activityId);
      if (!ownsEvidence(actor, evidence)) fail(403, 'Solo puedes entregar tus evidencias.');
      checkRevision(evidence, input.version);
      const version = latest(data, evidence);
      if (
        !version ||
        version.submittedAt ||
        !(evidence.status === 'DRAFT' || changes.includes(evidence.status))
      )
        fail(409, 'La entrega requiere una nueva versión con archivos.');
      version.submittedAt = new Date().toISOString();
      evidence.reviewerInProgressId = undefined;
      bump(evidence, actor, evidence.status === 'DRAFT' ? 'SUBMITTED' : 'RESUBMITTED');
      audit(data, actor, evidence, 'Evidencia entregada');
      return evidence;
    });
  },
  beginReview(input: { id: string; version: number }) {
    const actor = requireDemoUser('project:evidence-grade');
    return updateDatabase((data) => {
      const { evidence, project } = evidenceDetail(data, actor, input.id);
      if (!available(project) || !evidenceAssignment(data, actor, project, evidence))
        fail(403, 'Se requiere una asignación de evaluación vigente y sin conflicto.');
      checkRevision(evidence, input.version);
      if (
        !reviewable.includes(evidence.status) ||
        (activeReviewOwner(data, project, evidence) && evidence.reviewerInProgressId !== actor.id)
      )
        fail(409, 'Esta evidencia ya está siendo revisada o resuelta.');
      if (evidence.reviewerInProgressId === actor.id) return evidence;
      evidence.reviewerInProgressId = actor.id;
      if (evidence.status === 'UNDER_REVIEW') bump(evidence, actor);
      else enterReview(evidence, actor);
      return evidence;
    });
  },
  leaveReview(input: { id: string; version: number }) {
    const actor = requireDemoUser('project:evidence-grade');
    return updateDatabase((data) => {
      const { evidence, project } = evidenceDetail(data, actor, input.id);
      if (activeReviewOwner(data, project, evidence) !== actor.id)
        fail(403, 'Solo el evaluador que inició la revisión puede liberarla.');
      checkRevision(evidence, input.version);
      evidence.reviewerInProgressId = undefined;
      bump(evidence, actor);
      return evidence;
    });
  },
  review(input: {
    id: string;
    version: number;
    evidenceVersionId: string;
    outcome: string;
    feedback: string;
  }) {
    const actor = requireDemoUser('project:evidence-revise');
    const parsed = z
      .object({ outcome: reviewOutcome, feedback: z.string().trim().min(1).max(4000) })
      .safeParse(input);
    if (!parsed.success) fail(422, 'Indica la decisión y escribe retroalimentación.');
    return updateDatabase((data) => {
      const { evidence, project } = evidenceDetail(data, actor, input.id);
      const official = evidenceAssignment(data, actor, project, evidence);
      if (!available(project) || !(official || canReviewFormatively(actor, project, evidence)))
        fail(403, 'Tu cuenta no puede revisar esta evidencia.');
      checkRevision(evidence, input.version);
      const version = latest(data, evidence);
      if (!version?.submittedAt || version.id !== input.evidenceVersionId)
        fail(409, 'La versión revisada ya no es la entrega vigente.');
      if (parsed.data.outcome !== 'COMMENT') {
        if (
          !reviewable.includes(evidence.status) ||
          (activeReviewOwner(data, project, evidence) && evidence.reviewerInProgressId !== actor.id)
        )
          fail(409, 'La revisión oficial está en curso o la entrega ya fue resuelta.');
        enterReview(evidence, actor);
        evidence.reviewerInProgressId = undefined;
        bump(evidence, actor, parsed.data.outcome);
      } else bump(evidence, actor);
      const review = {
        ...parsed.data,
        id: crypto.randomUUID(),
        evidenceId: evidence.id,
        evidenceVersionId: version.id,
        reviewerUserId: actor.id,
        reviewKind: official ? ('OFFICIAL' as const) : ('FORMATIVE' as const),
        createdAt: new Date().toISOString(),
      };
      data.evidenceReviews.push(review);
      audit(data, actor, evidence, 'Revisión · ' + review.outcome);
      return review;
    });
  },
  grade(input: { id: string; version: number; evidenceVersionId: string; grade: GradeInput }) {
    const actor = requireDemoUser('project:evidence-grade');
    const parsed = gradeInputSchema.safeParse(input.grade);
    if (!parsed.success) fail(422, 'Revisa las puntuaciones, la decisión y las observaciones.');
    return updateDatabase((data) => {
      const { evidence, project } = evidenceDetail(data, actor, input.id);
      const assignment = evidenceAssignment(data, actor, project, evidence);
      if (!available(project) || !assignment || assignment.id !== parsed.data.evaluatorAssignmentId)
        fail(403, 'Se requiere una asignación vigente y sin conflicto de interés.');
      checkRevision(evidence, input.version);
      const version = latest(data, evidence);
      if (
        !version?.submittedAt ||
        version.id !== input.evidenceVersionId ||
        !reviewable.includes(evidence.status) ||
        (activeReviewOwner(data, project, evidence) &&
          evidence.reviewerInProgressId !== actor.id) ||
        data.evidenceGrades.some((item) => item.evidenceVersionId === version.id)
      )
        fail(409, 'Esta versión ya fue resuelta o está en revisión por otro evaluador.');
      const rubric = data.rubrics[0];
      const scores = parsed.data.rubricScores;
      if (
        !rubric ||
        rubric.id !== parsed.data.rubricId ||
        scores.length !== rubric.criteria.length ||
        !rubric.criteria.every(
          (criterion) =>
            scores.filter(
              (item) => item.criterionId === criterion.id && item.score <= criterion.maxScore,
            ).length === 1,
        )
      )
        fail(422, 'Completa todos los criterios dentro de los rangos configurados.');
      const total =
        rubric.criteria.reduce(
          (sum, criterion) =>
            sum +
            scores.find((item) => item.criterionId === criterion.id)!.score * criterion.weight,
          0,
        ) / rubric.criteria.reduce((sum, criterion) => sum + criterion.weight, 0);
      const grade = {
        ...parsed.data,
        id: crypto.randomUUID(),
        evidenceId: evidence.id,
        evidenceVersionId: version.id,
        evaluatorUserId: actor.id,
        totalScore: Math.round(total * 100) / 100,
        status: 'SUBMITTED' as const,
        submittedAt: new Date().toISOString(),
        publishedAt: null,
      };
      data.evidenceGrades.push(grade);
      enterReview(evidence, actor);
      evidence.reviewerInProgressId = undefined;
      bump(evidence, actor, grade.decision);
      if (grade.decision === 'CHANGES_REQUESTED' || grade.decision === 'REDO_REQUESTED') {
        data.evidenceReviews.push({
          id: crypto.randomUUID(),
          evidenceId: evidence.id,
          evidenceVersionId: version.id,
          reviewerUserId: actor.id,
          reviewKind: 'OFFICIAL',
          outcome: grade.decision,
          feedback:
            'La revisión oficial solicita otra entrega. Consulta las indicaciones de retroalimentación del equipo.',
          createdAt: grade.submittedAt,
        });
      }
      audit(data, actor, evidence, 'Calificación registrada');
      return grade;
    });
  },
  forProject(id: string) {
    const actor = requireDemoUser('project:evidence-read');
    visibleProject(readDatabase(), actor, id);
    return demoEvidences.list().filter((item) => item.projectId === id);
  },
};
