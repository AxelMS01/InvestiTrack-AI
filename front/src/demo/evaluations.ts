import { z } from 'zod';
import { can } from '../features/auth/model';
import { proposalFieldSchema } from '../types/proposal';
import { rubricScoreSchema, type Evaluation } from '../types/evaluation';
import {
  canCoordinateProposal,
  canReadProposal,
  checkVersion,
  fail,
  requireDemoUser,
  visibleProposal,
} from './access';
import { readDatabase, updateDatabase } from './database';
import { recordProposal } from './proposals';
import { today } from '../lib/format';

export const criteria = [
  { id: 'relevance', label: 'Pertinencia científica' },
  { id: 'methodology', label: 'Rigor metodológico' },
  { id: 'feasibility', label: 'Viabilidad y ética' },
];
const rubricDraftSchema = z.object({
  scores: z
    .array(rubricScoreSchema.extend({ comment: z.string().max(2000) }))
    .max(criteria.length)
    .refine(
      (scores) =>
        new Set(scores.map((item) => item.criterionId)).size === scores.length &&
        scores.every((item) => criteria.some(({ id }) => id === item.criterionId)),
    ),
  recommendation: z.enum(['approve', 'revise', 'reject']).nullable(),
  feedback: z.string().max(4000),
});
export const rubricSchema = rubricDraftSchema.superRefine((value, ctx) => {
  if (value.scores.length !== criteria.length || value.scores.some((item) => item.score === null))
    ctx.addIssue({
      code: 'custom',
      path: ['scores'],
      message: 'Completa la puntuación de cada criterio.',
    });
  if (!value.recommendation)
    ctx.addIssue({ code: 'custom', path: ['recommendation'], message: 'Elige una recomendación.' });
  if (value.feedback.trim().length < 10)
    ctx.addIssue({
      code: 'custom',
      path: ['feedback'],
      message: 'Escribe al menos 10 caracteres de retroalimentación.',
    });
});
export type Rubric = z.infer<typeof rubricSchema>;
const assignmentSchema = z.object({
  proposalId: z.string().min(1),
  reviewerId: z.string().min(1),
  dueDate: z.iso.date(),
});
const decisionSchema = z.object({
  id: z.string().min(1),
  version: z.number().int(),
  status: z.enum(['approved', 'rejected', 'changes_requested']),
  note: z.string().trim().min(10).max(4000),
  allowedFields: z.array(proposalFieldSchema),
});

