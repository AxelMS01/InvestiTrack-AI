import { z } from 'zod';
import { can } from '../features/auth/model';
import { canManageProject, canReadProject } from '../features/auth/access-policy';
import { activitySchema, type Activity } from '../types/activity';
import type { Project, User } from '../types';
import { fail, requireDemoUser, visibleProject } from './access';
import { readDatabase, updateDatabase, type DemoDatabase } from './database';

export const activityStatusLabels: Record<Activity['status'], string> = {
  pending: 'Por iniciar',
  in_progress: 'En curso',
  blocked: 'Bloqueada',
  completed: 'Completada',
  cancelled: 'Cancelada',
};
export const activityInputSchema = activitySchema
  .pick({
    projectId: true,
    title: true,
    description: true,
    assigneeId: true,
    dueDate: true,
    status: true,
    isMilestone: true,
    milestoneId: true,
  })
  .extend({
    title: z.string().trim().min(3).max(160),
    description: z.string().trim().min(5).max(4000),
  });
export type ActivityInput = z.infer<typeof activityInputSchema>;

function canReadActivity(user: User, project: Project, activity: Activity) {
  return (
    can(user, 'activity:read') &&
    canReadProject(user, project) &&
    (project.leadId === user.id ||
      project.supervisorIds?.includes(user.id) ||
      activity.assigneeId === user.id ||
      activity.isMilestone)
  );
}
export function visibleActivity(data: DemoDatabase, user: User, id: string) {
  const activity = data.activities.find((item) => item.id === id);
  const project = data.projects.find((item) => item.id === activity?.projectId);
  if (!activity || !project || !canReadActivity(user, project, activity))
    fail(404, 'La actividad no está disponible para tu perfil.');
  return { activity, project };
}
function updateProgress(data: DemoDatabase, project: Project) {
  const tasks = data.activities.filter(
    (item) => item.projectId === project.id && item.status !== 'cancelled',
  );
  project.progress = tasks.length
    ? Math.round((100 * tasks.filter((item) => item.status === 'completed').length) / tasks.length)
    : 0;
}

export const demoActivities = {
  list() {
    const user = requireDemoUser('activity:read');
    const data = readDatabase();
    return data.activities
      .flatMap((activity) => {
        const project = data.projects.find((item) => item.id === activity.projectId);
        if (!project || !canReadActivity(user, project, activity)) return [];
        return [{ ...activity, projectTitle: project.title }];
      })
      .sort((a, b) => a.dueDate.localeCompare(b.dueDate));
  },
  detail(id: string) {
    const user = requireDemoUser('activity:read');
    const data = readDatabase();
    const { activity, project } = visibleActivity(data, user, id);
    return {
      activity,
      project,
      manager: canManageProject(user, project) && can(user, 'project:manage-activities'),
      canUpdate:
        project.status !== 'completed' &&
        can(user, 'activity:update') &&
        activity.assigneeId === user.id &&
        !['completed', 'cancelled'].includes(activity.status),
      assigner: data.users.find((item) => item.id === activity.assignedBy)?.name,
      assignee: data.users.find((item) => item.id === activity.assigneeId)?.name,
    };
  },
  options(projectId: string) {
    const user = requireDemoUser('activity:create');
    const data = readDatabase();
    const project = visibleProject(data, user, projectId);
    if (!canManageProject(user, project) || !can(user, 'project:manage-activities'))
      fail(403, 'Solo la persona responsable puede crear actividades.');
    return {
      members: data.users
        .filter(
          (item) =>
            item.status === 'ACTIVE' &&
            (item.id === project.leadId ||
              project.members?.some((member) => member.userId === item.id)),
        )
        .map(({ id, name }) => ({ id, name })),
      milestones: data.activities.filter(
        (item) => item.projectId === project.id && item.isMilestone && item.status !== 'cancelled',
      ),
    };
  },
  save(input: { id?: string; values: ActivityInput }) {
    const user = requireDemoUser(input.id ? 'activity:update' : 'activity:create');
    const parsed = activityInputSchema.safeParse(input.values);
    if (!parsed.success) fail(422, 'Revisa el título, instrucciones, responsable y fecha.');
    const values = parsed.data;
    return updateDatabase((data) => {
      const project = visibleProject(data, user, values.projectId);
      const existing = input.id ? visibleActivity(data, user, input.id).activity : undefined;
      if (
        !canManageProject(user, project) ||
        !can(user, 'project:manage-activities') ||
        (existing && existing.projectId !== project.id)
      )
        fail(403, 'No puedes administrar esta actividad.');
      if (values.dueDate < project.startDate || values.dueDate > project.endDate)
        fail(422, 'La fecha debe estar dentro del periodo del proyecto.');
      if (
        !data.users.some((item) => item.id === values.assigneeId && item.status === 'ACTIVE') ||
        (values.assigneeId !== project.leadId &&
          !project.members?.some((item) => item.userId === values.assigneeId))
      )
        fail(422, 'Selecciona una persona activa que pertenezca al proyecto.');
      if ((!existing || existing.assigneeId !== values.assigneeId) && !can(user, 'activity:assign'))
        fail(403, 'Tu perfil no puede asignar responsables.');
      if (
        values.milestoneId &&
        (values.isMilestone ||
          !data.activities.some(
            (item) =>
              item.id === values.milestoneId &&
              item.projectId === project.id &&
              item.isMilestone &&
              item.status !== 'cancelled',
          ))
      )
        fail(422, 'Selecciona un hito vigente del proyecto.');
      if (
        existing?.isMilestone &&
        (!values.isMilestone || values.status === 'cancelled') &&
        data.activities.some((item) => item.milestoneId === existing.id)
      )
        fail(409, 'Desvincula primero las actividades asociadas al hito.');
      const activity: Activity = {
        ...values,
        id: existing?.id ?? crypto.randomUUID(),
        assignedBy: existing?.assigneeId === values.assigneeId ? existing.assignedBy : user.id,
      };
      if (existing) data.activities[data.activities.indexOf(existing)] = activity;
      else data.activities.push(activity);
      updateProgress(data, project);
      return activity;
    });
  },
  updateStatus(input: { id: string; status: 'in_progress' | 'blocked' | 'completed' }) {
    const user = requireDemoUser('activity:update');
    return updateDatabase((data) => {
      const { activity, project } = visibleActivity(data, user, input.id);
      if (
        project.status === 'completed' ||
        activity.assigneeId !== user.id ||
        ['completed', 'cancelled'].includes(activity.status)
      )
        fail(403, 'No puedes actualizar esta tarea.');
      if (!['in_progress', 'blocked', 'completed'].includes(input.status))
        fail(422, 'El estado seleccionado no es válido.');
      activity.status = input.status;
      updateProgress(data, project);
      return activity;
    });
  },
};
