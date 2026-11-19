import { useState } from 'react';
import { useParams, useSearchParams } from 'react-router-dom';
import { useDemoQuery, useWorkflowMutation } from '../../api/demo.queries';
import { useProject, useProjects } from '../../api/projects.queries';
import { Button } from '../../components/button';
import { Input } from '../../components/input';
import { PageHeading, Progress } from '../../components/ui';
import { demoDocuments } from '../../demo/documents';
import { demoProjects } from '../../demo/projects';
import { demoReports } from '../../demo/reports';
import { dateLabel } from '../../lib/format';
import { downloadBlob } from '../../lib/download';
import { statusLabels } from '../../types/project';
import { canManageProject } from '../auth/access-policy';
import { useAuth } from '../auth/auth-context';
import { can } from '../auth/model';
import { ActionLink, Feedback, Field, WorkflowList, WorkflowState } from '../shared/workflow-ui';

function csvCell(value: string | number) {
  const cell = String(value);
  return (
    '"' + (/^[\s\uFEFF]*[=+@-]|^[\t\r\n]/.test(cell) ? "'" : '') + cell.replaceAll('"', '""') + '"'
  );
}

export function ReportsPage({ exporting = false }: { exporting?: boolean }) {
  const { user } = useAuth();
  const [params] = useSearchParams();
  const finalReports = params.get('vista') === 'informes';
  const query = useProjects();
  const [status, setStatus] = useState('');
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const invalidDates = Boolean(from && to && from > to);
  const visible = (query.data ?? []).filter(
    (item) =>
      !invalidDates &&
      (!status || item.status === status) &&
      (!from || item.endDate >= from) &&
      (!to || item.endDate <= to),
  );
  function exportRecords() {
    if (!user || !can(user, 'report:export') || !visible.length) return;
    const csv = [
      ['Código', 'Proyecto', 'Estado', 'Avance (%)', 'Inicio', 'Cierre'],
      ...visible.map((item) => [
        item.code,
        item.title,
        statusLabels[item.status],
        item.progress,
        item.startDate,
        item.endDate,
      ]),
    ]
      .map((row) => row.map(csvCell).join(','))
      .join('\r\n');
    downloadBlob(
      new Blob(['\uFEFF', csv], { type: 'text/csv;charset=utf-8' }),
      'proyectos-autorizados.csv',
    );
  }
  return (
    <>
      <PageHeading
        eyebrow="Seguimiento institucional"
        title={
          exporting
            ? 'Exportación de reportes'
            : finalReports
              ? 'Informes finales'
              : 'Reportes de investigación'
        }
        description="Consulta el estado y avance de tus proyectos. Filtra los registros por fecha prevista de cierre."
        action={
          exporting ? (
            <ActionLink to="/reportes">Volver a reportes</ActionLink>
          ) : (
            <div className="flex flex-wrap gap-2">
              <ActionLink to="/reportes/publicaciones">Publicaciones</ActionLink>
              {user && can(user, 'report:export') && (
                <ActionLink to="/reportes/exportacion">Exportar</ActionLink>
              )}
            </div>
          )
        }
      />
      <section className="panel mb-5 grid gap-3 p-5 sm:grid-cols-3">
        <Field label="Estado">
          <select
            className="input"
            value={status}
            onChange={(event) => setStatus(event.target.value)}
          >
            <option value="">Todos los estados</option>
            {Object.entries(statusLabels).map(([value, label]) => (
              <option value={value} key={value}>
                {label}
              </option>
            ))}
          </select>
        </Field>
        <Field label="Cierre desde">
          <Input type="date" value={from} onChange={(event) => setFrom(event.target.value)} />
        </Field>
        <Field label="Cierre hasta">
          <Input type="date" value={to} onChange={(event) => setTo(event.target.value)} />
        </Field>
        {invalidDates && (
          <p className="field-error sm:col-span-3" role="alert">
            La fecha final debe ser igual o posterior a la inicial.
          </p>
        )}
      </section>
      <WorkflowState query={query}>
        <div className="mb-4 flex flex-wrap items-center justify-between gap-3">
          <p className="text-sm" aria-live="polite">
            {visible.length} proyectos ·{' '}
            {visible.length
              ? Math.round(
                  visible.reduce((total, item) => total + item.progress, 0) / visible.length,
                )
              : 0}
            % de avance promedio
          </p>
          {exporting && (
            <Button disabled={!visible.length} onClick={exportRecords}>
              Descargar CSV
            </Button>
          )}
        </div>
        <WorkflowList empty="No se encontraron proyectos para estos filtros.">
          {visible.map((item) => (
            <article key={item.id} className="space-y-3 p-5">
              <h2 className="font-medium">{item.title}</h2>
              <p className="text-sm text-muted">
                {statusLabels[item.status]} · cierre {dateLabel(item.endDate)}
              </p>
              <div className="max-w-sm">
                <Progress value={item.progress} />
              </div>
              <div className="flex flex-wrap gap-2">
                <ActionLink to={`/proyectos/${item.id}`}>Consultar proyecto</ActionLink>
                <ActionLink to={`/reportes/proyectos/${item.id}/informe-final`}>
                  Informe final
                </ActionLink>
                {user && can(user, 'project:close') && item.supervisorIds?.includes(user.id) && (
                  <ActionLink to={`/reportes/proyectos/${item.id}/cierre`}>
                    {item.closedAt ? 'Consultar cierre' : 'Revisar cierre'}
                  </ActionLink>
                )}
              </div>
            </article>
          ))}
        </WorkflowList>
      </WorkflowState>
    </>
  );
}

