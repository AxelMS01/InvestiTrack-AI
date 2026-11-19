import { currentDemoUser } from '../api/auth.api';
import { ApiError } from '../api/http-client';
import { config } from '../lib/config';
import { can, type Capability } from '../features/auth/model';
import { canCoordinateUnit, canReadProject } from '../features/auth/access-policy';
import type { DemoDatabase } from './database';
import { userRoles } from '../types/user';
import type { User } from '../types';
import type { RegisteredProposal } from '../types/proposal';

export function fail(status: number, message: string): never {
  throw new ApiError(status, message);
}
export function requireDemoUser(capability?: Capability) {
  if (!config.demo)
    fail(501, 'El servicio institucional de esta pantalla está pendiente de integración.');
  const user = currentDemoUser();
  if (!user) fail(401, 'Inicia sesión para continuar.');
  if (capability && !can(user, capability))
    fail(403, 'Tu perfil no tiene permiso para esta acción.');
  return user;
}
export function canCoordinateProposal(user: User, proposal: RegisteredProposal) {
  return (
    can(user, 'proposal:decide') &&
    user.id !== proposal.ownerId &&
    canCoordinateUnit(user, proposal.academicUnitId ?? proposal.departmentId)
  );
}
export function canReadProposal(user: User, proposal: RegisteredProposal) {
  return (
    can(user, 'proposal:read') &&
    ((userRoles(user).includes('researcher') && proposal.ownerId === user.id) ||
      (canCoordinateProposal(user, proposal) && proposal.status !== 'draft') ||
      (can(user, 'evaluation:read') && proposal.reviewerIds.includes(user.id)))
  );
}
export function canEditProposal(user: User, proposal: RegisteredProposal) {
  return (
    can(user, 'proposal:update') &&
    proposal.ownerId === user.id &&
    ['draft', 'changes_requested'].includes(proposal.status)
  );
}
export function visibleProposal(data: DemoDatabase, user: User, id: string) {
  const proposal = data.proposals.find((item) => item.id === id && canReadProposal(user, item));
  return proposal ?? fail(404, 'La propuesta no está disponible para tu perfil.');
}
export function visibleProject(data: DemoDatabase, user: User, id: string) {
  const project = data.projects.find((item) => item.id === id && canReadProject(user, item));
  return project ?? fail(404, 'El proyecto no está disponible para tu perfil.');
}
export function checkVersion(expected: number, actual: number) {
  if (expected !== actual)
    fail(409, 'El registro cambió. Actualiza los datos e inténtalo de nuevo.');
}
