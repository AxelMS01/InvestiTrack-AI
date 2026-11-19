import { useState } from 'react';
import { useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { useDemoQuery, useWorkflowMutation } from '../../api/demo.queries';
import { useProjects } from '../../api/projects.queries';
import { Button } from '../../components/button';
import { Input } from '../../components/input';
import { PageHeading } from '../../components/ui';
import { activityInputSchema, activityStatusLabels, demoActivities } from '../../demo/activities';
import { demoEvidences } from '../../demo/evidences';
import { evidenceStatusLabels } from '../../types/evidence';
import { projectUnit } from '../auth/access-policy';
import { dateLabel, today } from '../../lib/format';
import type { Activity, Project } from '../../types';
import { canManageProject } from '../auth/access-policy';
import { useAuth } from '../auth/auth-context';
import { can } from '../auth/model';
import { ActionLink, Feedback, Field, WorkflowList, WorkflowState } from '../shared/workflow-ui';

function dueLabel(item: Activity) {
  if (['completed', 'cancelled'].includes(item.status)) return dateLabel(item.dueDate);
  return item.dueDate < today() ? `Vencida · ${dateLabel(item.dueDate)}` : dateLabel(item.dueDate);
}

export function ActivitiesPage({ view = 'list' }: { view?: 'list' | 'milestones' | 'calendar' }) {
  const [params, setParams] = useSearchParams();
  const { user, workspace, unitId } = useAuth();
  const query = useDemoQuery(['activities'], demoActivities.list);
  const projectId = params.get('proyecto') ?? '';
  const projects = useProjects();
  const own = params.get('vista') === 'mias' || workspace === 'student';
  const [period, setPeriod] = useState('');
  const title =
    view === 'milestones'
      ? 'Hitos'
      : view === 'calendar'
        ? 'Calendario de entregas'
        : own
          ? 'Mis actividades'
          : 'Actividades';
  const entries = (query.data ?? []).filter(
    (item) =>
      (!projectId || item.projectId === projectId) &&
      (workspace !== 'coordinator' ||
        !unitId ||
        projects.data?.some(
          (project) => project.id === item.projectId && projectUnit(project) === unitId,
        )) &&
      (!own || item.assigneeId === user?.id || view === 'milestones') &&
      (view !== 'milestones' || item.isMilestone) &&
      (view !== 'calendar' ||
        (!['completed', 'cancelled'].includes(item.status) &&
          (!period || item.dueDate.startsWith(period)))),
  );
  const managed = Boolean(
    user &&
    can(user, 'activity:create') &&
    projects.data?.some(
      (project) => (!projectId || project.id === projectId) && canManageProject(user, project),
    ),
  );
  return (
    <>
      <PageHeading
        eyebrow="Trabajo de investigación"
        title={title}
        description="Consulta instrucciones, responsables, fechas y estado de las entregas."
        action={
          managed &&
          workspace === 'researcher' && (
            <ActionLink
              to={`/actividades/nueva?${new URLSearchParams({ ...(projectId ? { proyecto: projectId } : {}), ...(view === 'milestones' ? { hito: '1' } : {}) })}`}
              primary
            >
              {view === 'milestones' ? 'Nuevo hito' : 'Nueva actividad'}
            </ActionLink>
          )
        }
      />
      <div className="mb-5 flex flex-wrap gap-2">
        {projectId && <ActionLink to={`/proyectos/${projectId}`}>Volver al proyecto</ActionLink>}
        {view !== 'calendar' && (
          <ActionLink to={`/actividades/calendario${projectId ? `?proyecto=${projectId}` : ''}`}>
            Ver calendario
          </ActionLink>
        )}
        {view !== 'list' && (
          <ActionLink to={`/actividades${projectId ? `?proyecto=${projectId}` : ''}`}>
            Ver actividades
          </ActionLink>
        )}
        {view === 'list' && workspace !== 'student' && (
          <Button
            variant="secondary"
            onClick={() => {
              const next = new URLSearchParams(params);
              if (own) next.delete('vista');
              else next.set('vista', 'mias');
              setParams(next);
            }}
          >
            {own ? 'Ver todas las autorizadas' : 'Solo mis tareas'}
          </Button>
        )}
      </div>
      {view === 'calendar' && (
        <div className="mb-5 max-w-xs">
          <Field label="Mes de entrega">
            <Input
              type="month"
              value={period}
              onChange={(event) => setPeriod(event.target.value)}
            />
          </Field>
        </div>
      )}
      <WorkflowState query={query}>
        <WorkflowList empty="No hay actividades para los filtros seleccionados.">
          {entries.map((item) => (
            <article
              key={item.id}
              className="flex flex-wrap items-center justify-between gap-3 p-5"
            >
              <div>
                <h2 className="font-medium">{item.title}</h2>
                <p className="text-sm text-muted">
                  {item.projectTitle} · {activityStatusLabels[item.status]} · {dueLabel(item)}
                </p>
              </div>
              <ActionLink to={`/actividades/${item.id}`}>Ver actividad</ActionLink>
            </article>
          ))}
        </WorkflowList>
      </WorkflowState>
    </>
  );
}

export function ActivityDetailPage() {
  const { id = '' } = useParams();
  const { user } = useAuth();
  const query = useDemoQuery(['activity', id], () => demoActivities.detail(id));
  const evidenceEnabled = Boolean(user && can(user, 'project:evidence-read') && query.data);
  const evidence = useDemoQuery(
    ['activity-evidences', id],
    () => demoEvidences.list().filter((item) => item.activityId === id),
    evidenceEnabled,
  );
  const mutation = useWorkflowMutation(demoActivities.updateStatus);
  const result = query.data;
  return (
    <WorkflowState query={query}>
      {result && (
        <>
          <PageHeading
            eyebrow={result.activity.isMilestone ? 'Hito' : 'Actividad'}
            title={result.activity.title}
            description={`${result.project.title} · ${dueLabel(result.activity)}`}
            action={
              <ActionLink to={`/actividades?proyecto=${result.project.id}`}>
                Volver a actividades
              </ActionLink>
            }
          />
          <section className="panel space-y-5 p-6">
            <div>
              <h2 className="section-title">Instrucciones</h2>
              <p className="mt-2 whitespace-pre-wrap text-sm text-muted">
                {result.activity.description}
              </p>
            </div>
            <dl className="grid gap-3 text-sm sm:grid-cols-3">
              <div>
                <dt className="text-muted">Responsable</dt>
                <dd>{result.assignee ?? 'Sin información'}</dd>
              </div>
              <div>
                <dt className="text-muted">Asignada por</dt>
                <dd>{result.assigner ?? 'Sin información'}</dd>
              </div>
              <div>
                <dt className="text-muted">Estado</dt>
                <dd>{activityStatusLabels[result.activity.status]}</dd>
              </div>
            </dl>
            {result.activity.milestoneId && (
              <ActionLink to={`/actividades/${result.activity.milestoneId}`}>
                Consultar hito asociado
              </ActionLink>
            )}
            <div className="flex flex-wrap gap-2">
              {result.manager && (
                <ActionLink to={`/actividades/${id}/editar`}>Editar actividad</ActionLink>
              )}
              {user &&
                can(user, 'project:evidence-submit') &&
                result.project.status !== 'completed' &&
                result.activity.status !== 'cancelled' &&
                result.activity.assigneeId === user.id &&
                !result.activity.isMilestone &&
                result.project.members?.some(
                  (item) => item.userId === user.id && item.responsibility === 'student',
                ) && (
                  <ActionLink to={`/evidencias/nueva?actividad=${id}`} primary>
                    Entregar evidencia
                  </ActionLink>
                )}
              {user && can(user, 'document:read') && (
                <ActionLink to={`/documentos?proyecto=${result.project.id}`}>
                  Consultar archivos compartidos
                </ActionLink>
              )}
            </div>
            {result.canUpdate && (
              <form
                className="flex flex-wrap items-end gap-3"
                onSubmit={(event) => {
                  event.preventDefault();
                  const status = new FormData(event.currentTarget).get('status');
                  if (status === 'in_progress' || status === 'blocked' || status === 'completed')
                    mutation.mutate({ id, status });
                }}
              >
                <Field label="Actualizar estado">
                  <select name="status" className="input" defaultValue="in_progress">
                    <option value="in_progress">En curso</option>
                    <option value="blocked">Bloqueada</option>
                    <option value="completed">Completada</option>
                  </select>
                </Field>
                <Button type="submit" disabled={mutation.isPending}>
                  Guardar estado
                </Button>
              </form>
            )}
            <Feedback
              error={mutation.error}
              success={mutation.isSuccess && 'Estado actualizado.'}
            />
          </section>
          {evidenceEnabled && (
            <section className="mt-5">
              <h2 className="section-title mb-3">Evidencias de la actividad</h2>
              <WorkflowState query={evidence}>
                <WorkflowList empty="Aún no hay evidencias para esta actividad.">
                  {(evidence.data ?? []).map((item) => (
                    <article
                      key={item.id}
                      className="flex flex-wrap items-center justify-between gap-3 p-4"
                    >
                      <div>
                        <p className="font-medium">{item.title}</p>
                        <p className="text-xs text-muted">{evidenceStatusLabels[item.status]}</p>
                      </div>
                      <ActionLink to={`/evidencias/${item.id}`}>Consultar evidencia</ActionLink>
                    </article>
                  ))}
                </WorkflowList>
              </WorkflowState>
            </section>
          )}
        </>
      )}
    </WorkflowState>
  );
}

export function ActivityCreatePage() {
  const { user } = useAuth();
  const [params] = useSearchParams();
  const [id, setId] = useState(params.get('proyecto') ?? '');
  const query = useProjects();
  const eligible = query.data?.filter((item) => user && canManageProject(user, item)) ?? [];
  const selected =
    eligible.find((item) => item.id === id) ?? (eligible.length === 1 ? eligible[0] : undefined);
  return (
    <>
      <PageHeading
        eyebrow="Actividades"
        title={params.has('hito') ? 'Nuevo hito' : 'Nueva actividad'}
        description="Define una entrega dentro del plazo del proyecto."
        action={
          <ActionLink to={id ? `/actividades?proyecto=${id}` : '/actividades'}>
            Volver a actividades
          </ActionLink>
        }
      />
      <WorkflowState query={query}>
        {eligible.length ? (
          <>
            <div className="panel mb-4 max-w-3xl p-6">
              <Field label="Proyecto">
                <select
                  className="input"
                  value={selected?.id ?? ''}
                  onChange={(event) => setId(event.target.value)}
                >
                  <option value="">Selecciona un proyecto</option>
                  {eligible.map((item) => (
                    <option value={item.id} key={item.id}>
                      {item.title}
                    </option>
                  ))}
                </select>
              </Field>
            </div>
            {selected && (
              <ActivityForm key={selected.id} project={selected} isMilestone={params.has('hito')} />
            )}
          </>
        ) : (
          <WorkflowList empty="Necesitas un proyecto abierto bajo tu responsabilidad.">
            {[]}
          </WorkflowList>
        )}
      </WorkflowState>
    </>
  );
}

export function ActivityEditPage() {
  const { id = '' } = useParams();
  const query = useDemoQuery(['activity', id], () => demoActivities.detail(id));
  const result = query.data;
  return (
    <WorkflowState query={query}>
      {result && (
        <>
          <PageHeading
            eyebrow="Actividades"
            title="Editar actividad"
            description={result.activity.title}
            action={<ActionLink to={`/actividades/${id}`}>Volver a la actividad</ActionLink>}
          />
          {result.manager ? (
            <ActivityForm key={id} project={result.project} existing={result.activity} />
          ) : (
            <p role="alert" className="field-error">
              Tu perfil no puede editar la configuración de esta actividad.
            </p>
          )}
        </>
      )}
    </WorkflowState>
  );
}

function ActivityForm({
  project,
  existing,
  isMilestone = false,
}: {
  project: Project;
  existing?: Activity;
  isMilestone?: boolean;
}) {
  const [milestone, setMilestone] = useState(existing?.isMilestone ?? isMilestone);
  const [validation, setValidation] = useState('');
  const navigate = useNavigate();
  const query = useDemoQuery(['activity-options', project.id], () =>
    demoActivities.options(project.id),
  );
  const mutation = useWorkflowMutation(demoActivities.save);
  return (
    <WorkflowState query={query}>
      <form
        className="panel max-w-3xl space-y-4 p-6"
        onSubmit={async (event) => {
          event.preventDefault();
          const data = new FormData(event.currentTarget);
          const parsed = activityInputSchema.safeParse({
            ...Object.fromEntries(data),
            projectId: project.id,
            isMilestone: milestone,
            milestoneId: data.get('milestoneId') || undefined,
          });
          if (!parsed.success) {
            setValidation('Completa el nombre, las instrucciones, el responsable y la fecha.');
            return;
          }
          setValidation('');
          const saved = await mutation
            .mutateAsync({ id: existing?.id, values: parsed.data })
            .catch(() => null);
          if (saved) void navigate(`/actividades/${saved.id}`);
        }}
      >
        <Field label="Nombre">
          <Input
            required
            minLength={3}
            maxLength={160}
            name="title"
            defaultValue={existing?.title}
          />
        </Field>
        <Field label="Instrucciones">
          <textarea
            className="input"
            rows={4}
            required
            minLength={5}
            name="description"
            defaultValue={existing?.description}
          />
        </Field>
        <div className="grid gap-4 sm:grid-cols-2">
          <Field label="Responsable">
            <select
              className="input"
              name="assigneeId"
              required
              defaultValue={existing?.assigneeId ?? project.leadId}
            >
              {query.data?.members.map((item) => (
                <option value={item.id} key={item.id}>
                  {item.name}
                </option>
              ))}
            </select>
          </Field>
          <Field label="Fecha de entrega">
            <Input
              type="date"
              name="dueDate"
              min={project.startDate}
              max={project.endDate}
              defaultValue={existing?.dueDate}
              required
            />
          </Field>
          <Field label="Estado">
            <select className="input" name="status" defaultValue={existing?.status ?? 'pending'}>
              {Object.entries(activityStatusLabels).map(([value, label]) => (
                <option key={value} value={value}>
                  {label}
                </option>
              ))}
            </select>
          </Field>
          {!milestone && (
            <Field label="Hito asociado">
              <select
                className="input"
                name="milestoneId"
                defaultValue={existing?.milestoneId ?? ''}
              >
                <option value="">Ninguno</option>
                {query.data?.milestones
                  .filter((item) => item.id !== existing?.id)
                  .map((item) => (
                    <option value={item.id} key={item.id}>
                      {item.title}
                    </option>
                  ))}
              </select>
            </Field>
          )}
        </div>
        <label className="flex gap-2 text-sm">
          <input
            type="checkbox"
            checked={milestone}
            onChange={(event) => setMilestone(event.target.checked)}
          />
          Registrar como hito
        </label>
        {validation && (
          <p role="alert" className="field-error">
            {validation}
          </p>
        )}
        <Feedback error={mutation.error} />
        <Button type="submit" disabled={mutation.isPending}>
          Guardar {milestone ? 'hito' : 'actividad'}
        </Button>
      </form>
    </WorkflowState>
  );
}
