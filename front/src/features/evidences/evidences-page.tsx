import { useState } from 'react';
import { useParams, useSearchParams } from 'react-router-dom';
import { useDemoQuery } from '../../api/demo.queries';
import { PageHeading } from '../../components/ui';
import { Input } from '../../components/input';
import { demoEvidences } from '../../demo/evidences';
import { evidenceStatusLabels } from '../../types/evidence';
import { useAuth } from '../auth/auth-context';
import { can } from '../auth/model';
import { ActionLink, Field, WorkflowList, WorkflowState } from '../shared/workflow-ui';

export function EvidencesPage() {
  const [params] = useSearchParams();
  const { user, workspace } = useAuth();
  const view = params.get('vista');
  const project = params.get('proyecto');
  const activity = params.get('actividad');
  const [search, setSearch] = useState('');
  const [projectFilter, setProject] = useState('');
  const [activityFilter, setActivity] = useState('');
  const [status, setStatus] = useState('');
  const query = useDemoQuery(['academic-evidence', project], () =>
    project ? demoEvidences.forProject(project) : demoEvidences.list(),
  );
  const rows = (query.data ?? []).filter(
    (item) =>
      (workspace !== 'student' || item.ownerUserId === user?.id) &&
      (workspace !== 'reviewer' || item.assigned) &&
      (!activity || activity === item.activityId) &&
      (view !== 'pendientes' || item.pendingGrade) &&
      (view !== 'correcciones' || ['CHANGES_REQUESTED', 'REDO_REQUESTED'].includes(item.status)) &&
      (view !== 'calificaciones' || item.gradeCount > 0),
  );
  const projectChoices = new Map(rows.map((item) => [item.projectId, item.projectTitle]));
  const activityChoices = new Map(
    rows
      .filter((item) => !projectFilter || item.projectId === projectFilter)
      .map((item) => [item.activityId, item.activityTitle]),
  );
  const visible = rows.filter(
    (item) =>
      (!projectFilter || item.projectId === projectFilter) &&
      (!activityFilter || item.activityId === activityFilter) &&
      (!status || item.status === status) &&
      `${item.title} ${item.evidenceNumber}`.toLowerCase().includes(search.trim().toLowerCase()),
  );
  const title =
    view === 'calificaciones'
      ? workspace === 'student'
        ? 'Mis calificaciones'
        : 'Historial de calificaciones'
      : view === 'pendientes'
        ? 'Evidencias pendientes'
        : view === 'correcciones'
          ? 'Correcciones solicitadas'
          : workspace === 'student'
            ? 'Mis entregas'
            : 'Evidencias';
  const target = activity ? `?actividad=${activity}` : project ? `?proyecto=${project}` : '';
  return (
    <>
      <PageHeading
        eyebrow="Investigación · Entregas"
        title={title}
        description="Consulta las entregas y su historial por proyecto y actividad."
        action={
          <div className="flex flex-wrap gap-2">
            {project && <ActionLink to={`/proyectos/${project}`}>Volver al proyecto</ActionLink>}
            {user && workspace === 'student' && can(user, 'project:evidence-submit') && (
              <ActionLink primary to={`/evidencias/nueva${target}`}>
                Crear evidencia
              </ActionLink>
            )}
          </div>
        }
      />
      <section className="panel mb-5 grid gap-3 p-5 sm:grid-cols-2 xl:grid-cols-4">
        <Field label="Buscar por nombre o código">
          <Input value={search} onChange={(event) => setSearch(event.target.value)} />
        </Field>
        <Field label="Proyecto">
          <select
            className="input"
            value={projectFilter}
            onChange={(event) => {
              setProject(event.target.value);
              setActivity('');
            }}
          >
            <option value="">Todos</option>
            {[...projectChoices].map(([id, label]) => (
              <option key={id} value={id}>
                {label}
              </option>
            ))}
          </select>
        </Field>
        <Field label="Actividad">
          <select
            className="input"
            value={activityFilter}
            onChange={(event) => setActivity(event.target.value)}
          >
            <option value="">Todas</option>
            {[...activityChoices].map(([id, label]) => (
              <option key={id} value={id}>
                {label}
              </option>
            ))}
          </select>
        </Field>
        <Field label="Estado">
          <select
            className="input"
            value={status}
            onChange={(event) => setStatus(event.target.value)}
          >
            <option value="">Todos</option>
            {Object.entries(evidenceStatusLabels).map(([id, label]) => (
              <option key={id} value={id}>
                {label}
              </option>
            ))}
          </select>
        </Field>
      </section>
      <WorkflowState query={query}>
        {visible.length ? (
          <div className="panel overflow-x-auto">
            <table className="min-w-[820px] w-full text-left text-sm">
              <caption className="sr-only">{title}</caption>
              <thead className="border-b border-line bg-canvas text-xs text-muted">
                <tr>
                  {[
                    'Código',
                    'Nombre',
                    'Proyecto y actividad',
                    'Fecha',
                    'Estado',
                    'Versión',
                    'Acción',
                  ].map((label) => (
                    <th scope="col" className="p-4" key={label}>
                      {label}
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody className="divide-y divide-line">
                {visible.map((item) => (
                  <tr key={item.id}>
                    <td className="p-4 whitespace-nowrap">{item.evidenceNumber}</td>
                    <td className="p-4 font-medium">{item.title}</td>
                    <td className="p-4">
                      {item.projectTitle}
                      <br />
                      <span className="text-xs text-muted">{item.activityTitle}</span>
                    </td>
                    <td className="p-4 whitespace-nowrap">
                      {new Date(item.updatedAt).toLocaleDateString('es-MX')}
                    </td>
                    <td className="p-4">{evidenceStatusLabels[item.status]}</td>
                    <td className="p-4">{item.currentVersionNumber || 'Sin archivo'}</td>
                    <td className="p-4">
                      <ActionLink
                        to={
                          view === 'pendientes'
                            ? `/evidencias/${item.id}/evaluar`
                            : `/evidencias/${item.id}${view === 'calificaciones' ? '?tab=evaluacion' : view === 'correcciones' ? '?tab=retroalimentacion' : ''}`
                        }
                      >
                        {view === 'pendientes' ? 'Evaluar evidencia' : 'Ver detalle'}
                      </ActionLink>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        ) : (
          <WorkflowList
            empty={
              view === 'calificaciones' && workspace === 'student'
                ? 'Aún no tienes calificaciones publicadas.'
                : 'No hay evidencias para los filtros seleccionados.'
            }
          >
            {[]}
          </WorkflowList>
        )}
      </WorkflowState>
    </>
  );
}

export function ProjectEvidencesPage() {
  const { id = '' } = useParams();
  const query = useDemoQuery(['academic-evidence', id], () => demoEvidences.forProject(id));
  return (
    <>
      <PageHeading
        eyebrow="Proyecto de investigación"
        title="Evidencias del proyecto"
        description="Consulta entregas, correcciones y calificaciones dentro de tu ámbito."
        action={<ActionLink to={`/proyectos/${id}`}>Volver al proyecto</ActionLink>}
      />
      <WorkflowState query={query}>
        <WorkflowList empty="Todavía no hay entregas visibles en este proyecto.">
          {(query.data ?? []).map((item) => (
            <article
              className="flex flex-wrap items-center justify-between gap-3 p-5"
              key={item.id}
            >
              <div>
                <h2 className="font-medium">{item.title}</h2>
                <p className="text-sm text-muted">
                  {item.evidenceNumber} · {evidenceStatusLabels[item.status]}
                </p>
              </div>
              <ActionLink to={`/evidencias/${item.id}`}>Ver evidencia</ActionLink>
            </article>
          ))}
        </WorkflowList>
      </WorkflowState>
    </>
  );
}
