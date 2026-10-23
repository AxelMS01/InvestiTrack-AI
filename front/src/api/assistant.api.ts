import { config } from '../lib/config';
import { currency } from '../lib/format';
import { ApiError } from './http-client';
import { authApi } from './auth.api';
import { projectsApi } from './projects.api';
import { subjectKey, canReadProject } from '../features/auth/access-policy';
import { departmentLabels, departmentSchema } from '../types/department';
import { statusLabels } from '../types/project';
import {
  chatInputSchema,
  type ChatInput,
  type AssistantAnswer,
} from '../features/ai-assistant/model';
import type { User } from '../types';

const histories = new Map<string, AssistantAnswer[]>();
async function requireUser(): Promise<User> {
  if (!config.demo)
    throw new ApiError(501, 'El asistente simulado solo está disponible en demostración.');
  const user = await authApi.me();
  if (!user) throw new ApiError(401, 'Inicia sesión para consultar el asistente.');
  return user;
}
async function assertSession(user: User) {
  const current = await requireUser();
  if (subjectKey(current) !== subjectKey(user))
    throw new ApiError(403, 'El perfil cambió. Repite la consulta con el rol activo.');
}
async function historyFor(user: User) {
  const projects = await projectsApi.list();
  await assertSession(user);
  const visibleIds = new Set(
    projects.filter((project) => canReadProject(user, project)).map((p) => p.id),
  );
  // Never return a stored answer containing a source that has lost authorization.
  return (histories.get(subjectKey(user)) ?? []).filter((entry) =>
    entry.sources.every((source) => visibleIds.has(source.projectId)),
  );
}

export const assistantApi = {
  ask: async (input: ChatInput): Promise<AssistantAnswer> => {
    const user = await requireUser();
    const values = chatInputSchema.parse(input);
    const visible = (await projectsApi.list()).filter((project) => canReadProject(user, project));
    const prompt = values.prompt.toLocaleLowerCase('es');
    const requestedCodes = values.prompt.match(/\bINV-\d{4}-[a-z0-9-]+/gi) ?? [];
    const asksOutsideDepartment =
      user.role !== 'admin' &&
      (/otr[oa]s? departamentos?|ignor[ae].*permisos|sin restricciones/.test(prompt) ||
        departmentSchema.options.some(
          (id) =>
            id !== user.departmentId &&
            prompt.includes(departmentLabels[id].toLocaleLowerCase('es')),
        ));
    // Prompts never expand scope: retrieval above contains only authorized records.
    const denied =
      asksOutsideDepartment ||
      requestedCodes.some(
        (code) => !visible.some((project) => project.code.toLowerCase() === code.toLowerCase()),
      ) ||
      Boolean(values.projectId && !visible.some((p) => p.id === values.projectId));
    const selected = denied
      ? []
      : values.projectId
        ? visible.filter((p) => p.id === values.projectId)
        : visible;
    const sources = selected.map((project) => ({
      id: `record-${project.id}`,
      projectId: project.id,
      title: `Ficha institucional · ${project.code}`,
      version: 1,
      excerpt: `${project.title}. Estado: ${statusLabels[project.status]}. Avance: ${project.progress}%. Presupuesto: ${currency(project.budget)} MXN. Responsable: ${project.lead}.`,
    }));
    const answer: AssistantAnswer = {
      id: crypto.randomUUID(),
      question: values.prompt,
      createdAt: new Date().toISOString(),
      denied,
      sources,
      text: denied
        ? 'No puedo consultar información fuera de los proyectos autorizados para tu perfil. Selecciona un proyecto de tu ámbito para continuar.'
        : !sources.length
          ? 'No hay proyectos autorizados disponibles para respaldar esta respuesta.'
          : `Respuesta simulada basada exclusivamente en ${sources.length} ficha(s) autorizada(s).\n\n${sources.map((source, index) => `[${index + 1}] ${source.excerpt}`).join('\n\n')}\n\nSolo puedo resumir los datos registrados; esta simulación no realiza búsquedas externas ni genera conclusiones científicas.`,
    };
    await assertSession(user);
    histories.set(
      subjectKey(user),
      [answer, ...(histories.get(subjectKey(user)) ?? [])].slice(0, 50),
    );
    return answer;
  },
  history: async () => historyFor(await requireUser()),
  answer: async (id: string) => {
    const answer = (await assistantApi.history()).find((entry) => entry.id === id);
    if (!answer) throw new ApiError(404, 'La respuesta no está disponible para este perfil.');
    return answer;
  },
};
