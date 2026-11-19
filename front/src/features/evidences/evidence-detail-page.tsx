import { useState } from 'react';
import { useLocation, useParams, useSearchParams } from 'react-router-dom';
import { useDemoQuery, useWorkflowMutation } from '../../api/demo.queries';
import { Button } from '../../components/button';
import { Input } from '../../components/input';
import { PageHeading } from '../../components/ui';
import { fileAccept } from '../../demo/documents';
import { demoEvidences } from '../../demo/evidences';
import { evidenceStatusLabels } from '../../types/evidence';
import { useAuth } from '../auth/auth-context';
import { ActionLink, Feedback, Field, WorkflowList, WorkflowState } from '../shared/workflow-ui';
import { EvidenceFile } from './evidence-file';

export type EvidenceDetail = ReturnType<typeof demoEvidences.detail>;
const reviewLabels = {
  COMMENT: 'Observaciones',
  CHANGES_REQUESTED: 'Correcciones solicitadas',
  REDO_REQUESTED: 'Nueva entrega solicitada',
};
const tabs = {
  documento: 'Documento',
  historial: 'Historial de versiones',
  retroalimentacion: 'Retroalimentación',
  evaluacion: 'Evaluación',
};
function localDate(value: string) {
  return new Date(value).toLocaleString('es-MX');
}

export function EvidenceDetailPage() {
  const { id = '' } = useParams();
  const location = useLocation();
  const { user } = useAuth();
  const query = useDemoQuery(['evidence', id], () => demoEvidences.detail(id));
  return (
    <WorkflowState query={query}>
      {query.data && (
        <Detail
          key={id}
          result={query.data}
          saved={Boolean(location.state?.saved)}
          graded={Boolean(user && location.state?.gradedBy === user.id)}
        />
      )}
    </WorkflowState>
  );
}

