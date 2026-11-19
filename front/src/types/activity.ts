import { z } from 'zod';
import { dateSchema, entityIdSchema } from './shared';

export const activitySchema = z.object({
  id: entityIdSchema,
  projectId: entityIdSchema,
  title: z.string().min(1),
  description: z.string(),
  assigneeId: entityIdSchema,
  assignedBy: entityIdSchema.optional(),
  milestoneId: entityIdSchema.optional(),
  dueDate: dateSchema,
  status: z.enum(['pending', 'in_progress', 'completed', 'blocked', 'cancelled']),
  isMilestone: z.boolean(),
});
export type Activity = z.infer<typeof activitySchema>;
