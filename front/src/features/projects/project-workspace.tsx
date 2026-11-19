import { z } from 'zod';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { useParams } from 'react-router-dom';
import { useDemoQuery, useWorkflowMutation } from '../../api/demo.queries';
import { useProject } from '../../api/projects.queries';
import { Button } from '../../components/button';
import { Input } from '../../components/input';
import { AsyncState, PageHeading, Progress } from '../../components/ui';
import { demoProjects } from '../../demo/projects';
import { demoActivities, activityStatusLabels } from '../../demo/activities';
import { dateLabel } from '../../lib/format';
import {
  areas,
  projectInputSchema,
  statusLabels,
  statusSchema,
  type Project,
} from '../../types/project';
import { canCloseProject, canManageProject } from '../auth/access-policy';
import { can } from '../auth/model';
import { useAuth } from '../auth/auth-context';
import { ActionLink, Feedback, Field, WorkflowList, WorkflowState } from '../shared/workflow-ui';
import { config } from '../../lib/config';

export function ProjectTabs({ project }: { project: Project }) {
  const { user } = useAuth();
  if (!config.demo || !user) return null;
  const path = `/proyectos/${project.id}`;
  return (
    <nav className="mb-6 flex flex-wrap gap-2" aria-label="Secciones del proyecto">
      <ActionLink to={path}>Resumen</ActionLink>
      {can(user, 'activity:read') && (
        <>
          <ActionLink to={`/actividades?proyecto=${project.id}`}>Actividades</ActionLink>
          <ActionLink to={`/actividades/hitos?proyecto=${project.id}`}>Hitos</ActionLink>
          <ActionLink to={`${path}/seguimiento`}>Seguimiento</ActionLink>
        </>
      )}
      <ActionLink to={`${path}/colaboradores`}>Colaboradores</ActionLink>
      {can(user, 'document:read') && (
        <ActionLink to={`/documentos?proyecto=${project.id}`}>Documentos</ActionLink>
      )}
      {can(user, 'report:read') && (
        <ActionLink to={`/reportes/proyectos/${project.id}/informe-final`}>
          Informe final
        </ActionLink>
      )}
      {canCloseProject(user, project) && (
        <ActionLink to={`/reportes/proyectos/${project.id}/cierre`}>Cierre</ActionLink>
      )}
      {canManageProject(user, project) && (
        <ActionLink to={`${path}/configuracion`}>Configuración</ActionLink>
      )}
    </nav>
  );
}

export function ProjectSettingsPage() {
  const { id = '' } = useParams();
  const { user } = useAuth();
  const query = useProject(id);
  return (
    <WorkflowState query={query}>
      {query.data &&
        (user && canManageProject(user, query.data) ? (
          <SettingsForm key={id} project={query.data} />
        ) : (
          <>
            <ActionLink to={`/proyectos/${id}`}>Volver al proyecto</ActionLink>
            <AsyncState
              error={
                new Error(
                  'La configuración está reservada a la persona responsable del proyecto abierto.',
                )
              }
            />
          </>
        ))}
    </WorkflowState>
  );
}

function SettingsForm({ project }: { project: Project }) {
  const mutation = useWorkflowMutation(demoProjects.update);
  const {
    register,
    handleSubmit,
    formState: { errors },
  } = useForm({
    resolver: zodResolver(projectInputSchema.and(z.object({ status: statusSchema }))),
    defaultValues: project,
  });
  return (
    <>
      <PageHeading
        eyebrow={project.code}
        title="Configuración del proyecto"
        description={project.title}
      />
      <ProjectTabs project={project} />
      <form
        className="panel max-w-3xl space-y-4 p-6"
        onSubmit={handleSubmit((values) =>
          mutation.mutate({ id: project.id, settings: values, status: values.status }),
        )}
      >
        <Field label="Título del proyecto">
          <Input required {...register('title')} />
        </Field>
        <Field label="Descripción">
          <textarea className="input" rows={4} required {...register('description')} />
        </Field>
        <div className="grid gap-4 sm:grid-cols-2">
          <Field label="Área">
            <select className="input" {...register('area')}>
              {areas.map((area) => (
                <option key={area}>{area}</option>
              ))}
            </select>
          </Field>
          <Field label="Presupuesto (MXN)">
            <Input
              type="number"
              min="0"
              step="0.01"
              {...register('budget', { valueAsNumber: true })}
            />
          </Field>
          <Field label="Inicio">
            <Input type="date" required {...register('startDate')} />
          </Field>
          <Field label="Cierre previsto">
            <Input type="date" required {...register('endDate')} />
          </Field>
        </div>
        <Field label="Estado">
          <select className="input" {...register('status')}>
            {statusSchema.options
              .filter((status) => status !== 'completed')
              .map((status) => (
                <option key={status} value={status}>
                  {statusLabels[status]}
                </option>
              ))}
          </select>
        </Field>
        {Object.values(errors).map((error, index) => (
          <p key={index} className="field-error" role="alert">
            {error.message}
          </p>
        ))}
        <Feedback
          error={mutation.error}
          success={mutation.isSuccess && 'Configuración guardada.'}
        />
        <Button type="submit" disabled={mutation.isPending}>
          Guardar configuración
        </Button>
      </form>
    </>
  );
}

