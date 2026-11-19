import { z } from 'zod';
import { userRoles, type Role, type User } from '../../types/user';
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
  student: 'Estudiante',
};
const academic = ['coordinator', 'researcher'] as const;
const projectReaders = [...academic, 'student'] as const;
export const permissions = {
  'proposal:read': [...academic, 'reviewer'],
  'proposal:create': ['researcher'],
  'proposal:update': ['researcher'],
  'proposal:submit': ['researcher'],
  'proposal:review': ['reviewer'],
  'proposal:decide': ['coordinator'],
  'evaluation:read': ['coordinator', 'reviewer'],
  'evaluation:assign': ['coordinator'],
  'evaluation:create': ['reviewer'],
  'evaluation:submit': ['reviewer'],
  'project:read': [...projectReaders, 'reviewer'],
  'academic-unit:read': ['admin', 'coordinator'],
  'academic-unit:manage': ['admin'],
  'academic-unit:coordinate': ['coordinator'],
  'project:assign-evaluator': ['coordinator'],
  'project:student-propose': ['researcher'],
  'project:student-approve': ['coordinator'],
  'project:student-requests': ['coordinator', 'researcher'],
  'project:evidence-read': [...projectReaders, 'reviewer'],
  'project:evidence-submit': ['student'],
  'project:evidence-revise': ['researcher', 'reviewer'],
  'project:evidence-grade': ['reviewer'],
  'project:evidence-grade-read': [...projectReaders, 'reviewer'],
  'project:convert': academic,
  'project:update': ['researcher'],
  'project:manage-members': ['researcher'],
  'project:manage-activities': ['researcher'],
  'project:close': ['coordinator'],
  'activity:read': [...projectReaders, 'reviewer'],
  'activity:create': ['researcher'],
  'activity:update': ['researcher', 'student'],
  'activity:assign': ['researcher'],
  'document:read': [...projectReaders, 'reviewer'],
  'document:upload': ['researcher', 'student'],
  'document:download': [...projectReaders, 'reviewer'],
  'document:delete': ['researcher', 'student'],
  'report:read': academic,
  'report:export': ['coordinator'],
  'user:read': ['admin'],
  'user:create': ['admin'],
  'user:update': ['admin'],
  'user:deactivate': ['admin'],
  'role:assign': ['admin'],
  'audit:read': ['admin'],
  'ai:ask': [...projectReaders, 'reviewer'],
} as const satisfies Record<string, readonly Role[]>;

export type Capability = keyof typeof permissions;
export function can(user: User | Role, capability: Capability) {
  const allowed: readonly Role[] = permissions[capability];
  if (typeof user === 'string') return allowed.includes(user);
  return (
    (!user.status || user.status === 'ACTIVE') &&
    (!user.capabilities || user.capabilities.includes(capability)) &&
    userRoles(user).some((role) => allowed.includes(role))
  );
}
