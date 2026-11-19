import { useState } from 'react';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { Link } from 'react-router-dom';
import { Send, Sparkles } from 'lucide-react';
import { PageHeading, AsyncState } from '../../components/ui';
import { Button } from '../../components/button';
import { useAuth } from '../auth/auth-context';
import { can } from '../auth/model';
import { useProposals } from '../../api/proposals.queries';
import { useProjects } from '../../api/projects.queries';
import { useAskAssistant, useAssistantHistory } from '../../api/assistant.queries';
import { chatInputSchema, type ChatInput } from './model';
import { SourceCards } from './source-cards';

export function AssistantChatPage() {
  const { user, simulatorEnabled } = useAuth();
  const projects = useProjects(Boolean(user && can(user, 'project:read')));
  const proposals = useProposals(Boolean(user && can(user, 'proposal:read')));
  const [scope, setScope] = useState('');
  const history = useAssistantHistory();
  const ask = useAskAssistant();
  const {
    register,
    handleSubmit,
    resetField,
    formState: { errors },
  } = useForm<ChatInput>({
    resolver: zodResolver(chatInputSchema),
    defaultValues: { prompt: '', projectId: '' },
  });
  if (!simulatorEnabled)
    return (
      <PageHeading
        eyebrow="Asistente IA"
        title="Simulación no disponible"
        description="Activa el entorno de demostración para probar el asistente institucional."
      />
    );
  return (
    <>
      <PageHeading
        eyebrow="Asistente de investigación"
        title="Tu asistente institucional"
        description="Consulta el estado de tus proyectos y revisa el respaldo de cada respuesta."
        action={
          <Link className="btn-secondary" to="/asistente/historial">
            Ver historial
          </Link>
        }
      />
      <div className="mb-6 rounded-xl border border-accent/20 bg-sage/60 p-4 text-xs leading-6">
        <strong>Simulación con permisos activos.</strong> Resume registros de investigación
        autorizados y muestra archivos asociados.
      </div>
      <section className="panel p-5 sm:p-7">
        <div
          role="log"
          aria-label="Conversación con el asistente"
          aria-live="polite"
          className="max-h-[38rem] space-y-6 overflow-y-auto"
        >
          {history.isPending ? (
            <AsyncState loading />
          ) : history.error ? (
            <AsyncState error={history.error} retry={() => void history.refetch()} />
          ) : !history.data.length ? (
            <div className="py-8 text-center">
              <Sparkles className="mx-auto mb-4 text-accent" />
              <p className="font-medium">Comienza con una pregunta</p>
              <p className="mt-3 text-sm text-muted">
                Por ejemplo: ¿Cuál es el avance de mis proyectos autorizados?
              </p>
            </div>
          ) : (
            [...history.data].reverse().map((answer) => (
              <article key={answer.id} className="space-y-4 border-b border-line pb-6">
                <p className="ml-auto max-w-xl rounded-xl bg-sage p-4 text-sm">
                  <strong className="mb-1 block text-xs">Tú</strong>
                  {answer.question}
                </p>
                <div className="max-w-3xl">
                  <p className="mb-2 text-xs font-semibold text-accent">
                    InvescticTrack IA · simulación
                  </p>
                  <p className="whitespace-pre-wrap break-words text-sm leading-7">{answer.text}</p>
                  <div className="mt-5">
                    <SourceCards sources={answer.sources} />
                  </div>
                  <Link
                    to={`/asistente/respuestas/${answer.id}/fuentes`}
                    className="mt-4 inline-block text-xs font-medium text-accent"
                  >
                    Abrir fuentes de esta respuesta →
                  </Link>
                </div>
              </article>
            ))
          )}
        </div>
        {ask.isPending && (
          <p role="status" className="mt-3 text-sm text-muted">
            Consultando los registros autorizados…
          </p>
        )}
        <form
          noValidate
          className="mt-6 space-y-3 border-t border-line pt-5"
          onSubmit={handleSubmit(async (values) => {
            const result = await ask
              .mutateAsync({
                ...values,
                projectId: scope.startsWith('project:') ? scope.slice(8) : undefined,
                proposalId: scope.startsWith('proposal:') ? scope.slice(9) : undefined,
              })
              .catch(() => null);
            if (result) resetField('prompt');
          })}
        >
          <label htmlFor="chat-project" className="field-label">
            Ámbito de la consulta
          </label>
          <select
            id="chat-project"
            className="input"
            value={scope}
            onChange={(event) => setScope(event.target.value)}
            disabled={ask.isPending}
          >
            <option value="">Todas mis investigaciones autorizadas</option>
            {projects.data?.map((project) => (
              <option key={project.id} value={`project:${project.id}`}>
                {project.code} · {project.title}
              </option>
            ))}
            {proposals.data
              ?.filter((item) => item.title)
              .map((item) => (
                <option key={item.id} value={`proposal:${item.id}`}>
                  Propuesta · {item.title}
                </option>
              ))}
          </select>
          {proposals.error && (
            <p role="alert" className="field-error">
              {proposals.error.message}
            </p>
          )}
          {projects.error && (
            <p role="alert" className="field-error">
              {projects.error.message}
            </p>
          )}
          <label htmlFor="chat-prompt" className="field-label">
            Tu pregunta
          </label>
          <textarea
            id="chat-prompt"
            className="input"
            rows={3}
            maxLength={1500}
            {...register('prompt')}
            aria-invalid={Boolean(errors.prompt)}
            aria-describedby={errors.prompt ? 'chat-error' : undefined}
            placeholder="Pregunta sobre el avance, presupuesto o estado de los proyectos…"
          />
          {errors.prompt && (
            <p id="chat-error" className="field-error">
              {errors.prompt.message}
            </p>
          )}
          {ask.error && (
            <p role="alert" className="field-error">
              {ask.error.message}
            </p>
          )}
          <Button
            type="submit"
            disabled={ask.isPending || history.isPending || Boolean(history.error)}
          >
            <Send size={16} />
            {ask.isPending ? 'Consultando…' : 'Enviar pregunta'}
          </Button>
        </form>
      </section>
    </>
  );
}