export function FinalReportPage() {
  const { id = '' } = useParams();
  const { user } = useAuth();
  const project = useProject(id);
  const submission = useWorkflowMutation(demoReports.submitFinalReport);
  const documents = useDemoQuery(
    ['final-report', id],
    () =>
      demoDocuments.list().filter((item) => item.projectId === id && item.category === 'report'),
    Boolean(project.data),
  );
  const report = project.data?.finalReport;
  const editable = Boolean(user && project.data && canManageProject(user, project.data));
  const current = documents.data?.find((item) => item.id === report?.documentId);
  return (
    <WorkflowState query={project}>
      {project.data && (
        <>
          <PageHeading
            eyebrow={project.data.code}
            title="Informe final"
            description={project.data.title}
            action={<ActionLink to={`/proyectos/${id}`}>Volver al proyecto</ActionLink>}
          />
          <section className="panel mb-5 space-y-3 p-5">
            <h2 className="section-title">Requisitos de la entrega</h2>
            <p className="text-sm text-muted">
              Adjunta un PDF de hasta 10 MB con objetivos, metodología, resultados y conclusiones
              del proyecto.
            </p>
            <p className="text-sm" role="status">
              {report?.submittedAt
                ? 'Informe presentado'
                : report
                  ? 'Archivo en borrador · pendiente de presentar'
                  : 'Entrega pendiente'}
            </p>
            {user && editable && !report?.submittedAt && (
              <div className="flex flex-wrap gap-2">
                {can(user, 'document:upload') && (
                  <ActionLink
                    to={
                      current
                        ? `/documentos/subir?version=${current.id}`
                        : `/documentos/subir?proyecto=${id}&categoria=report`
                    }
                  >
                    {current ? 'Reemplazar borrador' : 'Cargar informe final'}
                  </ActionLink>
                )}
                {current && (
                  <Button
                    disabled={submission.isPending}
                    onClick={() => {
                      if (
                        window.confirm('¿Presentar el informe final? El archivo quedará bloqueado.')
                      )
                        submission.mutate({ id, documentId: current.id });
                    }}
                  >
                    Presentar informe final
                  </Button>
                )}
              </div>
            )}
            <Feedback
              error={submission.error}
              success={submission.isSuccess && 'Informe final presentado.'}
            />
          </section>
          <WorkflowState query={documents}>
            <WorkflowList empty="Todavía no se ha entregado el informe final.">
              {(documents.data ?? []).map((item) => (
                <article key={item.id} className="p-5">
                  <ActionLink to={`/documentos/${item.id}`}>{item.name}</ActionLink>
                </article>
              ))}
            </WorkflowList>
          </WorkflowState>
        </>
      )}
    </WorkflowState>
  );
}

export function ProjectClosurePage() {
  const { id = '' } = useParams();
  const query = useDemoQuery(['closure', id], () => demoProjects.closure(id));
  const mutation = useWorkflowMutation(demoProjects.close);
  const [confirmed, setConfirmed] = useState(false);
  const result = query.data;
  return (
    <WorkflowState query={query}>
      {result && (
        <>
          <PageHeading
            eyebrow={result.project.code}
            title="Cierre de proyecto"
            description={result.project.title}
            action={<ActionLink to={`/proyectos/${id}`}>Volver al proyecto</ActionLink>}
          />
          <section className="panel max-w-3xl space-y-5 p-6">
            <h2 className="section-title">Verificación de requisitos</h2>
            <ul className="space-y-2 text-sm">
              <li>Informe final · {result.report ? 'Entregado' : 'Pendiente'}</li>
              <li>
                Actividades e hitos · {result.activities ? 'Resueltos' : 'Con trabajo pendiente'}
              </li>
            </ul>
            <div className="flex flex-wrap gap-2">
              <ActionLink to={`/reportes/proyectos/${id}/informe-final`}>
                Consultar informe
              </ActionLink>
              <ActionLink to={`/proyectos/${id}/seguimiento`}>Consultar seguimiento</ActionLink>
            </div>
            {result.project.status === 'completed' ? (
              <Feedback
                success={
                  result.project.closedAt
                    ? `Cierre registrado el ${new Date(result.project.closedAt).toLocaleString('es-MX')}.`
                    : 'Proyecto finalizado.'
                }
              />
            ) : (
              <form
                className="space-y-3"
                onSubmit={(event) => {
                  event.preventDefault();
                  mutation.mutate({ id, confirmed });
                }}
              >
                <label className="flex gap-2 text-sm">
                  <input
                    type="checkbox"
                    checked={confirmed}
                    onChange={(event) => setConfirmed(event.target.checked)}
                  />
                  Confirmo que revisé los requisitos y autorizo el cierre del proyecto.
                </label>
                <Button
                  type="submit"
                  disabled={
                    !confirmed || !result.report || !result.activities || mutation.isPending
                  }
                >
                  Cerrar proyecto
                </Button>
              </form>
            )}
            <Feedback error={mutation.error} />
          </section>
        </>
      )}
    </WorkflowState>
  );
}

