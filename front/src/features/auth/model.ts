import { z } from 'zod';

import type { Role } from '../../types/user';
export { roleSchema, userSchema, type Role, type User } from '../../types/user';
export const credentialsSchema = z.object({
  email: z.email('Ingresa un correo válido.'),
  password: z.string().min(1, 'Ingresa tu contraseña.').max(128),
});
export type Credentials = z.infer<typeof credentialsSchema>;
export const roleLabels: Record<Role, string> = {
  admin: 'Administrador',
  coordinator: 'Coordinador',
  researcher: 'Investigador',
  reviewer: 'Evaluador',
};
export const permissions = {
  createProject: ['admin', 'coordinator', 'researcher'],
  manageUsers: ['admin'],
  createProposal: ['admin', 'coordinator', 'researcher'],
} satisfies Record<string, Role[]>;
export function can(role: Role, permission: keyof typeof permissions) {
  return (permissions[permission] as readonly Role[]).includes(role);
}
