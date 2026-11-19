import {
  canCoordinateProject,
  canManageProject,
  canReadProject,
  projectEvaluation,
} from '../features/auth/access-policy';
import { can } from '../features/auth/model';
import type { Project, User } from '../types';
import type { Evidence, EvidenceVersion } from '../types/evidence';
import type { DemoDatabase } from './database';
import { conflictsWithWork } from './coordination';

export function evidenceAssignment(
  data: DemoDatabase,
  user: User,
  project: Project,
  evidence?: Evidence,
) {
  if (evidence?.ownerUserId === user.id || conflictsWithWork(data, project, user)) return undefined;
  return projectEvaluation(user, project);
}
export function ownsEvidence(user: User, evidence: Evidence) {
  return user.id === evidence.ownerUserId && can(user, 'project:evidence-submit');
}
export function canReadEvidence(
  data: DemoDatabase,
  user: User,
  project: Project,
  evidence: Evidence,
) {
  if (!can(user, 'project:evidence-read') || !canReadProject(user, project)) return false;
  if (ownsEvidence(user, evidence)) return true;
  if (evidence.status === 'DRAFT') return false;
  return Boolean(
    canCoordinateProject(user, project) ||
    (can(user, 'project:update') &&
      (project.leadId === user.id ||
        project.members?.some(
          (item) => item.userId === user.id && item.responsibility === 'collaborator',
        ))) ||
    evidenceAssignment(data, user, project, evidence),
  );
}
export function canReadEvidenceVersion(data: DemoDatabase, user: User, version: EvidenceVersion) {
  const evidence = data.evidences.find((item) => item.id === version.evidenceId);
  const project = data.projects.find((item) => item.id === evidence?.projectId);
  return Boolean(
    evidence &&
    project &&
    canReadEvidence(data, user, project, evidence) &&
    (version.submittedAt || ownsEvidence(user, evidence)),
  );
}
export function canReviewFormatively(user: User, project: Project, evidence: Evidence) {
  return (
    can(user, 'project:evidence-revise') &&
    canManageProject(user, project) &&
    evidence.ownerUserId !== user.id
  );
}
