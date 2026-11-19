import { z } from 'zod';
import { departmentSchema } from './department';

export const roleSchema = z.enum(['admin', 'coordinator', 'researcher', 'reviewer', 'student']);
export type Role = z.infer<typeof roleSchema>;
export const institutionalRoleSchema = z.enum([
  'ADMIN',
  'COORDINATOR',
  'RESEARCHER',
  'EVALUATOR',
  'STUDENT',
]);
export type InstitutionalRole = z.infer<typeof institutionalRoleSchema>;
export const institutionalRoles = {
  admin: 'ADMIN',
  coordinator: 'COORDINATOR',
  researcher: 'RESEARCHER',
  reviewer: 'EVALUATOR',
  student: 'STUDENT',
} as const satisfies Record<Role, InstitutionalRole>;
export const accountStatusLabels = {
  ACTIVE: 'Activa',
  INACTIVE: 'Desactivada',
  PENDING: 'Pendiente',
};
export const accountStatusSchema = z.enum(['ACTIVE', 'INACTIVE', 'PENDING']);
const userFields = z.object({
  id: z.string(),
  email: z.email(),
  departmentId: departmentSchema.optional(),
  assignedProjectIds: z.array(z.string()).optional(),
  status: accountStatusSchema.optional(),
  capabilities: z.array(z.string()).optional(),
});
const receivedRole = z
  .union([institutionalRoleSchema, roleSchema])
  .transform((role) =>
    roleSchema.safeParse(role).success
      ? institutionalRoles[role as Role]
      : institutionalRoleSchema.parse(role),
  );
export const userSchema = userFields
  .extend({
    name: z.string().optional(),
    displayName: z.string().optional(),
    role: roleSchema.optional(),
    roles: z.array(receivedRole).min(1).optional(),
  })
  .refine((user) => Boolean((user.name || user.displayName) && (user.roles || user.role)), {
    message: 'El perfil requiere nombre y roles.',
  })
  .transform(({ name, displayName, role, roles, ...fields }) => {
    const effective = [...new Set(roles ?? [institutionalRoles[role!]])];
    const primary = roleSchema.options.find((item) => institutionalRoles[item] === effective[0])!;
    return { ...fields, name: (name || displayName)!, role: primary, roles: effective };
  });

export type User = Omit<z.infer<typeof userSchema>, 'roles'> & { roles?: InstitutionalRole[] };
export function userRoles(user: User): Role[] {
  return user.roles
    ? roleSchema.options.filter((role) => user.roles?.includes(institutionalRoles[role]))
    : [user.role];
}
