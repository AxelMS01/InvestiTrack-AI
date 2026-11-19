import { useEffect, useState } from 'react';
import { Button } from '../../components/button';
import { demoDocuments } from '../../demo/documents';
import { downloadBlob } from '../../lib/download';
import type { Document } from '../../types/document';
import { Feedback } from '../shared/workflow-ui';

type FileState = { url?: string; error?: Error; loaded?: boolean };
export function EvidenceFile({ file, version }: { file: Document; version: number }) {
  const [state, setState] = useState<FileState>();
  useEffect(() => {
    let cancelled = false;
    let url: string | undefined;
    void demoDocuments
      .download(file.id)
      .then(({ blob }) => {
        if (cancelled) return;
        const preview = file.mimeType === 'application/pdf' || file.mimeType.startsWith('image/');
        url = preview ? URL.createObjectURL(blob) : undefined;
        setState({ loaded: true, url });
      })
      .catch((error: Error) => {
        if (!cancelled) setState({ error });
      });
    return () => {
      cancelled = true;
      if (url) URL.revokeObjectURL(url);
    };
  }, [file.id, file.mimeType]);
  return (
    <section className="space-y-3 rounded-lg border border-line p-4">
      <p className="break-all text-sm font-medium">
        {file.name} · Versión académica {version}
      </p>
      {!state && (
        <p role="status" className="text-sm text-muted">
          Cargando archivo…
        </p>
      )}
      <Feedback error={state?.error} />
      {state?.url &&
        (file.mimeType === 'application/pdf' ? (
          <object
            data={state.url}
            type="application/pdf"
            aria-label={`Vista previa de ${file.name}`}
            className="h-[60vh] min-h-64 w-full rounded border border-line"
          >
            <p className="p-4 text-sm text-muted">
              Este navegador no muestra PDF en línea. Usa la descarga autorizada.
            </p>
          </object>
        ) : (
          <img
            className="max-h-[60vh] max-w-full rounded object-contain"
            src={state.url}
            alt={`Vista previa de ${file.name}`}
          />
        ))}
      {state?.loaded && (
        <>
          {!state.url && (
            <p className="text-sm text-muted">
              Este formato se puede consultar mediante descarga autorizada.
            </p>
          )}
          <Button
            variant="secondary"
            onClick={async () => {
              try {
                const result = await demoDocuments.download(file.id);
                downloadBlob(result.blob, result.name);
              } catch (error) {
                setState((previous) => ({
                  ...previous,
                  error:
                    error instanceof Error
                      ? error
                      : new Error('No fue posible descargar el archivo.'),
                }));
              }
            }}
          >
            Descargar archivo
          </Button>
        </>
      )}
    </section>
  );
}
