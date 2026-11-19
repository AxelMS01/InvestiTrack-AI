import { z } from 'zod';

export const evidenceStatus = z.enum([
  'DRAFT',
  'SUBMITTED',
  'UNDER_REVIEW',
  'CHANGES_REQUESTED',
  'REDO_REQUESTED',
  'RESUBMITTED',
  'ACCEPTED',
  'REJECTED',
]);
export const evidenceStatusLabels: Record<z.infer<typeof evidenceStatus>, string> = {
  DRAFT: 'Borrador',
  SUBMITTED: 'Entregada',
  UNDER_REVIEW: 'En revisión',
  CHANGES_REQUESTED: 'Correcciones solicitadas',
  REDO_REQUESTED: 'Nueva entrega solicitada',
  RESUBMITTED: 'Reentregada',
  ACCEPTED: 'Aceptada',
  REJECTED: 'Rechazada',
};
export const reviewOutcome = z.enum(['COMMENT', 'CHANGES_REQUESTED', 'REDO_REQUESTED']);
export const gradeDecision = z.enum([
  'ACCEPTED',
  'CHANGES_REQUESTED',
  'REDO_REQUESTED',
  'REJECTED',
]);
export const evidenceInputSchema = z.object({
  title: z.string().trim().min(1, 'Escribe un nombre para la evidencia.').max(250),
  description: z.string().trim().max(4000),
});
const evidenceEvent = z.object({
  status: evidenceStatus,
  at: z.iso.datetime(),
  actorId: z.string(),
  versionNumber: z.number().int().nonnegative(),
});
export const evidenceSchema = evidenceInputSchema.extend({
  id: z.string(),
  evidenceNumber: z.string(),
  projectId: z.string(),
  activityId: z.string(),
  ownerUserId: z.string(),
  status: evidenceStatus,
  currentVersionNumber: z.number().int().nonnegative(),
  version: z.number().int().positive(),
  createdAt: z.iso.datetime(),
  updatedAt: z.iso.datetime(),
  history: z.array(evidenceEvent),
  reviewerInProgressId: z.string().optional(),
});
export const evidenceVersionSchema = z.object({
  id: z.string(),
  evidenceId: z.string(),
  versionNumber: z.number().int().positive(),
  documentIds: z.array(z.string()).min(1),
  submittedBy: z.string(),
  createdAt: z.iso.datetime(),
  submittedAt: z.iso.datetime().optional(),
  submissionNotes: z.string(),
});
export const evidenceReviewSchema = z.object({
  id: z.string(),
  evidenceId: z.string(),
  evidenceVersionId: z.string(),
  reviewerUserId: z.string(),
  reviewKind: z.enum(['FORMATIVE', 'OFFICIAL']),
  outcome: reviewOutcome,
  feedback: z.string(),
  createdAt: z.iso.datetime(),
});
export const rubricSchema = z.object({
  id: z.string(),
  title: z.string(),
  version: z.number().int().positive(),
  criteria: z
    .array(
      z.object({
        id: z.string(),
        name: z.string(),
        maxScore: z.number().positive(),
        weight: z.number().positive(),
      }),
    )
    .min(1),
});
export const gradeInputSchema = z.object({
  evaluatorAssignmentId: z.string(),
  rubricId: z.string(),
  rubricScores: z
    .array(
      z.object({
        criterionId: z.string(),
        score: z.number().min(0),
        comment: z.string().max(2000),
      }),
    )
    .min(1),
  decision: gradeDecision,
  feedback: z.string().trim().max(4000),
});
export const evidenceGradeSchema = gradeInputSchema.extend({
  id: z.string(),
  evidenceId: z.string(),
  evidenceVersionId: z.string(),
  evaluatorUserId: z.string(),
  totalScore: z.number(),
  status: z.literal('SUBMITTED'),
  submittedAt: z.iso.datetime(),
  publishedAt: z.iso.datetime().nullable(),
});
export type Evidence = z.infer<typeof evidenceSchema>;
export type EvidenceVersion = z.infer<typeof evidenceVersionSchema>;
export type GradeInput = z.infer<typeof gradeInputSchema>;
