import { CalendarDays, Landmark, UserRound } from 'lucide-react';
import { useParams, useLocation } from 'react-router-dom';
import { AsyncState, BackLink, PageHeading, Progress, StatusBadge } from '../../components/ui';
import { currency, dateLabel } from '../../lib/format';
import { ProjectTabs } from './project-workspace';
import { ActionLink, Feedback } from '../shared/workflow-ui';
import { useAuth } from '../auth/auth-context';
import { can } from '../auth/model';
import { useProject } from '../../api/projects.queries';

export function ProjectDetailPage() {
  const { id = '' } = useParams();
  const query = useProject(id);
  const location = useLocation();
  const { user } = useAuth();
  if (query.isPending) return <AsyncState loading />;
  if (query.error)
    return (
      <>
        <BackLink />
        <AsyncState error={query.error} retry={() => void query.refetch()} />
      </>
    );
  const project = query.data;
  return (
    <>
      <BackLink />
      <PageHeading
        eyebrow={project.code}
        title={project.title}
        description={project.area}
        action={<StatusBadge status={project.status} />}
      />
      {location.state?.converted && (
        <div className="mb-5">
          <Feedback success="Proyecto creado a partir de la propuesta aprobada." />
        </div>
      )}
      <ProjectTabs project={project} />
      <div className="grid gap-6 lg:grid-cols-[1.6fr_1fr]">
        <section className="panel p-7">
          <h2 className="section-title">Acerca del proyecto</h2>
          <p className="mt-5 whitespace-pre-wrap text-sm leading-7 text-muted">
            {project.description}
          </p>
          <div className="mt-9 border-t border-line pt-6">
            <div className="mb-4 flex justify-between">
              <h2 className="section-title">Avance general</h2>
              <span className="text-xs text-muted">Seguimiento institucional</span>
            </div>
            <Progress value={project.progress} />
            <p className="mt-4 text-xs leading-6 text-muted">
              El avance resume el progreso registrado del proyecto durante su periodo de ejecución.
            </p>
          </div>
        </section>
        <section className="panel p-7">
          <h2 className="section-title">Ficha institucional</h2>
          {project.proposalId &&
            user &&
            can(user, 'proposal:read') &&
            (user.id === project.leadId ||
              (can(user, 'proposal:review') && user.departmentId === project.departmentId)) && (
              <div className="mt-4">
                <ActionLink to={`/propuestas/${project.proposalId}`}>
                  Propuesta de origen
                </ActionLink>
              </div>
            )}
          <dl className="mt-6 space-y-6">
            <div className="flex gap-3">
              <UserRound className="mt-1 text-accent" size={18} aria-hidden="true" />
              <div>
                <dt className="text-xs text-muted">Responsable</dt>
                <dd className="mt-1 text-sm font-medium">{project.lead}</dd>
              </div>
            </div>
            <div className="flex gap-3">
              <Landmark className="mt-1 text-accent" size={18} aria-hidden="true" />
              <div>
                <dt className="text-xs text-muted">Presupuesto asignado · MXN</dt>
                <dd className="mt-1 text-xl font-semibold">{currency(project.budget)}</dd>
              </div>
            </div>
            <div className="flex gap-3">
              <CalendarDays className="mt-1 text-accent" size={18} aria-hidden="true" />
              <div>
                <dt className="text-xs text-muted">Periodo de ejecución</dt>
                <dd className="mt-1 text-sm">
                  {dateLabel(project.startDate)} — {dateLabel(project.endDate)}
                </dd>
              </div>
            </div>
          </dl>
        </section>
      </div>
    </>
  );
}
