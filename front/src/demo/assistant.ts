import { demoSessionGeneration } from '../api/auth.api';
import { subjectKey } from '../features/auth/access-policy';
import { can } from '../features/auth/model';
import {
  chatInputSchema,
  type AssistantAnswer,
  type ChatInput,
} from '../features/ai-assistant/model';
import { currency } from '../lib/format';
import { statusLabels } from '../types/project';
import { proposalStatusLabels } from '../types/proposal';
import { fail, requireDemoUser } from './access';
import { demoDocuments } from './documents';
import { demoProjects } from './projects';
import { demoProposals } from './proposals';

const histories = new Map<string, AssistantAnswer[]>();

export const demoAssistant = {
  async ask(input: ChatInput): Promise<AssistantAnswer> {
    const user = requireDemoUser('ai:ask');
    const generation = demoSessionGeneration();
    const values = chatInputSchema.safeParse(input);
    if (!values.success) fail(422, 'Revisa el texto y el ámbito de la consulta.');
    const { prompt, projectId, proposalId } = values.data;
    const projects = can(user, 'project:read') ? demoProjects.list() : [];
    const proposals = can(user, 'proposal:read') ? demoProposals.list() : [];
    const code = prompt.match(/\bINV-\d{4}-[a-z0-9-]+/gi) ?? [];
    const denied =
      /otr[oa]s? departamentos?|ignor[ae].*permisos|sin restricciones/i.test(prompt) ||
      Boolean(projectId && !projects.some((item) => item.id === projectId)) ||
      Boolean(proposalId && !proposals.some((item) => item.id === proposalId)) ||
      code.some(
        (value) => !projects.some((item) => item.code.toLowerCase() === value.toLowerCase()),
      );
    const scopedProjects = proposalId
      ? []
      : projects
          .filter(
            (item) =>
              (!projectId || item.id === projectId) &&
              (!code.length ||
                code.some((value) => value.toLowerCase() === item.code.toLowerCase())),
          )
          .slice(0, 10);
    const scopedProposals =
      projectId || code.length
        ? []
        : proposals.filter((item) => !proposalId || item.id === proposalId).slice(0, 10);
    const projectIds = denied ? [] : scopedProjects.map((item) => item.id);
    const proposalIds = denied ? [] : scopedProposals.map((item) => item.id);
    const matched =
      /avance|estado|resum|presupuesto|documento|archivo|protocolo|proyecto|propuesta|evidencia|informe/i.test(
        prompt,
      );
    const docs =
      !denied && matched && can(user, 'document:read')
        ? demoDocuments
            .list()
            .filter(
              (item) =>
                (item.projectId && projectIds.includes(item.projectId)) ||
                (item.proposalId && proposalIds.includes(item.proposalId)),
            )
        : [];
    const sources = docs
      .filter((item) => !docs.some((entry) => entry.previousId === item.id))
      .slice(0, 10)
      .map((item) => ({
        id: item.id,
        documentId: item.id,
        projectId: item.projectId,
        title: item.name,
        version: item.version,
        excerpt: `Archivo registrado · ${Math.ceil(item.sizeBytes / 1024)} KB.`,
      }));
    const projectLines = scopedProjects.map(
      (item) =>
        `${item.title} (${item.code}) · ${statusLabels[item.status]} · avance ${item.progress}% · ${currency(item.budget)} MXN.`,
    );
    const proposalLines = scopedProposals.map(
      (item) => `${item.title || 'Propuesta sin título'} · ${proposalStatusLabels[item.status]}.`,
    );
    const supported = matched && (projectLines.length > 0 || proposalLines.length > 0);
    const text = denied
      ? 'El ámbito solicitado queda fuera de los recursos autorizados para tu cuenta.'
      : supported
        ? `Respuesta simulada basada exclusivamente en registros autorizados.\n\n${[...projectLines, ...proposalLines].join('\n\n')}${sources.length ? '\n\nLos archivos asociados se muestran como referencias de catálogo. Su contenido requiere abrir cada documento.' : ''}`
        : 'La información registrada es insuficiente para responder esta pregunta. Puedes seleccionar una investigación y consultar su estado o sus archivos.';
    const current = requireDemoUser('ai:ask');
    if (generation !== demoSessionGeneration() || subjectKey(user) !== subjectKey(current))
      fail(403, 'La sesión cambió durante la consulta.');
    const answer: AssistantAnswer = {
      id: crypto.randomUUID(),
      question: prompt,
      text,
      createdAt: new Date().toISOString(),
      denied,
      sources,
      projectIds: supported ? projectIds : [],
      proposalIds: supported ? proposalIds : [],
    };
    histories.set(
      subjectKey(user),
      [answer, ...(histories.get(subjectKey(user)) ?? [])].slice(0, 50),
    );
    return answer;
  },
  history() {
    const user = requireDemoUser('ai:ask');
    const projects = new Set(
      can(user, 'project:read') ? demoProjects.list().map((item) => item.id) : [],
    );
    const proposals = new Set(
      can(user, 'proposal:read') ? demoProposals.list().map((item) => item.id) : [],
    );
    const documents = new Set(
      can(user, 'document:read') ? demoDocuments.list().map((item) => item.id) : [],
    );
    return (histories.get(subjectKey(user)) ?? []).filter(
      (item) =>
        item.projectIds.every((id) => projects.has(id)) &&
        item.proposalIds.every((id) => proposals.has(id)) &&
        item.sources.every((source) => documents.has(source.documentId)),
    );
  },
  answer(id: string) {
    return (
      demoAssistant.history().find((item) => item.id === id) ??
      fail(404, 'La respuesta no está disponible para tu cuenta.')
    );
  },
};