export function ProjectCollaboratorsPage() {
  const { id = '' } = useParams();
  const { user } = useAuth();
  const query = useProject(id);
  const project = query.data;
  const manage = Boolean(
    user && project && canManageProject(user, project) && can(user, 'project:manage-members'),
  );
  const directory = useDemoQuery(
    ['project-directory', id],
    () => demoProjects.directory(id),
    manage,
  );
  const mutation = useWorkflowMutation(demoProjects.member);
  return (
    <WorkflowState query={query}>
      {project && (
        <>
          <PageHeading eyebrow={project.code} title="Colaboradores" description={project.title} />
          <ProjectTabs project={project} />
          <WorkflowList>
            {[
              <article key="lead" className="p-5">
                <h2 className="font-medium">{project.lead}</h2>
                <p className="text-sm text-muted">Investigación principal</p>
              </article>,
              ...(project.members ?? []).map((member) => (
                <article
                  className="flex flex-wrap items-center justify-between gap-2 p-5"
                  key={member.userId}
                >
                  <div>
                    <h2 className="font-medium">
                      {member.name ??
                        directory.data?.find((item) => item.id === member.userId)?.name ??
                        'Integrante del equipo'}
                    </h2>
                    <p className="text-sm text-muted">
                      {member.responsibility === 'student' ? 'Estudiante' : 'Colaboración'}
                    </p>
                  </div>
                  {manage && (
                    <Button
                      variant="secondary"
                      disabled={mutation.isPending}
                      onClick={() => {
                        if (window.confirm('¿Retirar a esta persona del proyecto?'))
                          mutation.mutate({ id, userId: member.userId, responsibility: 'remove' });
                      }}
                    >
                      Retirar acceso
                    </Button>
                  )}
                </article>
              )),
            ]}
          </WorkflowList>
          {manage && (
            <WorkflowState query={directory}>
              <form
                className="panel mt-5 max-w-3xl space-y-4 p-6"
                onSubmit={(event) => {
                  event.preventDefault();
                  const values = new FormData(event.currentTarget);
                  mutation.mutate({
                    id,
                    userId: String(values.get('user') ?? ''),
                    responsibility: values.get('role') === 'student' ? 'student' : 'collaborator',
                  });
                }}
              >
                <Field label="Persona">
                  <select name="user" className="input" required defaultValue="">
                    <option value="">Selecciona una persona</option>
                    {directory.data?.map((item) => (
                      <option value={item.id} key={item.id}>
                        {item.name} · {item.email}
                      </option>
                    ))}
                  </select>
                </Field>
                <Field label="Responsabilidad">
                  <select name="role" className="input">
                    <option value="collaborator">Colaboración</option>
                    <option value="student">Estudiante</option>
                  </select>
                </Field>
                <Button type="submit" disabled={mutation.isPending}>
                  Guardar integrante
                </Button>
                <Feedback
                  error={mutation.error}
                  success={mutation.isSuccess && 'Equipo actualizado.'}
                />
              </form>
            </WorkflowState>
          )}
        </>
      )}
    </WorkflowState>
  );
}

export function ProjectTrackingPage() {
  const { id = '' } = useParams();
  const project = useProject(id);
  const activities = useDemoQuery(['activities'], demoActivities.list);
  return (
    <WorkflowState query={project}>
      {project.data && (
        <>
          <PageHeading
            eyebrow={project.data.code}
            title="Seguimiento del proyecto"
            description={project.data.title}
          />
          <ProjectTabs project={project.data} />
          <section className="panel mb-5 space-y-4 p-6">
            <h2 className="section-title">Avance general</h2>
            <Progress value={project.data.progress} />
          </section>
          <WorkflowState query={activities}>
            <WorkflowList empty="Aún no hay actividades visibles en este proyecto.">
              {(activities.data ?? [])
                .filter((item) => item.projectId === id)
                .map((item) => (
                  <article
                    className="flex flex-wrap items-center justify-between gap-3 p-5"
                    key={item.id}
                  >
                    <div>
                      <h2 className="font-medium">{item.title}</h2>
                      <p className="text-sm text-muted">
                        {activityStatusLabels[item.status]} · {dateLabel(item.dueDate)}
                      </p>
                    </div>
                    <ActionLink to={`/actividades/${item.id}`}>Consultar</ActionLink>
                  </article>
                ))}
            </WorkflowList>
          </WorkflowState>
        </>
      )}
    </WorkflowState>
  );
}
