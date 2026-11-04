import { z } from 'zod';
import { departmentSchema } from './department';

export const roleSchema = z.enum(['admin', 'coordinator', 'researcher', 'reviewer']);
export type Role = z.infer<typeof roleSchema>;
export const userSchema = z.object({
  id: z.string(),
  name: z.string(),
  email: z.email(),
  role: roleSchema,
  departmentId: departmentSchema.optional(),
  assignedProjectIds: z.array(z.string()).optional(),
});
export type User = z.infer<typeof userSchema>;
