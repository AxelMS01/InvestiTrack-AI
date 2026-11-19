import { userRoles } from '../../types/user';
import type { Project, User } from '../../types';
import { can } from './model';

export function canReadProject(user: User, project: Project): boolean {
  const roles = userRoles(user);
  return (
    can(user, 'project:read') &&
    ((roles.includes('researcher') && project.leadId === user.id) ||
      (roles.includes('coordinator') && (project.supervisorIds?.includes(user.id) ?? false)) ||
      (project.members?.some(
        (member) =>
          member.userId === user.id &&
          roles.includes(member.responsibility === 'student' ? 'student' : 'researcher'),
      ) ??
        false))
  );
}

export function canManageProject(user: User, project: Project): boolean {
  return (
    project.status !== 'completed' &&
    canReadProject(user, project) &&
    can(user, 'project:update') &&
    project.leadId === user.id
  );
}

export function canCloseProject(user: User, project: Project): boolean {
  return (
    project.status !== 'completed' &&
    can(user, 'project:close') &&
    (project.supervisorIds?.includes(user.id) ?? false)
  );
}

export function subjectKey(user: User): string {
  return JSON.stringify([
    user.id,
    userRoles(user),
    user.status,
    user.departmentId,
    user.capabilities ? [...user.capabilities].sort() : null,
  ]);
}
