import { Link, useParams } from 'react-router-dom';
import { PageHeading, AsyncState } from '../../components/ui';
import { useAssistantAnswer } from '../../api/assistant.queries';
import { SourceCards } from './source-cards';

export function AssistantSourcesPage() {
  const { id = '' } = useParams();
  const query = useAssistantAnswer(id);
  if (query.isPending) return <AsyncState loading />;
  if (query.error) return <AsyncState error={query.error} retry={() => void query.refetch()} />;
  return (
    <>
      <PageHeading
        eyebrow="Pantalla 35 · Trazabilidad"
        title="Fuentes de la respuesta"
        description={query.data.question}
        action={
          <Link to="/asistente" className="btn-secondary">
            Volver al chat
          </Link>
        }
      />
      <section className="panel p-6">
        <p className="mb-6 whitespace-pre-wrap text-sm leading-7">{query.data.text}</p>
        <SourceCards sources={query.data.sources} />
      </section>
    </>
  );
}
