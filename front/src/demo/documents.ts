import { canReadEvidenceVersion } from './evidence-policy';
import { demoSessionGeneration } from '../api/auth.api';
import { demoFiles } from './files';
import type { Document } from '../types/document';
import {
  canCoordinateProject,
  canManageProject,
  canReadProject,
  projectEvaluation,
} from '../features/auth/access-policy';
import { can } from '../features/auth/model';
import type { User } from '../types';
import {
  canEditProposal,
  canReadProposal,
  fail,
  requireDemoUser,
  visibleProject,
  visibleProposal,
} from './access';
import { readDatabase, updateDatabase, type DemoDatabase } from './database';
import { visibleActivity } from './activities';

type Context = Pick<
  Document,
  'projectId' | 'proposalId' | 'activityId' | 'category' | 'previousId'
>;
export const fileAccept = '.pdf,.docx,.xlsx,.pptx,.png,.jpg,.jpeg,.webp,.gif';
const types: Record<string, string> = {
  pdf: 'application/pdf',
  docx: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
  xlsx: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
  pptx: 'application/vnd.openxmlformats-officedocument.presentationml.presentation',
  png: 'image/png',
  jpg: 'image/jpeg',
  jpeg: 'image/jpeg',
  webp: 'image/webp',
  gif: 'image/gif',
};

export function fileType(file: File, category: Context['category']) {
  const extension = file.name.split('.').pop()?.toLowerCase() ?? '';
  const mime = Object.hasOwn(types, extension) ? types[extension] : undefined;
  if (!mime || (file.type && file.type !== mime) || (category === 'report' && extension !== 'pdf'))
    fail(
      415,
      category === 'report'
        ? 'El informe final debe ser un PDF.'
        : 'El formato del archivo no está admitido.',
    );
  if (file.size === 0 || file.size > 10 * 1024 * 1024)
    fail(413, 'El archivo debe pesar entre 1 byte y 10 MB.');
  return mime;
}

export async function validateContent(file: File, mime: string) {
  const bytes = new Uint8Array(
    await new Promise<ArrayBuffer>((resolve, reject) => {
      const reader = new FileReader();
      reader.onload = () => {
        if (reader.result instanceof ArrayBuffer) resolve(reader.result);
        else reject(new Error('No pudimos leer el archivo.'));
      };
      reader.onerror = () => reject(new Error('No pudimos leer el archivo.'));
      reader.readAsArrayBuffer(file.slice(0, 12));
    }),
  );
  const header = new TextDecoder().decode(bytes);
  const valid =
    mime === 'application/pdf'
      ? header.startsWith('%PDF-')
      : mime === 'image/png'
        ? bytes[0] === 137 && header.slice(1, 4) === 'PNG'
        : mime === 'image/jpeg'
          ? bytes[0] === 255 && bytes[1] === 216 && bytes[2] === 255
          : mime === 'image/gif'
            ? /^GIF8[79]a/.test(header)
            : mime === 'image/webp'
              ? header.startsWith('RIFF') && header.slice(8) === 'WEBP'
              : bytes[0] === 80 && bytes[1] === 75 && bytes[2] === 3 && bytes[3] === 4;
  if (!valid) fail(415, 'El contenido del archivo no coincide con el formato seleccionado.');
}

function readable(data: DemoDatabase, user: User, item: Document) {
  if (item.evidenceVersionId) {
    const version = data.evidenceVersions.find(
      (entry) => entry.id === item.evidenceVersionId && entry.documentIds.includes(item.id),
    );
    return Boolean(version && canReadEvidenceVersion(data, user, version));
  }
  if (item.proposalId) {
    const proposal = data.proposals.find((entry) => entry.id === item.proposalId);
    return Boolean(proposal && canReadProposal(user, proposal));
  }
  const project = data.projects.find((entry) => entry.id === item.projectId);
  if (!project || !canReadProject(user, project)) return false;
  if (projectEvaluation(user, project) && item.category !== 'other') return false;
  if (item.activityId) {
    const activity = data.activities.find((entry) => entry.id === item.activityId);
    return (
      project.leadId === user.id ||
      canCoordinateProject(user, project) ||
      activity?.assigneeId === user.id
    );
  }
  return true;
}
function writable(data: DemoDatabase, user: User, context: Context) {
  if (context.proposalId) {
    if (
      context.projectId ||
      context.activityId ||
      context.category !== 'protocol' ||
      !canEditProposal(user, visibleProposal(data, user, context.proposalId))
    )
      fail(403, 'Solo puedes adjuntar documentos a una propuesta propia editable.');
    if (visibleProposal(data, user, context.proposalId).status !== 'draft')
      fail(403, 'Esta solicitud de cambios solo permite corregir los campos autorizados.');
    return;
  }
  const project = visibleProject(data, user, context.projectId ?? '');
  if (project.status === 'completed') fail(409, 'El proyecto está cerrado.');
  if (context.category === 'report' && project.finalReport?.submittedAt)
    fail(409, 'El informe final ya se presentó y está bloqueado.');
  if (context.activityId) {
    const { activity } = visibleActivity(data, user, context.activityId);
    if (
      context.category !== 'evidence' ||
      activity.projectId !== project.id ||
      activity.status === 'cancelled' ||
      (activity.assigneeId !== user.id && !canManageProject(user, project))
    )
      fail(403, 'No puedes entregar evidencia en esta actividad.');
  } else if (context.category === 'evidence' || !canManageProject(user, project)) {
    fail(403, 'Selecciona una actividad autorizada o un proyecto bajo tu responsabilidad.');
  }
}

