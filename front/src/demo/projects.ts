import { z } from 'zod';
import { canCloseProject, canManageProject, canReadProject } from '../features/auth/access-policy';
import { can } from '../features/auth/model';
import {
  projectInputSchema,
  projectMemberSchema,
  statusSchema,
  type Project,
} from '../types/project';
import {
  canCoordinateProposal,
  fail,
  requireDemoUser,
  visibleProject,
  visibleProposal,
} from './access';
import { readDatabase, updateDatabase, type DemoDatabase } from './database';

export function closureRequirements(data: DemoDatabase, id: string) {
  const activities = data.activities.filter((item) => item.projectId === id);
  return {
    report: data.projects.some(
      (item) =>
        item.id === id &&
        item.finalReport?.submittedAt &&
        data.documents.some(
          (document) =>
            document.id === item.finalReport?.documentId &&
            document.projectId === id &&
            document.category === 'report',
        ),
    ),
    activities: activities.every((item) => ['completed', 'cancelled'].includes(item.status)),
  };
}

function manageable(data: DemoDatabase, id: string, user: ReturnType<typeof requireDemoUser>) {
  const project = visibleProject(data, user, id);
  if (!canManageProject(user, project))
    fail(403, 'Solo la persona responsable puede modificar este proyecto abierto.');
  return project;
}

