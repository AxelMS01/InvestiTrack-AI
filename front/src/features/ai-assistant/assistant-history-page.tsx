import { Link } from 'react-router-dom';
import { PageHeading, AsyncState, EmptyState } from '../../components/ui';
import { useAssistantHistory } from '../../api/assistant.queries';

export function AssistantHistoryPage() {
  const query = useAssistantHistory();
  return (
    <>
      <PageHeading
        eyebrow="Pantalla 36 · Conversaciones"
        title="Historial del asistente"
        description="Consultas del perfil activo. El historial de demostración permanece solo durante esta sesión del navegador."
        action={
          <Link to="/asistente" className="btn-primary">
            Volver al chat
          </Link>
        }
      />
      <section className="panel divide-y divide-line">
        {query.isPending ? (
          <AsyncState loading />
        ) : query.error ? (
          <AsyncState error={query.error} retry={() => void query.refetch()} />
        ) : !query.data.length ? (
          <EmptyState
            title="Aún no hay conversaciones"
            description="Envía una pregunta en el asistente para comenzar."
          />
        ) : (
          query.data.map((answer) => (
            <article key={answer.id} className="p-6">
              <p className="mb-2 text-xs text-muted">
                {new Date(answer.createdAt).toLocaleString('es-MX')} ·{' '}
                {answer.denied ? 'Consulta restringida' : `${answer.sources.length} fuentes`}
              </p>
              <h2 className="text-sm font-medium">{answer.question}</h2>
              <Link
                to={`/asistente/respuestas/${answer.id}/fuentes`}
                className="mt-4 inline-block text-xs text-accent"
              >
                Consultar respuesta y respaldo →
              </Link>
            </article>
          ))
        )}
      </section>
    </>
  );
}