export const demoEvaluations = {
  list() {
    const user = requireDemoUser('evaluation:read');
    const data = readDatabase();
    return data.evaluations.flatMap((evaluation) => {
      const proposal = data.proposals.find((item) => item.id === evaluation.proposalId);
      if (
        !proposal ||
        (evaluation.reviewerId !== user.id && !canCoordinateProposal(user, proposal))
      )
        return [];
      return [
        {
          ...evaluation,
          proposalTitle:
            evaluation.proposalTitle ??
            (canReadProposal(user, proposal) ? proposal.title : 'Propuesta evaluada'),
          reviewerName:
            data.users.find((item) => item.id === evaluation.reviewerId)?.name ?? 'Evaluador',
        },
      ];
    });
  },
  reviewers() {
    requireDemoUser('evaluation:assign');
    return readDatabase()
      .users.filter((user) => can(user, 'evaluation:create'))
      .map(({ id, name, email }) => ({ id, name, email }));
  },
  assign(input: z.infer<typeof assignmentSchema>) {
    const user = requireDemoUser('evaluation:assign');
    const parsed = assignmentSchema.safeParse(input);
    if (!parsed.success || parsed.data.dueDate < today())
      fail(422, 'Selecciona una propuesta, un evaluador y una fecha vigente.');
    return updateDatabase((data) => {
      const proposal = visibleProposal(data, user, input.proposalId);
      if (!canCoordinateProposal(user, proposal)) fail(403, 'No puedes coordinar esta propuesta.');
      if (!['submitted', 'under_review'].includes(proposal.status))
        fail(409, 'La propuesta debe estar enviada o bajo revisión.');
      const reviewer = data.users.find((item) => item.id === input.reviewerId);
      if (
        !reviewer ||
        !can(reviewer, 'evaluation:create') ||
        reviewer.id === proposal.ownerId ||
        proposal.details.team.some(
          (item) => item.email.toLowerCase() === reviewer.email.toLowerCase(),
        ) ||
        reviewer.id === user.id
      )
        fail(422, 'Selecciona un evaluador activo sin conflicto de interés.');
      if (
        data.evaluations.some(
          (evaluation) =>
            evaluation.proposalId === proposal.id &&
            evaluation.reviewerId === reviewer.id &&
            evaluation.submissionVersion === proposal.submissionVersion,
        )
      )
        fail(409, 'El evaluador ya tiene asignada esta entrega.');
      const evaluation: Evaluation = {
        id: crypto.randomUUID(),
        proposalId: proposal.id,
        proposalTitle: proposal.title,
        reviewerId: reviewer.id,
        dueDate: input.dueDate,
        status: 'assigned',
        scores: [],
        recommendation: null,
        feedback: '',
        submittedAt: null,
        submissionVersion: proposal.submissionVersion,
      };
      data.evaluations.push(evaluation);
      if (!proposal.reviewerIds.includes(reviewer.id)) proposal.reviewerIds.push(reviewer.id);
      recordProposal(proposal, 'under_review', user.id, 'Se asignó una evaluación.');
      return evaluation;
    });
  },
  save(input: { id: string; rubric: Rubric; submit: boolean }) {
    const user = requireDemoUser(input.submit ? 'evaluation:submit' : 'evaluation:create');
    const parsed = (input.submit ? rubricSchema : rubricDraftSchema).safeParse(input.rubric);
    if (!parsed.success)
      fail(
        422,
        'Revisa las puntuaciones y completa la recomendación y las observaciones para enviar.',
      );
    return updateDatabase((data) => {
      const evaluation = data.evaluations.find(
        (item) => item.id === input.id && item.reviewerId === user.id,
      );
      if (!evaluation) fail(404, 'Esta rúbrica no está asignada a tu cuenta.');
      const proposal = visibleProposal(data, user, evaluation.proposalId);
      if (
        proposal.ownerId === user.id ||
        proposal.details.team.some((item) => item.email.toLowerCase() === user.email.toLowerCase())
      )
        fail(403, 'Existe un conflicto de interés con esta propuesta.');
      if (
        evaluation.status === 'submitted' ||
        evaluation.superseded ||
        proposal.status !== 'under_review' ||
        evaluation.submissionVersion !== proposal.submissionVersion
      )
        fail(409, 'Esta evaluación ya no admite cambios.');
      Object.assign(evaluation, parsed.data, {
        status: input.submit ? 'submitted' : 'in_progress',
        submittedAt: input.submit ? new Date().toISOString() : null,
      });
      return evaluation;
    });
  },
  results(id: string) {
    const user = requireDemoUser('evaluation:read');
    const data = readDatabase();
    const assignment = data.evaluations.find((item) => item.id === id);
    const proposal = data.proposals.find((item) => item.id === (assignment?.proposalId ?? id));
    if (!proposal) fail(404, 'Los resultados no están disponibles.');
    const coordinator = canCoordinateProposal(user, proposal) && canReadProposal(user, proposal);
    const ownAssignment = assignment?.reviewerId === user.id;
    if (!coordinator && (assignment ? !ownAssignment : !proposal.reviewerIds.includes(user.id)))
      fail(404, 'Los resultados no están disponibles.');
    const visible = canReadProposal(user, proposal);
    if (!visible && assignment?.status !== 'submitted')
      fail(404, 'Los resultados no están disponibles.');
    return {
      proposalId: proposal.id,
      proposalTitle: visible ? proposal.title : (assignment?.proposalTitle ?? 'Propuesta evaluada'),
      proposalStatus: visible ? proposal.status : undefined,
      coordinatedProposal: coordinator ? proposal : undefined,
      canDecide:
        coordinator &&
        proposal.status === 'under_review' &&
        data.evaluations.some(
          (item) =>
            item.proposalId === proposal.id &&
            item.submissionVersion === proposal.submissionVersion &&
            item.status === 'submitted',
        ) &&
        !data.evaluations.some(
          (item) =>
            item.proposalId === proposal.id &&
            item.submissionVersion === proposal.submissionVersion &&
            !item.superseded &&
            item.status !== 'submitted',
        ),
      evaluations: demoEvaluations
        .list()
        .filter(
          (item) =>
            item.proposalId === proposal.id &&
            item.status === 'submitted' &&
            (coordinator || item.reviewerId === user.id),
        ),
    };
  },
  decide(input: z.infer<typeof decisionSchema>) {
    const user = requireDemoUser('proposal:decide');
    const parsed = decisionSchema.safeParse(input);
    if (!parsed.success || (input.status === 'changes_requested' && !input.allowedFields.length))
      fail(422, 'Indica el motivo y selecciona los campos que deben corregirse.');
    return updateDatabase((data) => {
      const proposal = visibleProposal(data, user, input.id);
      if (!canCoordinateProposal(user, proposal))
        fail(403, 'No puedes decidir sobre esta propuesta.');
      checkVersion(input.version, proposal.version);
      const reviews = data.evaluations.filter(
        (item) =>
          item.proposalId === proposal.id &&
          item.submissionVersion === proposal.submissionVersion &&
          !item.superseded,
      );
      if (
        proposal.status !== 'under_review' ||
        !reviews.length ||
        reviews.some((item) => item.status !== 'submitted')
      )
        fail(
          409,
          'Las evaluaciones de esta entrega deben estar completas para registrar la decisión.',
        );
      proposal.allowedFields =
        input.status === 'changes_requested' ? parsed.data.allowedFields : [];
      recordProposal(proposal, input.status, user.id, parsed.data.note);
      return proposal;
    });
  },
};
