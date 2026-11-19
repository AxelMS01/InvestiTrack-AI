import { canManageProject, canReadProject } from '../features/auth/access-policy';
import { fail, requireDemoUser, visibleProject } from './access';
import { publicationInputSchema, readDatabase, updateDatabase } from './database';

export const demoReports = {
  submitFinalReport(input: { id: string; documentId: string }) {
    const user = requireDemoUser('project:update');
    return updateDatabase((data) => {
      const project = visibleProject(data, user, input.id);
      if (!canManageProject(user, project))
        fail(403, 'Solo la persona responsable del proyecto abierto puede presentar el informe.');
      if (
        !project.finalReport ||
        project.finalReport.documentId !== input.documentId ||
        project.finalReport.submittedAt ||
        !data.documents.some(
          (item) =>
            item.id === input.documentId &&
            item.projectId === project.id &&
            item.category === 'report',
        )
      )
        fail(409, 'Se requiere un informe final en borrador vigente.');
      project.finalReport.submittedAt = new Date().toISOString();
      return project.finalReport;
    });
  },
  publications() {
    const user = requireDemoUser('report:read');
    const data = readDatabase();
    return data.publications.flatMap((item) => {
      const project = data.projects.find((entry) => entry.id === item.projectId);
      return project && canReadProject(user, project)
        ? [{ ...item, projectTitle: project.title, editable: canManageProject(user, project) }]
        : [];
    });
  },
  savePublication(input: {
    id?: string;
    projectId: string;
    title: string;
    url: string;
    date: string;
  }) {
    const user = requireDemoUser('project:update');
    const parsed = publicationInputSchema.safeParse(input);
    if (!parsed.success) fail(422, 'Revisa el título, la URL HTTP(S) y la fecha.');
    return updateDatabase((data) => {
      const project = visibleProject(data, user, input.projectId);
      if (!canManageProject(user, project))
        fail(403, 'La publicación requiere un proyecto abierto bajo tu responsabilidad.');
      const existing = input.id
        ? data.publications.find((item) => item.id === input.id && item.projectId === project.id)
        : undefined;
      if (input.id && !existing) fail(404, 'La publicación no está disponible.');
      const publication = {
        ...parsed.data,
        id: existing?.id ?? crypto.randomUUID(),
        projectId: project.id,
      };
      if (existing) data.publications[data.publications.indexOf(existing)] = publication;
      else data.publications.push(publication);
      return publication;
    });
  },
};
