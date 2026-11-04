import { z } from 'zod';
import { entityIdSchema } from './shared';

export const proposalSchema = z.object({
  id: entityIdSchema,
  title: z.string().min(1),
  abstract: z.string(),
  ownerId: entityIdSchema,
  status: z.enum(['draft', 'submitted', 'evaluating', 'approved', 'rejected']),
  version: z.number().int().positive(),
  createdAt: z.iso.datetime(),
  updatedAt: z.iso.datetime(),
});
export type Proposal = z.infer<typeof proposalSchema>;
