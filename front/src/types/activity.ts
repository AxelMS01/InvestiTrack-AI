import { z } from 'zod';
import { dateSchema, entityIdSchema } from './shared';

export const activitySchema = z.object({
  id: entityIdSchema,
  projectId: entityIdSchema,
  title: z.string().min(1),
  description: z.string(),
  assigneeId: entityIdSchema,
  dueDate: dateSchema,
  status: z.enum(['pending', 'in_progress', 'completed', 'blocked']),
  isMilestone: z.boolean(),
});
export type Activity = z.infer<typeof activitySchema>;
