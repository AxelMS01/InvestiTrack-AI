import { ArrowRight, CheckCheck, CircleDot, Clock3, FolderKanban } from 'lucide-react';
import { Link } from 'react-router-dom';
import { useDemoQuery } from '../../api/demo.queries';
import { authApi } from '../../api/auth.api';
import { useProjects } from '../../api/projects.queries';
import { useProposals } from '../../api/proposals.queries';
import { AsyncState, PageHeading } from '../../components/ui';
import { demoAdministration } from '../../demo/administration';
import { demoActivities } from '../../demo/activities';
import { canCoordinateProposal } from '../../demo/access';
import { demoDocuments } from '../../demo/documents';
import { demoEvaluations } from '../../demo/evaluations';
import { addDays, dateLabel, today } from '../../lib/format';
import { config } from '../../lib/config';
import type { Role } from '../../types';
import { useAuth } from '../auth/auth-context';
import { can, roleLabels } from '../auth/model';
import { ProjectTable } from '../projects/project-table';
import { ActionLink, WorkflowList } from '../shared/workflow-ui';
import { workspaceTitles } from './navigation';

const colors = [
  'bg-sage text-forest',
  'bg-blue-50 text-blue-700',
  'bg-amber-50 text-amber-700',
  'bg-violet-50 text-violet-700',
];
const icons = [FolderKanban, CircleDot, Clock3, CheckCheck];
type Task = { id: string; title: string; detail?: string; path: string; action?: string };

