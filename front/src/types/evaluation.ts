import { z } from 'zod';
import { entityIdSchema } from './shared';

export const rubricScoreSchema = z.object({
  criterionId: entityIdSchema,
  score: z.number().min(0).max(100),
  comment: z.string(),
});
export const evaluationSchema = z.object({
  id: entityIdSchema,
  proposalId: entityIdSchema,
  reviewerId: entityIdSchema,
  status: z.enum(['assigned', 'in_progress', 'submitted']),
  scores: z.array(rubricScoreSchema),
  recommendation: z.enum(['approve', 'revise', 'reject']).nullable(),
  feedback: z.string(),
  submittedAt: z.iso.datetime().nullable(),
});
export type Evaluation = z.infer<typeof evaluationSchema>;
export type RubricScore = z.infer<typeof rubricScoreSchema>;