export function PublicationsPage() {
  const { user } = useAuth();
  const query = useDemoQuery(['publications'], demoReports.publications);
  const projects = useProjects();
  const eligible = projects.data?.filter((item) => user && canManageProject(user, item)) ?? [];
  const [editing, setEditing] = useState<string>();
  const edited = query.data?.find((item) => item.id === editing && item.editable);
  const mutation = useWorkflowMutation(demoReports.savePublication);
  return (
    <>
      <PageHeading
        eyebrow="Resultados de investigación"
        title="Publicaciones"
        description="Consulta y registra publicaciones asociadas a proyectos autorizados."
        action={<ActionLink to="/reportes">Volver a reportes</ActionLink>}
      />
      <WorkflowState query={query}>
        <WorkflowList empty="Todavía no hay publicaciones registradas.">
          {(query.data ?? []).map((item) => (
            <article className="flex flex-wrap justify-between gap-3 p-5" key={item.id}>
              <div>
                <a
                  className="font-medium text-accent underline"
                  href={item.url}
                  target="_blank"
                  rel="noopener noreferrer"
                >
                  {item.title}
                </a>
                <p className="text-sm text-muted">
                  {item.projectTitle} · {dateLabel(item.date)}
                </p>
              </div>
              {item.editable && (
                <Button
                  variant="secondary"
                  onClick={() => {
                    mutation.reset();
                    setEditing(item.id);
                  }}
                >
                  Editar publicación
                </Button>
              )}
            </article>
          ))}
        </WorkflowList>
      </WorkflowState>
      {eligible.length > 0 && (
        <form
          key={edited?.id ?? 'new'}
          className="panel mt-5 max-w-3xl space-y-4 p-6"
          onSubmit={(event) => {
            event.preventDefault();
            const form = event.currentTarget;
            const values = new FormData(form);
            mutation.mutate(
              {
                id: edited?.id,
                projectId: edited?.projectId ?? String(values.get('project') ?? ''),
                title: String(values.get('title') ?? ''),
                url: String(values.get('url') ?? ''),
                date: String(values.get('date') ?? ''),
              },
              {
                onSuccess: () => {
                  form.reset();
                  setEditing(undefined);
                },
              },
            );
          }}
        >
          <h2 className="section-title">
            {edited ? 'Editar publicación' : 'Registrar publicación'}
          </h2>
          {edited ? (
            <p className="text-sm">{edited.projectTitle}</p>
          ) : (
            <Field label="Proyecto">
              <select className="input" name="project" required defaultValue="">
                <option value="">Selecciona un proyecto</option>
                {eligible.map((item) => (
                  <option key={item.id} value={item.id}>
                    {item.title}
                  </option>
                ))}
              </select>
            </Field>
          )}
          <Field label="Título">
            <Input
              name="title"
              required
              minLength={3}
              maxLength={180}
              defaultValue={edited?.title}
            />
          </Field>
          <Field label="Enlace a la publicación">
            <Input name="url" type="url" required defaultValue={edited?.url} />
          </Field>
          <Field label="Fecha de publicación">
            <Input name="date" type="date" required defaultValue={edited?.date} />
          </Field>
          <div className="flex gap-2">
            <Button type="submit" disabled={mutation.isPending}>
              Guardar publicación
            </Button>
            {edited && (
              <Button
                variant="secondary"
                onClick={() => {
                  mutation.reset();
                  setEditing(undefined);
                }}
              >
                Cancelar edición
              </Button>
            )}
          </div>
          <Feedback
            error={mutation.error}
            success={mutation.isSuccess && 'Publicación guardada.'}
          />
        </form>
      )}
    </>
  );
}