export const demoDocuments = {
  list() {
    const user = requireDemoUser('document:read');
    const data = readDatabase();
    return data.documents.filter((item) => readable(data, user, item)).reverse();
  },
  detail(id: string) {
    const item = demoDocuments.list().find((entry) => entry.id === id);
    return item ?? fail(404, 'El archivo no está disponible para tu perfil.');
  },
  versions(id: string) {
    const documents = demoDocuments.list();
    const current = documents.find((item) => item.id === id);
    if (!current) fail(404, 'El archivo no está disponible.');
    let first = current;
    const byId = new Map(documents.map((item) => [item.id, item]));
    while (first.previousId && byId.has(first.previousId) && first.version > 1) {
      const previous = byId.get(first.previousId)!;
      if (previous.version >= first.version) break;
      first = previous;
    }
    const chain = [first];
    for (const item of [...documents].reverse()) {
      if (chain.some((version) => version.id === item.previousId)) chain.push(item);
    }
    return chain.reverse();
  },
  permissions(id: string) {
    const user = requireDemoUser('document:read');
    const data = readDatabase();
    const item = demoDocuments.detail(id);
    if (item.category === 'evidence' || data.documents.some((entry) => entry.previousId === id))
      return { upload: false, remove: false };
    try {
      writable(data, user, item);
      return {
        upload: can(user, 'document:upload'),
        remove: can(user, 'document:delete') && item.uploadedBy === user.id,
      };
    } catch {
      return { upload: false, remove: false };
    }
  },
  async upload(context: Context, file: File, onProcessing?: () => void) {
    const actor = requireDemoUser('document:upload');
    if (context.category === 'evidence')
      fail(409, 'Crea o actualiza la entrega desde el módulo de evidencias.');
    const generation = demoSessionGeneration();
    writable(readDatabase(), actor, context);
    const mimeType = fileType(file, context.category);
    onProcessing?.();
    await validateContent(file, mimeType);
    const id = crypto.randomUUID();
    await demoFiles
      .put(id, file)
      .catch(() => fail(507, 'No pudimos guardar el archivo en este navegador.'));
    try {
      const user = requireDemoUser('document:upload');
      if (actor.id !== user.id || generation !== demoSessionGeneration())
        fail(403, 'La sesión cambió durante la carga.');
      return updateDatabase((data) => {
        writable(data, user, context);
        const previous = context.previousId
          ? data.documents.find((entry) => entry.id === context.previousId)
          : undefined;
        if (
          context.previousId &&
          (!previous ||
            !readable(data, user, previous) ||
            previous.projectId !== context.projectId ||
            previous.proposalId !== context.proposalId ||
            previous.activityId !== context.activityId ||
            previous.category !== context.category ||
            data.documents.some((entry) => entry.previousId === previous.id))
        )
          fail(409, 'Selecciona la versión vigente del documento.');
        const document: Document = {
          ...context,
          id,
          name: file.name,
          mimeType,
          sizeBytes: file.size,
          version: (previous?.version ?? 0) + 1,
          uploadedBy: user.id,
          uploadedAt: new Date().toISOString(),
        };
        data.documents.push(document);
        if (document.category === 'report') {
          const project = visibleProject(data, user, document.projectId ?? '');
          project.finalReport = { documentId: document.id };
        }
        return document;
      });
    } catch (error) {
      await demoFiles.remove(id).catch(() => undefined);
      throw error;
    }
  },
  async download(id: string) {
    const user = requireDemoUser('document:download');
    const generation = demoSessionGeneration();
    const item = demoDocuments.detail(id);
    const blob = await demoFiles
      .get(item.id)
      .catch(() => fail(503, 'No pudimos abrir el almacenamiento de archivos.'));
    const current = requireDemoUser('document:download');
    if (current.id !== user.id || generation !== demoSessionGeneration())
      fail(403, 'La sesión cambió durante la descarga.');
    demoDocuments.detail(id);
    if (!blob) fail(404, 'El archivo ya no está disponible en este navegador.');
    return { name: item.name, blob };
  },
  async remove(id: string) {
    const user = requireDemoUser('document:delete');
    updateDatabase((data) => {
      const item = data.documents.find((entry) => entry.id === id && readable(data, user, entry));
      if (!item) fail(404, 'El archivo no está disponible.');
      if (item.category === 'evidence')
        fail(409, 'El archivo forma parte del historial de evidencias.');
      writable(data, user, item);
      if (item.uploadedBy !== user.id || data.documents.some((entry) => entry.previousId === id))
        fail(409, 'Solo puedes eliminar tu archivo cuando es la última versión.');
      if (item.category === 'report') {
        const project = visibleProject(data, user, item.projectId ?? '');
        if (project.finalReport?.documentId === item.id)
          project.finalReport = item.previousId ? { documentId: item.previousId } : undefined;
      }
      data.documents = data.documents.filter((entry) => entry.id !== id);
    });
    await demoFiles.remove(id).catch(() => undefined);
  },
};
