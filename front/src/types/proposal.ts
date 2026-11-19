import { z } from 'zod';
import { entityIdSchema } from './shared';
import { departmentSchema } from './department';
import { areas } from './project';

export const proposalStatusSchema = z.enum([
  'draft',
  'submitted',
  'under_review',
  'changes_requested',
  'approved',
  'rejected',
  'cancelled',
]);
export type ProposalStatus = z.infer<typeof proposalStatusSchema>;
export const proposalStatusLabels: Record<ProposalStatus, string> = {
  draft: 'Borrador',
  submitted: 'Enviada',
  under_review: 'En evaluación',
  changes_requested: 'Cambios solicitados',
  approved: 'Aprobada',
  rejected: 'Rechazada',
  cancelled: 'Cancelada',
};
const proposalStatus = z
  .union([proposalStatusSchema, z.literal('evaluating')])
  .transform((status): ProposalStatus => (status === 'evaluating' ? 'under_review' : status));
export const proposalSchema = z.object({
  id: entityIdSchema,
  title: z.string(),
  abstract: z.string(),
  ownerId: entityIdSchema,
  status: proposalStatus,
  version: z.number().int().positive(),
  createdAt: z.iso.datetime(),
  updatedAt: z.iso.datetime(),
});
export type Proposal = z.infer<typeof proposalSchema>;

export const proposalDraftSchema = z.object({
  title: z.string().max(180),
  abstract: z.string().max(3000),
  area: z.enum(areas),
  objective: z.string().max(1500),
  methodology: z.string().max(4000),
  startDate: z.union([z.iso.date(), z.literal('')]),
  endDate: z.union([z.iso.date(), z.literal('')]),
  team: z
    .array(
      z.object({
        name: z.string().max(180),
        email: z.string().max(254),
        contribution: z.string().max(1500),
      }),
    )
    .max(10),
  budgetItems: z
    .array(
      z.object({
        concept: z.string().max(180),
        category: z.enum(['personnel', 'equipment', 'services', 'materials']),
        amount: z.number(),
      }),
    )
    .max(20),
  requestedFunding: z.number(),
  involvesParticipants: z.boolean(),
  ethicsApproval: z.boolean(),
  declaration: z.boolean(),
});
export type ProposalDraft = z.infer<typeof proposalDraftSchema>;
export const proposalFieldSchema = proposalDraftSchema.keyof();
export const registeredProposalSchema = proposalSchema.extend({
  details: proposalDraftSchema,
  departmentId: departmentSchema.optional(),
  academicUnitId: z.string().optional(),
  submissionVersion: z.number().int().positive().optional(),
  reviewerIds: z.array(entityIdSchema).default([]),
  allowedFields: z.array(proposalFieldSchema).default([]),
  history: z
    .array(
      z.object({
        status: proposalStatusSchema,
        at: z.iso.datetime(),
        actorId: entityIdSchema,
        note: z.string(),
      }),
    )
    .default([]),
});
export type RegisteredProposal = z.infer<typeof registeredProposalSchema>;
