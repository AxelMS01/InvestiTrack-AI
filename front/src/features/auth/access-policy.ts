import { userRoles } from '../../types/user';
import type { Project, User } from '../../types';
import { can } from './model';

export function projectUnit(project: Project) {
  return project.academicUnitId ?? project.departmentId;
}

export function canCoordinateUnit(user: User, unit: string | undefined) {
  return Boolean(
    unit &&
    can(user, 'academic-unit:read') &&
    userRoles(user).includes('coordinator') &&
    (user.coordinationUnitIds ?? []).includes(unit),
  );
}

export function canCoordinateProject(user: User, project: Project) {
  return (
    canCoordinateUnit(user, projectUnit(project)) &&
    (project.supervisorIds?.includes(user.id) ?? false)
  );
}

export function evaluatorConflicted(user: User, project: Project) {
  return (
    project.leadId === user.id ||
    Boolean(project.members?.some((member) => member.userId === user.id)) ||
    Boolean(project.supervisorIds?.includes(user.id))
  );
}

export function projectEvaluation(user: User, project: Project) {
  if (!can(user, 'project:evidence-grade') || evaluatorConflicted(user, project)) return undefined;
  return project.evaluatorAssignments?.find(
    (item) => item.evaluatorUserId === user.id && item.status === 'ACTIVE',
  );
}

export function canReadProject(user: User, project: Project): boolean {
  const roles = userRoles(user);
  return (
    can(user, 'project:read') &&
    ((roles.includes('researcher') && project.leadId === user.id) ||
      canCoordinateProject(user, project) ||
      Boolean(projectEvaluation(user, project)) ||
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
    canCoordinateProject(user, project)
  );
}

export function subjectKey(user: User): string {
  return JSON.stringify([
    user.id,
    userRoles(user),
    user.status,
    user.departmentId,
    user.academicUnitIds?.slice().sort(),
    user.coordinationUnitIds?.slice().sort(),
    user.capabilities ? [...user.capabilities].sort() : null,
  ]);
}
