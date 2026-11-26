import { z } from 'zod';
import { request } from './http-client';

const uuid = z.uuid();
const version = z.number().int().nonnegative();
const label = z.object({ code: z.string(), name: z.string() });
const criterion = label.extend({ minimumScore: z.number(), maximumScore: z.number() });
const objective = z.object({ type: z.enum(['GENERAL', 'SPECIFIC']), description: z.string() });
const proposal = z.object({
  id: uuid,
  title: z.string(),
  status: z.enum([
    'DRAFT',
    'SUBMITTED',
    'UNDER_REVIEW',
    'CHANGES_REQUESTED',
    'APPROVED',
    'REJECTED',
    'CANCELLED',
  ]),
  ownerUserId: uuid,
  researcherIds: z.array(uuid),
  version,
  abstract: z.string().nullish(),
  researchProblem: z.string().nullish(),
  methodology: z.string().nullish(),
  expectedResults: z.string().nullish(),
  researchAreaCode: z.string().nullish(),
  estimatedDurationMonths: z.number().nullish(),
  objectives: z.array(objective),
  projectId: uuid.nullish(),
});
const assignment = z.object({ id: uuid, evaluatorId: uuid, status: z.string(), version });
const decision = z.enum(['APPROVE', 'REQUEST_CHANGES', 'REJECT']);
const evaluation = z.object({
  id: uuid,
  status: z.string(),
  version,
  evaluatorId: uuid,
  decision: decision.nullish(),
  observations: z.string().nullish(),
  recommendations: z.string().nullish(),
  criteria: z.array(z.object({ criterionCode: z.string(), score: z.number() })),
});
const project = z.object({
  id: uuid,
  projectNumber: z.string(),
  sourceProposalId: uuid,
  title: z.string(),
  abstractText: z.string().nullish(),
  researchAreaCode: z.string(),
  status: z.string(),
  progressPercent: z.number(),
  startDate: z.iso.date().nullish(),
  plannedEndDate: z.iso.date(),
});
const user = z.object({ id: uuid, displayName: z.string() });
const history = z.object({ id: uuid, occurredAt: z.string(), eventType: z.string() });

export type InstitutionalProposal = z.infer<typeof proposal>;
export type InstitutionalEvaluation = z.infer<typeof evaluation>;
export type ProposalInput = {
  title: string;
  abstract: string;
  researchProblem: string;
  objectives: z.infer<typeof objective>[];
  methodology: string;
  researchAreaCode: string;
  estimatedDurationMonths: number;
  expectedResults: string;
};
export type EvaluationInput = {
  decision: z.infer<typeof decision>;
  criteria: z.infer<typeof evaluation>['criteria'];
  observations: string;
  recommendations: string;
};

export function collection<T>(schema: z.ZodType<T>) {
  return z.object({
    items: z.array(schema),
    page: z.object({ nextCursor: z.string().nullable(), hasMore: z.boolean() }),
  });
}

export async function readAll<T>(path: string, schema: z.ZodType<T>, signal?: AbortSignal) {
  const items: T[] = [];
  const seen = new Set<string>();
  let cursor: string | null = null;
  do {
    const query: string = cursor
      ? `${path.includes('?') ? '&' : '?'}cursor=${encodeURIComponent(cursor)}`
      : '';
    const result = await request(path + query, collection(schema), { signal });
    items.push(...result.items);
    cursor = result.page.hasMore ? result.page.nextCursor : null;
    if (result.page.hasMore && (!cursor || seen.has(cursor)))
      throw new Error('El servidor devolvió una página inválida.');
    if (cursor) seen.add(cursor);
  } while (cursor);
  return items;
}

const proposalPath = (id: string) => '/proposals/' + encodeURIComponent(id);
const evaluationPath = (id: string, evaluationId?: string) =>
  proposalPath(id) + '/evaluations' + (evaluationId ? '/' + encodeURIComponent(evaluationId) : '');
const etag = (value: number) => ({ 'If-Match': `"${value}"` });
const idempotency = () => ({ 'Idempotency-Key': crypto.randomUUID() });

export const workflowApi = {
  proposals: (signal?: AbortSignal) => readAll('/proposals', proposal, signal),
  proposal: (id: string, signal?: AbortSignal) => request(proposalPath(id), proposal, { signal }),
  history: (id: string, signal?: AbortSignal) =>
    readAll(proposalPath(id) + '/history', history, signal),
  areas: (signal?: AbortSignal) => readAll('/reference/research-areas', label, signal),
  saveProposal: (body: ProposalInput, current?: InstitutionalProposal) =>
    request(current ? proposalPath(current.id) : '/proposals', proposal, {
      method: current ? 'PATCH' : 'POST',
      headers: current ? etag(current.version) : idempotency(),
      body: JSON.stringify(body),
    }),
  transition: (
    current: InstitutionalProposal,
    targetStatus: InstitutionalProposal['status'],
    creationPlan?: { coordinatorId: string; startDate: string | null; plannedEndDate: string },
  ) =>
    request(proposalPath(current.id) + '/transitions', proposal, {
      method: 'POST',
      headers: etag(current.version),
      body: JSON.stringify({ targetStatus, creationPlan }),
    }),
  evaluators: (signal?: AbortSignal) =>
    readAll('/users?role=EVALUATOR&status=ACTIVE', user, signal),
  assignments: (id: string, signal?: AbortSignal) =>
    readAll(proposalPath(id) + '/evaluator-assignments', assignment, signal),
  assign: (current: InstitutionalProposal, evaluatorId: string) =>
    request(proposalPath(current.id) + '/evaluator-assignments', assignment, {
      method: 'POST',
      headers: { ...etag(current.version), ...idempotency() },
      body: JSON.stringify({ evaluatorId }),
    }),
  evaluations: (id: string, signal?: AbortSignal) =>
    readAll(evaluationPath(id), evaluation, signal),
  criteria: (signal?: AbortSignal) => readAll('/evaluation-criteria', criterion, signal),
  saveEvaluation: (id: string, body: EvaluationInput, current?: InstitutionalEvaluation) =>
    request(evaluationPath(id, current?.id), evaluation, {
      method: current ? 'PATCH' : 'POST',
      headers: current ? etag(current.version) : idempotency(),
      body: JSON.stringify(body),
    }),
  submitEvaluation: (id: string, current: InstitutionalEvaluation) =>
    request(evaluationPath(id, current.id) + '/submission', evaluation, {
      method: 'POST',
      headers: etag(current.version),
    }),
  projects: (signal?: AbortSignal) => readAll('/projects', project, signal),
  project: (id: string, signal?: AbortSignal) =>
    request('/projects/' + encodeURIComponent(id), project, { signal }),
};

export const institutionalProposalStatus: Record<InstitutionalProposal['status'], string> = {
  DRAFT: 'Borrador',
  SUBMITTED: 'Enviada',
  UNDER_REVIEW: 'En evaluación',
  CHANGES_REQUESTED: 'Cambios solicitados',
  APPROVED: 'Aprobada',
  REJECTED: 'Rechazada',
  CANCELLED: 'Cancelada',
};