function Detail({
  result,
  saved,
  graded,
}: {
  result: EvidenceDetail;
  saved: boolean;
  graded: boolean;
}) {
  const [params, setParams] = useSearchParams();
  const { evidence, versions, reviews, grades, people } = result;
  const requested = params.get('tab');
  const tab = requested && Object.hasOwn(tabs, requested) ? requested : 'documento';
  const selected = versions.find((item) => item.id === params.get('version')) ?? versions[0];
  const submit = useWorkflowMutation(demoEvidences.submit);
  return (
    <>
      <PageHeading
        eyebrow={`${evidence.evidenceNumber} · ${evidenceStatusLabels[evidence.status]}`}
        title={evidence.title}
        description={`${result.projectTitle} · ${result.activityTitle}`}
        action={<ActionLink to="/evidencias">Volver a evidencias</ActionLink>}
      />
      <section className="panel mb-5 space-y-3 p-5">
        <p className="text-sm font-medium">
          {people[evidence.ownerUserId] ?? 'Estudiante'} ·{' '}
          {evidence.currentVersionNumber
            ? `Versión ${evidence.currentVersionNumber}`
            : 'Sin archivos'}
        </p>
        {evidence.description && (
          <p className="whitespace-pre-wrap break-words text-sm text-muted">
            {evidence.description}
          </p>
        )}
        <p className="text-xs text-muted">Último cambio · {localDate(evidence.updatedAt)}</p>
        <div className="flex flex-wrap gap-2">
          {result.canSubmit && (
            <Button
              disabled={submit.isPending}
              onClick={() => {
                if (window.confirm('¿Entregar esta versión para su revisión?'))
                  submit.mutate({ id: evidence.id, version: evidence.version });
              }}
            >
              Confirmar entrega
            </Button>
          )}
          {result.gradeContext && (
            <ActionLink to={`/evidencias/${evidence.id}/evaluar`} primary>
              Evaluar evidencia
            </ActionLink>
          )}
        </div>
        <Feedback
          error={submit.error}
          success={
            submit.isSuccess
              ? 'Versión entregada para revisión.'
              : graded
                ? 'Calificación registrada para la versión evaluada.'
                : saved && evidence.status === 'DRAFT' && 'Borrador guardado.'
          }
        />
      </section>
      <nav aria-label="Secciones de la evidencia" className="mb-5 flex flex-wrap gap-2">
        {Object.entries(tabs).map(([key, name]) => (
          <button
            type="button"
            key={key}
            aria-current={tab === key ? 'page' : undefined}
            onClick={() => setParams({ tab: key })}
            className={tab === key ? 'btn-primary' : 'btn-secondary'}
          >
            {name}
          </button>
        ))}
      </nav>
      {tab === 'documento' && (
        <section className="panel space-y-4 p-5">
          <h2 className="section-title">Documentos de la entrega</h2>
          {selected ? (
            <>
              <p className="text-sm text-muted">
                {selected.submittedAt
                  ? `Entregada el ${localDate(selected.submittedAt)}`
                  : 'Versión en preparación'}
                {selected.submissionNotes && ` · ${selected.submissionNotes}`}
              </p>
              {selected.documents.map((item) => (
                <EvidenceFile file={item} version={selected.versionNumber} key={item.id} />
              ))}
            </>
          ) : (
            <p className="text-sm text-muted">El borrador todavía no tiene archivos.</p>
          )}
          {result.canAttach && <VersionForm key={evidence.version} result={result} />}
        </section>
      )}
      {tab === 'historial' && (
        <>
          <WorkflowList empty="Todavía no se han adjuntado archivos.">
            {versions.map((item) => (
              <article
                className="flex flex-wrap items-center justify-between gap-3 p-5"
                key={item.id}
              >
                <div>
                  <h2 className="font-medium">Versión {item.versionNumber}</h2>
                  <p className="text-sm text-muted">
                    {item.submittedAt ? localDate(item.submittedAt) : 'En preparación'} ·{' '}
                    {people[item.submittedBy] ?? 'Estudiante'} · {item.documents.length} archivo(s)
                  </p>
                </div>
                <Button
                  variant="secondary"
                  onClick={() => setParams({ tab: 'documento', version: item.id })}
                >
                  Abrir documentos
                </Button>
              </article>
            ))}
          </WorkflowList>
          <section className="panel mt-5 space-y-3 p-5">
            <h2 className="section-title">Historial de estados</h2>
            <ol className="space-y-3 text-sm">
              {evidence.history
                .slice()
                .reverse()
                .map((event, index) => (
                  <li key={index}>
                    {evidenceStatusLabels[event.status]}
                    {event.versionNumber > 0 && ` · Versión ${event.versionNumber}`} ·{' '}
                    {people[event.actorId] ?? 'Cuenta institucional'} · {localDate(event.at)}
                  </li>
                ))}
            </ol>
          </section>
        </>
      )}
      {tab === 'retroalimentacion' && (
        <>
          <WorkflowList empty="Todavía no hay retroalimentación.">
            {reviews
              .slice()
              .reverse()
              .map((review) => (
                <article className="space-y-2 p-5" key={review.id}>
                  <h2 className="font-medium">{reviewLabels[review.outcome]}</h2>
                  <p className="text-xs text-muted">
                    {review.reviewKind === 'FORMATIVE'
                      ? 'Revisión formativa · Investigador'
                      : 'Revisión oficial · Evaluador'}{' '}
                    · {people[review.reviewerUserId]} · Versión{' '}
                    {versions.find((item) => item.id === review.evidenceVersionId)?.versionNumber} ·{' '}
                    {localDate(review.createdAt)}
                  </p>
                  <p className="whitespace-pre-wrap break-words text-sm">{review.feedback}</p>
                </article>
              ))}
          </WorkflowList>
          {result.canReview && versions[0] && (
            <ReviewForm key={versions[0].id} result={result} versionId={versions[0].id} />
          )}
        </>
      )}
      {tab === 'evaluacion' && (
        <WorkflowList empty="No hay calificaciones visibles para esta evidencia.">
          {grades
            .slice()
            .reverse()
            .map((grade) => (
              <article className="space-y-2 p-5" key={grade.id}>
                <h2 className="font-medium">Calificación registrada · {grade.totalScore}</h2>
                <p className="text-sm text-muted">
                  {grade.publishedAt
                    ? `Publicada el ${localDate(grade.publishedAt)}`
                    : 'Pendiente de publicación'}{' '}
                  · Versión{' '}
                  {versions.find((item) => item.id === grade.evidenceVersionId)?.versionNumber} ·{' '}
                  {people[grade.evaluatorUserId]}
                </p>
                <p className="text-sm">
                  {evidenceStatusLabels[grade.decision]} · {localDate(grade.submittedAt)}
                </p>
                {grade.feedback && (
                  <p className="whitespace-pre-wrap break-words text-sm">{grade.feedback}</p>
                )}
              </article>
            ))}
        </WorkflowList>
      )}
    </>
  );
}

