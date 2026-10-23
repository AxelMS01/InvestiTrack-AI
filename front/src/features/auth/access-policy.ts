import type { Project, User } from '../../types';

export function canReadProject(user: User, project: Project): boolean {
  if (user.role === 'admin') return true;
  if (user.role === 'reviewer') return user.assignedProjectIds?.includes(project.id) ?? false;
  return Boolean(
    user.departmentId && project.departmentId && user.departmentId === project.departmentId,
  );
}
export function subjectKey(user: User): string {
  return JSON.stringify([
    user.id,
    user.role,
    user.departmentId,
    [...(user.assignedProjectIds ?? [])].sort(),
  ]);
}
