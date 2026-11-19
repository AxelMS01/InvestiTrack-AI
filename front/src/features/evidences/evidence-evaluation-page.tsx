import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { useNavigate, useParams } from 'react-router-dom';
import { useDemoQuery, useWorkflowMutation } from '../../api/demo.queries';
import { Button } from '../../components/button';
import { Input } from '../../components/input';
import { PageHeading } from '../../components/ui';
import { demoEvidences } from '../../demo/evidences';
import {
  evidenceStatusLabels,
  gradeDecision,
  gradeInputSchema,
  type GradeInput,
} from '../../types/evidence';
import { useAuth } from '../auth/auth-context';
import { ActionLink, Feedback, Field, WorkflowState } from '../shared/workflow-ui';
import { EvidenceFile } from './evidence-file';
import type { EvidenceDetail } from './evidence-detail-page';

export function EvidenceEvaluationPage() {
  const { id = '' } = useParams();
  const query = useDemoQuery(['evidence', id], () => demoEvidences.detail(id));
  const mutation = useWorkflowMutation(demoEvidences.beginReview);
  const leave = useWorkflowMutation(demoEvidences.leaveReview);
  const result = query.data;
  const version = result?.versions.find((item) => item.id === result.gradeContext?.versionId);
  return (
    <WorkflowState query={query}>
      {result && (
        <>
          <PageHeading
            eyebrow="Evaluación oficial"
            title={result.evidence.title}
            description={`${result.projectTitle} · ${result.activityTitle} · ${result.people[result.evidence.ownerUserId] ?? 'Estudiante'}`}
            action={<ActionLink to={`/evidencias/${id}`}>Volver a la evidencia</ActionLink>}
          />
          {result.gradeContext && version ? (
            <>
              <div className="panel mb-5 space-y-4 p-5">
                <h2 className="section-title">Entrega · Versión {version.versionNumber}</h2>
                {version.documents.map((file) => (
                  <EvidenceFile key={file.id} file={file} version={version.versionNumber} />
                ))}
              </div>
              {result.evidence.reviewerInProgressId ? (
                <div className="space-y-4">
                  <GradeForm key={version.id} result={result} context={result.gradeContext} />
                  <Button
                    variant="secondary"
                    disabled={leave.isPending}
                    onClick={() => {
                      if (
                        window.confirm(
                          '¿Liberar la revisión actual? Los cambios sin enviar se descartarán.',
                        )
                      )
                        leave.mutate({ id, version: result.evidence.version });
                    }}
                  >
                    Liberar revisión
                  </Button>
                  <Feedback error={leave.error} />
                </div>
              ) : (
                <section className="panel space-y-3 p-5">
                  <p className="text-sm">
                    Inicia la revisión de esta versión para completar la rúbrica oficial.
                  </p>
                  <Button
                    disabled={mutation.isPending}
                    onClick={() => mutation.mutate({ id, version: result.evidence.version })}
                  >
                    Iniciar evaluación
                  </Button>
                  <Feedback error={mutation.error} />
                </section>
              )}
            </>
          ) : (
            <section className="panel p-5">
              <p role="status">
                Esta versión ya fue resuelta o tu asignación no permite calificarla.
              </p>
            </section>
          )}
        </>
      )}
    </WorkflowState>
  );
}

function GradeForm({
  result,
  context,
}: {
  result: EvidenceDetail;
  context: NonNullable<EvidenceDetail['gradeContext']>;
}) {
  const navigate = useNavigate();
  const { user } = useAuth();
  const mutation = useWorkflowMutation(demoEvidences.grade);
  const { evidence } = result;
  const { rubric } = context;
  const {
    register,
    handleSubmit,
    formState: { errors },
  } = useForm<GradeInput>({
    resolver: zodResolver(gradeInputSchema),
    defaultValues: {
      evaluatorAssignmentId: context.assignment.id,
      rubricId: rubric.id,
      rubricScores: rubric.criteria.map((item) => ({ criterionId: item.id, comment: '' })),
      feedback: '',
    },
  });
  return (
    <form
      className="panel max-w-3xl space-y-5 p-5"
      onSubmit={(event) =>
        void handleSubmit(async (grade) => {
          const result = await mutation
            .mutateAsync({
              id: evidence.id,
              version: evidence.version,
              evidenceVersionId: context.versionId,
              grade,
            })
            .catch(() => null);
          if (result)
            void navigate(`/evidencias/${evidence.id}?tab=evaluacion`, {
              state: { gradedBy: user?.id },
            });
        })(event)
      }
    >
      <h2 className="section-title">
        {rubric.title} · Versión {rubric.version}
      </h2>
      <input type="hidden" {...register('evaluatorAssignmentId')} />
      <input type="hidden" {...register('rubricId')} />
      {rubric.criteria.map((item, index) => (
        <fieldset key={item.id} className="space-y-3 rounded-lg border border-line p-4">
          <legend className="px-2 text-sm font-medium">{item.name}</legend>
          <input type="hidden" {...register(`rubricScores.${index}.criterionId`)} />
          <Field label={`Puntos · máximo ${item.maxScore} · ponderación ${item.weight}`}>
            <Input
              type="number"
              required
              min={0}
              max={item.maxScore}
              step="0.01"
              {...register(`rubricScores.${index}.score`, { valueAsNumber: true })}
            />
          </Field>
          <Field label="Observaciones del criterio">
            <textarea
              className="input"
              maxLength={2000}
              {...register(`rubricScores.${index}.comment`)}
            />
          </Field>
        </fieldset>
      ))}
      <Field label="Decisión oficial">
        <select className="input" required defaultValue="" {...register('decision')}>
          <option value="">Selecciona el resultado</option>
          {gradeDecision.options.map((value) => (
            <option value={value} key={value}>
              {evidenceStatusLabels[value]}
            </option>
          ))}
        </select>
      </Field>
      <Field label="Retroalimentación de la calificación (visible al publicarse)">
        <textarea className="input" rows={4} maxLength={4000} {...register('feedback')} />
      </Field>
      <label className="flex items-start gap-2 text-sm">
        <input type="checkbox" required />
        Confirmo el envío de la calificación para esta versión.
      </label>
      {Object.keys(errors).length > 0 && (
        <p role="alert" className="field-error">
          Completa la rúbrica y selecciona una decisión válida.
        </p>
      )}
      <Feedback error={mutation.error} />
      <Button disabled={mutation.isPending} type="submit">
        Registrar calificación
      </Button>
    </form>
  );
}