function VersionForm({ result }: { result: EvidenceDetail }) {
  const mutation = useWorkflowMutation(demoEvidences.addVersion);
  const [files, setFiles] = useState<File[]>([]);
  const { evidence } = result;
  return (
    <form
      className="space-y-3 border-t border-line pt-4"
      onSubmit={(event) => {
        event.preventDefault();
        const fields = new FormData(event.currentTarget);
        mutation.mutate({
          id: evidence.id,
          version: evidence.version,
          files,
          notes: String(fields.get('notes') ?? ''),
        });
      }}
    >
      <h2 className="section-title">
        {evidence.currentVersionNumber ? 'Preparar nueva versión' : 'Adjuntar archivos'}
      </h2>
      <Field label="Archivos de la versión">
        <Input
          type="file"
          accept={fileAccept}
          multiple
          required
          onChange={(event) => setFiles(Array.from(event.target.files ?? []))}
        />
      </Field>
      <Field label="Observaciones de entrega (opcional)">
        <textarea name="notes" maxLength={2000} className="input" />
      </Field>
      <Button disabled={!files.length || files.length > 5 || mutation.isPending} type="submit">
        Guardar versión
      </Button>
      {files.length > 5 && (
        <p role="alert" className="field-error">
          Selecciona hasta cinco archivos.
        </p>
      )}
      {mutation.isPending && (
        <p role="status" className="text-sm text-muted">
          Validando y guardando los archivos…
        </p>
      )}
      <Feedback error={mutation.error} />
    </form>
  );
}

function ReviewForm({ result, versionId }: { result: EvidenceDetail; versionId: string }) {
  const mutation = useWorkflowMutation(demoEvidences.review);
  const { evidence } = result;
  return (
    <form
      className="panel mt-5 max-w-3xl space-y-4 p-5"
      onSubmit={(event) => {
        event.preventDefault();
        const form = event.currentTarget;
        const fields = new FormData(form);
        const outcome = String(fields.get('outcome') ?? 'COMMENT');
        if (outcome !== 'COMMENT' && !window.confirm('¿Solicitar una nueva versión al estudiante?'))
          return;
        mutation.mutate(
          {
            id: evidence.id,
            version: evidence.version,
            evidenceVersionId: versionId,
            outcome,
            feedback: String(fields.get('feedback') ?? ''),
          },
          { onSuccess: () => form.reset() },
        );
      }}
    >
      <h2 className="section-title">Registrar retroalimentación</h2>
      <Field label="Decisión de la revisión">
        <select name="outcome" className="input">
          <option value="COMMENT">Registrar observaciones</option>
          {result.canRequest && (
            <>
              <option value="CHANGES_REQUESTED">Solicitar correcciones</option>
              <option value="REDO_REQUESTED">Solicitar nueva entrega</option>
            </>
          )}
        </select>
      </Field>
      <Field label="Comentarios">
        <textarea name="feedback" className="input" rows={4} maxLength={4000} required />
      </Field>
      <Button type="submit" disabled={mutation.isPending}>
        Registrar revisión
      </Button>
      <Feedback
        error={mutation.error}
        success={mutation.isSuccess && 'Retroalimentación registrada.'}
      />
    </form>
  );
}