export const demoProjects = {
  list() {
    const user = requireDemoUser('project:read');
    const data = readDatabase();
    return data.projects
      .filter((project) => canReadProject(user, project))
      .map((project) => ({
        ...project,
        members: project.members?.map((member) => ({
          ...member,
          name: data.users.find((account) => account.id === member.userId)?.name,
        })),
      }));
  },
  detail(id: string) {
    const project = demoProjects.list().find((item) => item.id === id);
    return project ?? fail(404, 'El proyecto no está disponible para tu perfil.');
  },
  convertible() {
    const user = requireDemoUser('project:convert');
    const data = readDatabase();
    return data.proposals.filter(
      (proposal) =>
        proposal.status === 'approved' &&
        (proposal.ownerId === user.id || canCoordinateProposal(user, proposal)) &&
        !data.projects.some((project) => project.proposalId === proposal.id),
    );
  },
  convert(proposalId: string) {
    const user = requireDemoUser('project:convert');
    if (typeof proposalId !== 'string' || !proposalId)
      fail(422, 'Selecciona una propuesta aprobada.');
    return updateDatabase((data) => {
      const proposal = visibleProposal(data, user, proposalId);
      if (proposal.ownerId !== user.id && !canCoordinateProposal(user, proposal))
        fail(403, 'No puedes convertir esta propuesta.');
      if (proposal.status !== 'approved') fail(409, 'Se requiere una propuesta aprobada.');
      if (data.projects.some((project) => project.proposalId === proposal.id))
        fail(409, 'Esta propuesta ya tiene un proyecto.');
      const owner = data.users.find((item) => item.id === proposal.ownerId);
      if (!owner) fail(409, 'La propuesta requiere un responsable registrado.');
      const id = crypto.randomUUID();
      const { title, abstract, area, budgetItems, startDate, endDate } = proposal.details;
      const approver = proposal.history
        .slice()
        .reverse()
        .find((item) => item.status === 'approved')?.actorId;
      const supervisor = data.users.find(
        (item) => item.id === approver && canCoordinateProposal(item, proposal),
      );
      if (!supervisor)
        fail(
          409,
          'La coordinación que aprobó la propuesta debe estar activa para supervisar el proyecto.',
        );
      const supervisorIds = [supervisor.id];
      const project: Project = {
        id,
        code: `INV-${new Date().getFullYear()}-${id.slice(0, 8).toUpperCase()}`,
        title,
        description: abstract,
        area,
        budget: budgetItems.reduce((sum, item) => sum + Math.round(item.amount * 100), 0) / 100,
        startDate,
        endDate,
        status: 'active',
        progress: 0,
        lead: owner.name,
        leadId: owner.id,
        departmentId: proposal.departmentId,
        proposalId,
        supervisorIds,
        members: [],
      };
      data.projects.unshift(project);
      return project;
    });
  },
  directory(id: string) {
    const user = requireDemoUser('project:manage-members');
    const data = readDatabase();
    manageable(data, id, user);
    return data.users
      .filter((item) => item.id !== user.id && can(item, 'project:read'))
      .map(({ id, name, email }) => ({ id, name, email }));
  },
  member(input: {
    id: string;
    userId: string;
    responsibility: 'student' | 'collaborator' | 'remove';
  }) {
    const user = requireDemoUser('project:manage-members');
    return updateDatabase((data) => {
      const project = manageable(data, input.id, user);
      const members = project.members ?? [];
      if (input.responsibility === 'remove') {
        if (
          data.activities.some(
            (item) =>
              item.projectId === project.id &&
              item.assigneeId === input.userId &&
              !['completed', 'cancelled'].includes(item.status),
          )
        )
          fail(409, 'Reasigna las actividades abiertas antes de retirar al colaborador.');
        project.members = members.filter((member) => member.userId !== input.userId);
      } else {
        const member = projectMemberSchema.parse(input);
        const account = data.users.find((item) => item.id === member.userId);
        if (
          !account ||
          account.id === project.leadId ||
          !can(account, 'project:read') ||
          (member.responsibility === 'student' && !account.roles.includes('STUDENT')) ||
          (member.responsibility === 'collaborator' && !account.roles.includes('RESEARCHER'))
        )
          fail(422, 'El perfil elegido no admite esa responsabilidad.');
        project.members = [...members.filter((item) => item.userId !== member.userId), member];
      }
      return project;
    });
  },
  update(input: { id: string; settings: z.input<typeof projectInputSchema>; status: string }) {
    const user = requireDemoUser('project:update');
    const parsed = projectInputSchema.safeParse(input.settings);
    const status = statusSchema.safeParse(input.status);
    if (!parsed.success || !status.success || status.data === 'completed')
      fail(422, 'Revisa los datos y fechas del proyecto.');
    return updateDatabase((data) => {
      const project = manageable(data, input.id, user);
      if (
        status.data === 'active' &&
        !data.proposals.some((item) => item.id === project.proposalId && item.status === 'approved')
      )
        fail(409, 'El proyecto necesita una propuesta aprobada para entrar en ejecución.');
      if (
        data.activities.some(
          (item) =>
            item.projectId === project.id &&
            !['completed', 'cancelled'].includes(item.status) &&
            (item.dueDate < parsed.data.startDate || item.dueDate > parsed.data.endDate),
        )
      )
        fail(
          409,
          'El nuevo periodo dejaría actividades abiertas fuera de las fechas del proyecto.',
        );
      Object.assign(project, parsed.data, { status: status.data });
      return project;
    });
  },
  closure(id: string) {
    const user = requireDemoUser('project:close');
    const data = readDatabase();
    const project = visibleProject(data, user, id);
    if (!project.supervisorIds?.includes(user.id))
      fail(403, 'Solo la coordinación asignada puede consultar el cierre.');
    return { project, ...closureRequirements(data, id) };
  },
  close(input: { id: string; confirmed: boolean }) {
    const user = requireDemoUser('project:close');
    return updateDatabase((data) => {
      const project = visibleProject(data, user, input.id);
      if (!canCloseProject(user, project))
        fail(403, 'Solo la coordinación asignada puede cerrar un proyecto abierto.');
      const requirements = closureRequirements(data, project.id);
      if (!input.confirmed || !requirements.report || !requirements.activities)
        fail(
          409,
          'Presenta el informe final, resuelve las actividades abiertas y confirma el cierre.',
        );
      project.status = 'completed';
      project.progress = 100;
      project.closedAt = new Date().toISOString();
      return project;
    });
  },
};
