import { z } from 'zod';

export const unitInputSchema = z.object({
  code: z.string().trim().min(2).max(50),
  name: z.string().trim().min(2).max(250),
});
export const academicUnitSchema = unitInputSchema.extend({
  id: z.string(),
  active: z.boolean(),
});
export const evaluatorAssignmentSchema = z.object({
  id: z.string(),
  projectId: z.string(),
  evaluatorUserId: z.string(),
  status: z.enum(['ACTIVE', 'REVOKED', 'COMPLETED']),
  assignedBy: z.string(),
  assignedAt: z.iso.datetime(),
  reason: z.string(),
});
export const requestStatus = z.enum(['PENDING', 'APPROVED', 'REJECTED', 'CANCELLED']);
export const requestStatusLabels: Record<z.infer<typeof requestStatus>, string> = {
  PENDING: 'Pendiente',
  APPROVED: 'Aprobada',
  REJECTED: 'Rechazada',
  CANCELLED: 'Cancelada',
};
export const studentRequestSchema = z.object({
  id: z.string(),
  projectId: z.string(),
  studentUserId: z.string(),
  proposedBy: z.string(),
  proposedAt: z.iso.datetime(),
  rationale: z.string(),
  status: requestStatus,
  version: z.number().int().positive(),
  decidedBy: z.string().optional(),
  decidedAt: z.iso.datetime().optional(),
  decisionReason: z.string().optional(),
});
