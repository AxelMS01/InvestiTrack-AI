import { useQuery } from '@tanstack/react-query';
import { Link, useParams } from 'react-router-dom';
import { workflowApi } from '../../api/institutional-workflow.api';
import { AsyncState, EmptyState } from '../../components/ui';

const projectStatus: Record<string, string> = {
  APPROVED: 'Aprobado',
  ACTIVE: 'En ejecución',
  DELAYED: 'Atrasado',
  COMPLETED: 'Finalizado',
  CLOSED: 'Cerrado',
  CANCELLED: 'Cancelado',
};

export function InstitutionalProjectsPage() {
  const query = useQuery({
    queryKey: ['institutional', 'projects'],
    queryFn: ({ signal }) => workflowApi.projects(signal),
    refetchInterval: 5000,
  });
  return (
    <section className="space-y-5">
      <h1 className="text-2xl font-semibold">Proyectos</h1>
      {(query.isPending || query.error) && (
        <AsyncState
          loading={query.isPending}
          error={query.error ?? undefined}
          retry={() => void query.refetch()}
        />
      )}
      {query.data?.length === 0 && (
        <EmptyState
          title="Sin proyectos disponibles"
          description="Los proyectos aparecen después de que se procesa la aprobación de una propuesta."
        />
      )}
      <ul className="space-y-3">
        {query.data?.map((project) => (
          <li key={project.id} className="rounded-xl border border-line p-4">
            <Link className="font-semibold underline" to={'/proyectos/' + project.id}>
              {project.title}
            </Link>
            <p className="text-sm text-muted">{projectStatus[project.status] ?? project.status}</p>
          </li>
        ))}
      </ul>
    </section>
  );
}

export function InstitutionalProjectDetailPage() {
  const id = useParams().id ?? '';
  const query = useQuery({
    queryKey: ['institutional', 'project', id],
    queryFn: ({ signal }) => workflowApi.project(id, signal),
  });
  const project = query.data;
  if (!project)
    return (
      <AsyncState
        loading={query.isPending}
        error={query.error ?? undefined}
        retry={() => void query.refetch()}
      />
    );
  return (
    <section className="space-y-5">
      <Link className="underline" to="/proyectos">
        Proyectos
      </Link>
      <h1 className="text-2xl font-semibold">{project.title}</h1>
      <p>{project.abstractText}</p>
      <dl className="grid gap-4 rounded-xl border border-line p-4 sm:grid-cols-2">
        <div>
          <dt className="field-label">Estado</dt>
          <dd>{projectStatus[project.status] ?? project.status}</dd>
        </div>
        <div>
          <dt className="field-label">Folio</dt>
          <dd className="break-all">{project.projectNumber}</dd>
        </div>
        <div>
          <dt className="field-label">Fin previsto</dt>
          <dd>{project.plannedEndDate}</dd>
        </div>
        <div>
          <dt className="field-label">Avance</dt>
          <dd>{project.progressPercent}%</dd>
        </div>
      </dl>
    </section>
  );
}