export function DashboardPage({ roleOverride }: { roleOverride?: Role }) {
  const { user, workspace } = useAuth();
  const role = roleOverride ?? workspace ?? user?.role ?? 'researcher';
  const allowed = (permission: Parameters<typeof can>[1]) => Boolean(user && can(user, permission));
  const projectQuery = useProjects(
    role !== 'admin' && role !== 'reviewer' && allowed('project:read'),
  );
  const proposalQuery = useProposals(
    (role === 'coordinator' || role === 'researcher') && allowed('proposal:read'),
  );
  const activityQuery = useDemoQuery(
    ['activities'],
    demoActivities.list,
    config.demo && role !== 'reviewer' && role !== 'admin' && allowed('activity:read'),
  );
  const evaluationQuery = useDemoQuery(
    ['evaluations'],
    demoEvaluations.list,
    config.demo && (role === 'reviewer' || role === 'coordinator') && allowed('evaluation:read'),
  );
  const userQuery = useDemoQuery(
    ['users'],
    authApi.users,
    role === 'admin' && allowed('user:read'),
  );
  const auditQuery = useDemoQuery(
    ['audit'],
    demoAdministration.audit,
    config.demo && role === 'admin' && allowed('audit:read'),
  );
  const documentQuery = useDemoQuery(
    ['documents'],
    demoDocuments.list,
    config.demo && role === 'student' && allowed('document:read'),
  );
  if (!user) return null;
  const queries = [
    projectQuery,
    proposalQuery,
    activityQuery,
    evaluationQuery,
    userQuery,
    auditQuery,
    documentQuery,
  ].filter((query) => query.isEnabled);
  if (queries.some((query) => query.isPending)) return <AsyncState loading />;
  const failed = queries.find((query) => query.error);
  const projects = (projectQuery.data ?? []).filter(
    (item) =>
      !config.demo ||
      (role === 'coordinator'
        ? item.supervisorIds?.includes(user.id)
        : role === 'student'
          ? item.members?.some(
              (member) => member.userId === user.id && member.responsibility === 'student',
            )
          : item.leadId === user.id ||
            item.members?.some(
              (member) => member.userId === user.id && member.responsibility === 'collaborator',
            )),
  );
  const proposals = (proposalQuery.data ?? []).filter(
    (item) =>
      !config.demo ||
      (role === 'coordinator' ? canCoordinateProposal(user, item) : item.ownerId === user.id),
  );
  const tasks = (activityQuery.data ?? []).filter(
    (item) =>
      projects.some((project) => project.id === item.projectId) &&
      (role !== 'student' || item.assigneeId === user.id),
  );
  const pending = tasks.filter((item) => !['completed', 'cancelled'].includes(item.status));
  const upcoming = pending.filter((item) => item.dueDate >= today() && item.dueDate <= addDays(30));
  const drafts = proposals.filter(
    (item) => item.status === 'draft' || item.status === 'changes_requested',
  );
  const reviews = (evaluationQuery.data ?? []).filter((item) =>
    role === 'reviewer'
      ? item.reviewerId === user.id
      : proposals.some((proposal) => proposal.id === item.proposalId),
  );
  const reviewPending = reviews.filter((item) => item.status !== 'submitted' && !item.superseded);
  const decisions = proposals.filter((proposal) => {
    const related = reviews.filter(
      (item) =>
        item.proposalId === proposal.id && item.submissionVersion === proposal.submissionVersion,
    );
    return (
      proposal.status === 'under_review' &&
      related.length > 0 &&
      related.every((item) => item.status === 'submitted')
    );
  });
  const awaitingAssignment = proposals.filter((item) => item.status === 'submitted');
  const users = userQuery.data ?? [];
  const audit = (auditQuery.data ?? []).filter((item) => item.action.startsWith('Roles asignados'));
  const activitiesWork: Task[] = pending
    .filter((item) => role !== 'researcher' || item.assigneeId === user.id)
    .slice(0, 5)
    .map((item) => ({
      id: item.id,
      title: item.title,
      path: `/actividades/${item.id}`,
      detail: `${item.dueDate < today() ? 'Vencida · ' : ''}${dateLabel(item.dueDate)}`,
    }));
  const reviewWork: Task[] = reviewPending.slice(0, 5).map((item) => ({
    id: item.id,
    title: item.proposalTitle,
    path: `/evaluaciones/${item.id}/rubrica`,
    detail: item.dueDate ? dateLabel(item.dueDate) : undefined,
    action: item.status === 'assigned' ? 'Comenzar rúbrica' : 'Continuar rúbrica',
  }));
  let work: Task[] = activitiesWork;
  let stats: Array<[string, number | string]> = [];
  let primary: Task | undefined;
  if (role === 'admin') {
    stats = [
      ['Usuarios activos', users.filter((item) => !item.status || item.status === 'ACTIVE').length],
      ['Cuentas desactivadas', users.filter((item) => item.status === 'INACTIVE').length],
      ['Cuentas pendientes', users.filter((item) => item.status === 'PENDING').length],
    ];
    if (auditQuery.isEnabled) stats.push(['Cambios de roles', audit.length]);
    work = audit.slice(0, 5).map((item) => ({
      id: item.id,
      title: item.target,
      detail: new Date(item.at).toLocaleString('es-MX'),
      path: '/administracion/auditoria',
      action: 'Ver auditoría',
    }));
    if (allowed('user:read'))
      primary = { id: 'users', title: 'Gestionar usuarios', path: '/usuarios' };
  } else if (role === 'coordinator') {
    if (proposalQuery.isEnabled) stats.push(['Propuestas recibidas', proposals.length]);
    if (evaluationQuery.isEnabled)
      stats.push(
        ['Evaluaciones pendientes', reviewPending.length],
        ['Decisiones pendientes', decisions.length],
      );
    if (projectQuery.isEnabled)
      stats.push(
        ['Proyectos activos', projects.filter((item) => item.status === 'active').length],
        [
          'Proyectos retrasados',
          projects.filter((item) => item.status !== 'completed' && item.endDate < today()).length,
        ],
      );
    if (activityQuery.isEnabled)
      stats.push(['Hitos próximos', upcoming.filter((item) => item.isMilestone).length]);
    work = [
      ...(allowed('proposal:review') ? decisions : []).map((item) => ({
        id: item.id,
        title: item.title,
        path: `/evaluaciones/${item.id}/decision`,
        action: 'Registrar decisión',
      })),
      ...awaitingAssignment.map((item) => ({
        id: item.id,
        title: item.title,
        path: `/evaluaciones/asignar?propuesta=${item.id}`,
        action: 'Asignar evaluación',
      })),
    ]
      .filter((item) => item.action !== 'Asignar evaluación' || allowed('evaluation:assign'))
      .slice(0, 5);
    if (allowed('evaluation:read'))
      primary = {
        id: 'reviews',
        title: 'Atender evaluaciones pendientes',
        path: '/evaluaciones?estado=pendientes',
      };
  } else if (role === 'reviewer') {
    if (evaluationQuery.isEnabled)
      stats = [
        ['Evaluaciones asignadas', reviews.length],
        ['Pendientes', reviewPending.length],
        [
          'Próximas a vencer',
          reviewPending.filter(
            (item) => item.dueDate && item.dueDate >= today() && item.dueDate <= addDays(7),
          ).length,
        ],
        ['Evaluaciones enviadas', reviews.filter((item) => item.status === 'submitted').length],
      ];
    work = allowed('evaluation:create') ? reviewWork : [];
    primary =
      work[0] ??
      (allowed('evaluation:read')
        ? { id: 'evaluations', title: 'Ver mis evaluaciones', path: '/evaluaciones' }
        : undefined);
  } else if (role === 'student') {
    if (projectQuery.isEnabled) stats.push(['Mis proyectos', projects.length]);
    if (activityQuery.isEnabled)
      stats.push(
        ['Tareas pendientes', pending.length],
        ['Entregas próximas', upcoming.length],
        ['Actividades completadas', tasks.filter((item) => item.status === 'completed').length],
      );
    if (documentQuery.isEnabled && activityQuery.isEnabled)
      stats.push([
        'Evidencias pendientes',
        pending.filter(
          (item) => !documentQuery.data?.some((document) => document.activityId === item.id),
        ).length,
      ]);
    primary =
      work[0] ??
      (allowed('activity:read')
        ? { id: 'tasks', title: 'Consultar mis actividades', path: '/actividades?vista=mias' }
        : undefined);
  } else {
    if (proposalQuery.isEnabled)
      stats.push(
        ['Mis propuestas', proposals.length],
        ['Borradores y correcciones', drafts.length],
      );
    if (projectQuery.isEnabled)
      stats.push(
        ['Mis proyectos', projects.length],
        [
          'Avance promedio',
          `${projects.length ? Math.round(projects.reduce((sum, project) => sum + project.progress, 0) / projects.length) : 0}%`,
        ],
      );
    if (activityQuery.isEnabled)
      stats.push(
        ['Actividades próximas', upcoming.length],
        ['Hitos próximos', upcoming.filter((item) => item.isMilestone).length],
      );
    work = [
      ...drafts.map((item) => ({
        id: item.id,
        title: item.title || 'Propuesta sin título',
        path:
          allowed('proposal:update') && config.demo
            ? `/propuestas/${item.id}/editar`
            : `/propuestas/${item.id}`,
        action: 'Continuar propuesta',
      })),
      ...work,
    ].slice(0, 5);
    primary =
      work[0] ??
      (config.demo && allowed('proposal:create')
        ? { id: 'proposal', title: 'Crear propuesta', path: '/propuestas/nueva' }
        : undefined);
  }
  return (
    <>
      <PageHeading
        eyebrow={roleLabels[role]}
        title={workspaceTitles[role]}
        description={`Hola, ${user.name.split(' ')[0]}. Consulta las acciones y pendientes de tu espacio de trabajo.`}
        action={<ActionLink to="/notificaciones">Notificaciones</ActionLink>}
      />
      {failed && (
        <AsyncState error={failed.error ?? undefined} retry={() => void failed.refetch()} />
      )}
      {primary && (
        <section className="hero-panel mb-7 p-7 sm:p-8">
          <p className="text-xs font-medium text-lime-200">Acción prioritaria</p>
          <h2 className="mt-3 text-2xl font-medium">{primary.title}</h2>
          <Link
            className="mt-4 inline-flex items-center gap-2 text-sm text-lime-200"
            to={primary.path}
          >
            {primary.action ?? 'Continuar'}
            <ArrowRight size={16} />
          </Link>
        </section>
      )}
      <section
        aria-label="Indicadores del espacio"
        className="mb-8 grid gap-4 sm:grid-cols-2 xl:grid-cols-4"
      >
        {stats.map(([label, value], index) => {
          const Icon = icons[index % icons.length] ?? CircleDot;
          return (
            <article key={label} className="panel p-5">
              <div className="flex items-center justify-between gap-2">
                <h2 className="text-sm text-muted">{label}</h2>
                <span className={`rounded-lg p-2 ${colors[index % colors.length]}`}>
                  <Icon size={17} aria-hidden="true" />
                </span>
              </div>
              <p className="mt-3 text-3xl font-semibold tracking-tight">{value}</p>
            </article>
          );
        })}
      </section>
      <h2 className="section-title mb-3">
        {role === 'admin' ? 'Cambios recientes de roles' : 'Trabajo pendiente'}
      </h2>
      <WorkflowList
        empty={
          role === 'admin'
            ? 'Todavía no hay cambios de roles registrados.'
            : 'No hay acciones pendientes para este espacio.'
        }
      >
        {work.map((item) => (
          <article key={item.id} className="flex flex-wrap items-center justify-between gap-3 p-5">
            <div>
              <h3 className="font-medium">{item.title}</h3>
              {item.detail && <p className="text-sm text-muted">{item.detail}</p>}
            </div>
            <ActionLink to={item.path}>{item.action ?? 'Consultar'}</ActionLink>
          </article>
        ))}
      </WorkflowList>
      {projectQuery.isEnabled && (
        <section className="panel mt-6 overflow-hidden">
          <div className="flex items-center justify-between gap-3 p-5">
            <h2 className="section-title">Avance de proyectos</h2>
            <ActionLink to="/proyectos">Ver proyectos</ActionLink>
          </div>
          <ProjectTable projects={projects.slice(0, 4)} />
        </section>
      )}
    </>
  );
}
