import { z } from 'zod';
import {
  canCoordinateProject,
  canCoordinateUnit,
  canManageProject,
  evaluatorConflicted,
  projectUnit,
} from '../features/auth/access-policy';
import { can } from '../features/auth/model';
import type { Project, User } from '../types';
import { unitInputSchema } from '../types/academic';
import { fail, requireDemoUser, visibleProject } from './access';
import { readDatabase, updateDatabase, type DemoDatabase } from './database';

const reasonSchema = z.string().trim().max(2000);
export function belongsToUnit(user: User, unit: string | undefined) {
  return Boolean(
    unit && (user.academicUnitIds ?? (user.departmentId ? [user.departmentId] : [])).includes(unit),
  );
}
export function coordinationScope(user: User, data: DemoDatabase, project: Project) {
  return (
    canCoordinateProject(user, project) &&
    data.academicUnits.some((unit) => unit.id === projectUnit(project) && unit.active)
  );
}
function openCoordination(data: DemoDatabase, user: User, projectId: string) {
  const project = visibleProject(data, user, projectId);
  if (!coordinationScope(user, data, project))
    fail(403, 'Este proyecto está fuera de tu coordinación.');
  if (project.status === 'completed') fail(409, 'El proyecto ya está cerrado.');
  return project;
}
function displayUser(data: DemoDatabase, id: string) {
  return data.users.find((user) => user.id === id)?.name ?? id;
}
export function conflictsWithWork(data: DemoDatabase, project: Project, user: User) {
  const proposal = data.proposals.find((item) => item.id === project.proposalId);
  return (
    evaluatorConflicted(user, project) ||
    proposal?.ownerId === user.id ||
    Boolean(
      proposal?.details.team.some((item) => item.email.toLowerCase() === user.email.toLowerCase()),
    )
  );
}
function activeStudent(data: DemoDatabase, id: string, project: Project) {
  return data.users.find(
    (user) =>
      user.id === id &&
      can(user, 'project:evidence-submit') &&
      belongsToUnit(user, projectUnit(project)),
  );
}
function requireCurrentVersion(actual: number, expected: number) {
  if (actual !== expected)
    fail(412, 'Los datos cambiaron. Actualiza la pantalla antes de continuar.');
}
function directoryEntry(user: User) {
  return { id: user.id, name: user.name, email: user.email, roles: user.roles ?? [] };
}
function formatRequest(data: DemoDatabase, request: DemoDatabase['studentRequests'][number]) {
  return {
    ...request,
    student: displayUser(data, request.studentUserId),
    researcher: displayUser(data, request.proposedBy),
    project: data.projects.find((project) => project.id === request.projectId)?.title ?? 'Proyecto',
    actor: request.decidedBy ? displayUser(data, request.decidedBy) : undefined,
  };
}

export const demoCoordination = {
  units() {
    const user = requireDemoUser('academic-unit:read');
    const data = readDatabase();
    return data.academicUnits.filter(
      (unit) =>
        can(user, 'academic-unit:manage') || (unit.active && canCoordinateUnit(user, unit.id)),
    );
  },
  directory(unitId: string) {
    const user = requireDemoUser('academic-unit:read');
    const data = readDatabase();
    if (
      !data.academicUnits.some(
        (unit) => unit.id === unitId && unit.active && canCoordinateUnit(user, unit.id),
      )
    )
      fail(404, 'La unidad académica no está disponible para tu cuenta.');
    return {
      investigators: data.users
        .filter((item) => belongsToUnit(item, unitId) && can(item, 'project:update'))
        .map(directoryEntry),
      evaluators: data.users
        .filter((item) => belongsToUnit(item, unitId) && can(item, 'project:evidence-grade'))
        .map(directoryEntry),
      projectCount: data.projects.filter(
        (item) => projectUnit(item) === unitId && coordinationScope(user, data, item),
      ).length,
      pendingCount: data.studentRequests.filter(
        (item) =>
          item.status === 'PENDING' &&
          data.projects.some(
            (project) =>
              project.id === item.projectId &&
              projectUnit(project) === unitId &&
              coordinationScope(user, data, project),
          ),
      ).length,
    };
  },
  saveUnit(input: { id?: string; code: string; name: string; active: boolean }) {
    const user = requireDemoUser('academic-unit:manage');
    const parsed = unitInputSchema.safeParse(input);
    if (!parsed.success) fail(422, 'Revisa el código y el nombre de la unidad.');
    return updateDatabase((data) => {
      const code = parsed.data.code.toUpperCase();
      if (data.academicUnits.some((item) => item.code === code && item.id !== input.id))
        fail(409, 'El código ya está en uso.');
      const current = data.academicUnits.find((item) => item.id === input.id);
      if (input.id && !current) fail(404, 'La unidad ya no existe.');
      if (
        !input.active &&
        data.projects.some(
          (project) => projectUnit(project) === input.id && project.status !== 'completed',
        )
      )
        fail(409, 'La unidad tiene proyectos abiertos.');
      const unit = {
        ...parsed.data,
        id: current?.id ?? crypto.randomUUID(),
        code,
        active: input.active,
      };
      if (!unit.active) {
        for (const account of data.users)
          account.coordinationUnitIds = account.coordinationUnitIds?.filter((id) => id !== unit.id);
      }
      if (current) data.academicUnits[data.academicUnits.indexOf(current)] = unit;
      else data.academicUnits.push(unit);
      data.audit.unshift({
        id: crypto.randomUUID(),
        actorId: user.id,
        targetId: unit.id,
        action: 'Unidad académica guardada',
        at: new Date().toISOString(),
      });
      return unit;
    });
  },
  unitAccounts(unitId: string) {
    requireDemoUser('academic-unit:manage');
    const data = readDatabase();
    if (!data.academicUnits.some((item) => item.id === unitId))
      fail(404, 'La unidad ya no existe.');
    return data.users
      .filter((item) => item.status === 'ACTIVE')
      .map((item) => ({
        ...directoryEntry(item),
        member: belongsToUnit(item, unitId),
        coordinates:
          (item.coordinationUnitIds ?? []).includes(unitId) &&
          can(item, 'academic-unit:read') &&
          item.roles.includes('COORDINATOR'),
      }));
  },
  assignUnit(input: { unitId: string; userId: string; member: boolean; coordinates: boolean }) {
    const actor = requireDemoUser('academic-unit:manage');
    return updateDatabase((data) => {
      const unit = data.academicUnits.find((item) => item.id === input.unitId && item.active);
      const user = data.users.find((item) => item.id === input.userId && item.status === 'ACTIVE');
      if (!unit || !user) fail(404, 'Elige una unidad y una cuenta activas.');
      if (actor.id === user.id) fail(403, 'Otra persona administradora debe actualizar tu ámbito.');
      if (input.coordinates && (!input.member || !user.roles.includes('COORDINATOR')))
        fail(422, 'La coordinación requiere pertenecer a la unidad y tener el rol de coordinador.');
      function update(values: string[], flag: boolean) {
        return [
          ...new Set([
            ...values.filter((id) => id !== input.unitId),
            ...(flag ? [input.unitId] : []),
          ]),
        ];
      }
      user.academicUnitIds = update(
        user.academicUnitIds ?? (user.departmentId ? [user.departmentId] : []),
        input.member,
      );
      user.coordinationUnitIds = update(user.coordinationUnitIds ?? [], input.coordinates);
      data.audit.unshift({
        id: crypto.randomUUID(),
        actorId: actor.id,
        targetId: user.id,
        action: 'Vínculo con unidad · ' + unit.code,
        at: new Date().toISOString(),
      });
      return user.id;
    });
  },
  assignmentContext(projectId: string) {
    const user = requireDemoUser('project:assign-evaluator');
    const data = readDatabase();
    const project = openCoordination(data, user, projectId);
    return {
      project,
      assigned: (project.evaluatorAssignments ?? []).map((item) => ({
        ...item,
        name: displayUser(data, item.evaluatorUserId),
      })),
      candidates: data.users
        .filter(
          (item) =>
            can(item, 'project:evidence-grade') &&
            !conflictsWithWork(data, project, item) &&
            !project.evaluatorAssignments?.some(
              (entry) => entry.evaluatorUserId === item.id && entry.status === 'ACTIVE',
            ),
        )
        .map(directoryEntry),
      investigators: data.users
        .filter(
          (item) =>
            can(item, 'project:update') &&
            item.id !== project.leadId &&
            belongsToUnit(item, projectUnit(project)),
        )
        .map(directoryEntry),
    };
  },
  assignEvaluator(input: { projectId: string; evaluatorUserId: string; reason: string }) {
    const user = requireDemoUser('project:assign-evaluator');
    const parsed = reasonSchema.safeParse(input.reason);
    if (!parsed.success) fail(422, 'El motivo admite hasta 2000 caracteres.');
    return updateDatabase((data) => {
      const project = openCoordination(data, user, input.projectId);
      const candidate = data.users.find((item) => item.id === input.evaluatorUserId);
      if (
        !candidate ||
        !can(candidate, 'project:evidence-grade') ||
        conflictsWithWork(data, project, candidate)
      )
        fail(403, 'El evaluador no está habilitado o tiene un conflicto de interés.');
      if (
        project.evaluatorAssignments?.some(
          (item) => item.evaluatorUserId === candidate.id && item.status === 'ACTIVE',
        )
      )
        fail(409, 'El evaluador ya tiene una asignación vigente.');
      const assignment = {
        id: crypto.randomUUID(),
        projectId: project.id,
        evaluatorUserId: candidate.id,
        status: 'ACTIVE' as const,
        assignedBy: user.id,
        assignedAt: new Date().toISOString(),
        reason: parsed.data,
      };
      project.evaluatorAssignments = [...(project.evaluatorAssignments ?? []), assignment];
      return assignment;
    });
  },
  changeLead(input: { projectId: string; userId: string; previousLeadId: string }) {
    const user = requireDemoUser('project:assign-evaluator');
    return updateDatabase((data) => {
      const project = openCoordination(data, user, input.projectId);
      if (project.leadId !== input.previousLeadId)
        fail(412, 'La responsabilidad del proyecto cambió.');
      const researcher = data.users.find(
        (item) =>
          item.id === input.userId &&
          can(item, 'project:update') &&
          belongsToUnit(item, projectUnit(project)),
      );
      if (
        !researcher ||
        researcher.id === project.leadId ||
        project.evaluatorAssignments?.some(
          (item) => item.evaluatorUserId === researcher.id && item.status === 'ACTIVE',
        )
      )
        fail(409, 'Elige un investigador de la unidad sin asignación de evaluación vigente.');
      project.members = [
        ...(project.members ?? []).filter(
          (entry) => entry.userId !== researcher.id && entry.userId !== project.leadId,
        ),
        { userId: project.leadId, responsibility: 'collaborator' },
      ];
      project.leadId = researcher.id;
      project.lead = researcher.name;
      return project;
    });
  },
  participants(projectId: string) {
    const user = requireDemoUser('project:read');
    const data = readDatabase();
    const project = visibleProject(data, user, projectId);
    return {
      unit: data.academicUnits.find((unit) => unit.id === projectUnit(project))?.name,
      coordinators: (project.supervisorIds ?? []).map((id) => displayUser(data, id)),
      evaluators: (project.evaluatorAssignments ?? [])
        .filter((item) => item.status === 'ACTIVE')
        .map((item) => displayUser(data, item.evaluatorUserId)),
      canAssign:
        can(user, 'project:assign-evaluator') &&
        coordinationScope(user, data, project) &&
        project.status !== 'completed',
      canPropose: can(user, 'project:student-propose') && canManageProject(user, project),
    };
  },
  studentCandidates(projectId: string) {
    const user = requireDemoUser('project:student-propose');
    const data = readDatabase();
    const project = visibleProject(data, user, projectId);
    if (!canManageProject(user, project))
      fail(403, 'La propuesta corresponde a la persona responsable del proyecto.');
    return {
      project,
      candidates: data.users
        .filter(
          (account) =>
            activeStudent(data, account.id, project) &&
            !project.members?.some((member) => member.userId === account.id) &&
            !project.evaluatorAssignments?.some(
              (entry) => entry.evaluatorUserId === account.id && entry.status === 'ACTIVE',
            ) &&
            !data.studentRequests.some(
              (entry) =>
                entry.projectId === projectId &&
                entry.studentUserId === account.id &&
                entry.status === 'PENDING',
            ),
        )
        .map(directoryEntry),
    };
  },
  propose(input: { projectId: string; studentUserId: string; rationale: string }) {
    const user = requireDemoUser('project:student-propose');
    const rationale = reasonSchema.safeParse(input.rationale);
    if (!rationale.success || !rationale.data)
      fail(422, 'Ingresa la justificación o responsabilidad del estudiante.');
    return updateDatabase((data) => {
      const project = visibleProject(data, user, input.projectId);
      if (!canManageProject(user, project))
        fail(403, 'No puedes proponer estudiantes en este proyecto.');
      if (
        !activeStudent(data, input.studentUserId, project) ||
        input.studentUserId === project.leadId
      )
        fail(422, 'Selecciona un estudiante activo de la unidad del proyecto.');
      if (
        project.members?.some((item) => item.userId === input.studentUserId) ||
        project.evaluatorAssignments?.some(
          (entry) => entry.evaluatorUserId === input.studentUserId && entry.status === 'ACTIVE',
        ) ||
        data.studentRequests.some(
          (item) =>
            item.projectId === project.id &&
            item.studentUserId === input.studentUserId &&
            item.status === 'PENDING',
        )
      )
        fail(409, 'La persona ya participa o tiene una solicitud pendiente.');
      const request = {
        id: crypto.randomUUID(),
        projectId: project.id,
        studentUserId: input.studentUserId,
        proposedBy: user.id,
        proposedAt: new Date().toISOString(),
        rationale: rationale.data,
        status: 'PENDING' as const,
        version: 1,
      };
      data.studentRequests.unshift(request);
      return request;
    });
  },
  requests() {
    const user = requireDemoUser('project:student-requests');
    const data = readDatabase();
    return data.studentRequests.flatMap((request) => {
      const project = data.projects.find((item) => item.id === request.projectId);
      const canDecide = Boolean(
        project &&
        coordinationScope(user, data, project) &&
        can(user, 'project:student-approve') &&
        project.status !== 'completed',
      );
      const canCancel = Boolean(
        project &&
        request.proposedBy === user.id &&
        canManageProject(user, project) &&
        can(user, 'project:student-propose'),
      );
      return project &&
        (canCancel ||
          (request.proposedBy === user.id && can(user, 'project:student-propose')) ||
          (coordinationScope(user, data, project) && can(user, 'project:student-approve')))
        ? [{ ...formatRequest(data, request), canDecide, canCancel, unitId: projectUnit(project) }]
        : [];
    });
  },
  request(id: string) {
    return (
      demoCoordination.requests().find((item) => item.id === id) ??
      fail(404, 'La solicitud no está disponible para tu cuenta.')
    );
  },
  decide(input: {
    id: string;
    version: number;
    decision: 'APPROVED' | 'REJECTED' | 'CANCELLED';
    reason: string;
  }) {
    const cancel = input.decision === 'CANCELLED';
    const user = requireDemoUser(cancel ? 'project:student-propose' : 'project:student-approve');
    const decision = z.enum(['APPROVED', 'REJECTED', 'CANCELLED']).safeParse(input.decision);
    const reason = reasonSchema.safeParse(input.reason);
    if (!decision.success || !reason.success) fail(422, 'Revisa la decisión y el motivo.');
    return updateDatabase((data) => {
      const request = data.studentRequests.find((item) => item.id === input.id);
      if (!request) fail(404, 'La solicitud no está disponible.');
      const project = cancel
        ? visibleProject(data, user, request.projectId)
        : openCoordination(data, user, request.projectId);
      if (cancel && (request.proposedBy !== user.id || !canManageProject(user, project)))
        fail(403, 'Solo el investigador que propuso puede cancelar la solicitud pendiente.');
      requireCurrentVersion(request.version, input.version);
      if (request.status !== 'PENDING') fail(409, 'La solicitud ya fue resuelta.');
      if (decision.data === 'APPROVED') {
        if (
          !activeStudent(data, request.studentUserId, project) ||
          project.members?.some((item) => item.userId === request.studentUserId) ||
          project.evaluatorAssignments?.some(
            (entry) => entry.evaluatorUserId === request.studentUserId && entry.status === 'ACTIVE',
          )
        )
          fail(409, 'Ya no se puede incorporar a este estudiante.');
        project.members = [
          ...(project.members ?? []),
          { userId: request.studentUserId, responsibility: 'student' },
        ];
      }
      request.status = decision.data;
      request.version++;
      if (!cancel) {
        request.decidedBy = user.id;
        request.decidedAt = new Date().toISOString();
        request.decisionReason = reason.data;
      }
      data.audit.unshift({
        id: crypto.randomUUID(),
        actorId: user.id,
        targetId: request.studentUserId,
        action: 'Solicitud de incorporación · ' + decision.data,
        at: new Date().toISOString(),
      });
      return request;
    });
  },
};
