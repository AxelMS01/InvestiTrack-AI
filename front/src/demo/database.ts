import { z } from 'zod';
import { activitySchema } from '../types/activity';
import { documentSchema } from '../types/document';
import { evaluationSchema } from '../types/evaluation';
import { projectSchema, type Project } from '../types/project';
import { registeredProposalSchema, type RegisteredProposal } from '../types/proposal';
import { userSchema } from '../types/user';
import { readDemoProjects } from '../features/projects/demo';
import { addDays } from '../lib/format';
import { ApiError } from '../api/http-client';

export const demoUsers = z.array(userSchema).parse([
  {
    id: 'u1',
    name: 'Andrea Martínez',
    email: 'andrea@institucion.edu.mx',
    roles: ['ADMIN'],
    status: 'ACTIVE',
  },
  {
    id: 'u2',
    name: 'Carlos Mendoza',
    email: 'carlos@institucion.edu.mx',
    roles: ['COORDINATOR'],
    departmentId: 'environment',
    status: 'ACTIVE',
  },
  {
    id: 'u3',
    name: 'Elena Torres',
    email: 'elena@institucion.edu.mx',
    roles: ['RESEARCHER', 'EVALUATOR'],
    departmentId: 'environment',
    status: 'ACTIVE',
  },
  {
    id: 'u4',
    name: 'Daniel Ruiz',
    email: 'daniel@institucion.edu.mx',
    roles: ['EVALUATOR'],
    departmentId: 'engineering',
    status: 'ACTIVE',
  },
  {
    id: 'u5',
    name: 'Sofía López',
    email: 'sofia@institucion.edu.mx',
    roles: ['STUDENT'],
    departmentId: 'environment',
    status: 'ACTIVE',
  },
]);
const proposalKey = 'invesctictrack.demo.proposals.v1';
export const demoStorageKey = 'invesctictrack.demo.workspace.v2';
const auditSchema = z.object({
  id: z.string(),
  actorId: z.string(),
  targetId: z.string(),
  action: z.string(),
  at: z.iso.datetime(),
});
export const publicationInputSchema = z.object({
  title: z.string().trim().min(3).max(180),
  url: z.httpUrl(),
  date: z.iso.date(),
});
const publicationSchema = publicationInputSchema.extend({ id: z.string(), projectId: z.string() });
const databaseSchema = z.object({
  users: z.array(userSchema),
  proposals: z.array(registeredProposalSchema),
  projects: z.array(projectSchema),
  evaluations: z.array(evaluationSchema),
  activities: z.array(activitySchema),
  documents: z.array(documentSchema),
  audit: z.array(auditSchema),
  publications: z.array(publicationSchema),
});
export type DemoDatabase = z.infer<typeof databaseSchema>;

function proposalFrom(project: Project): RegisteredProposal {
  const at = project.startDate + 'T12:00:00.000Z';
  return {
    id: 'proposal-' + project.id,
    ownerId: project.leadId,
    title: project.title,
    abstract: project.description,
    departmentId: project.departmentId ?? 'environment',
    status: 'approved',
    version: 1,
    createdAt: at,
    updatedAt: at,
    details: {
      title: project.title,
      abstract: project.description,
      area: project.area,
      objective:
        'Caracterizar el fenómeno de estudio y comunicar los resultados a la comunidad universitaria.',
      methodology:
        'Se aplicará un protocolo de levantamiento de datos con revisión colegiada de los resultados y resguardo institucional.',
      startDate: project.startDate,
      endDate: project.endDate,
      team: [
        {
          name: 'Equipo de campo',
          email: 'equipo@institucion.edu.mx',
          contribution: 'Registro y análisis de observaciones.',
        },
      ],
      budgetItems: [{ concept: 'Trabajo de campo', category: 'materials', amount: project.budget }],
      requestedFunding: project.budget,
      involvesParticipants: false,
      ethicsApproval: false,
      declaration: true,
    },
    history: [
      {
        status: 'approved',
        at,
        actorId: 'u2',
        note: 'Aprobación registrada en el conjunto de demostración.',
      },
    ],
    allowedFields: [],
    reviewerIds: [],
  };
}

function initialDatabase(): DemoDatabase {
  const legacyProjects = readDemoProjects();
  const projects = legacyProjects.map((project) => {
    if (!/^p[1-5]$/.test(project.id)) return project;
    return {
      ...project,
      proposalId: 'proposal-' + project.id,
      supervisorIds: ['u2'],
      members: project.id === 'p1' ? [{ userId: 'u5', responsibility: 'student' as const }] : [],
    };
  });
  const seed = projects.find((project) => project.id === 'p1');
  const assigned = seed
    ? {
        ...proposalFrom(seed),
        id: 'proposal-assigned',
        title: 'Protocolo de monitoreo comunitario',
        status: 'under_review' as const,
        version: 3,
        submissionVersion: 2,
        reviewerIds: ['u4'],
        history: [
          {
            status: 'draft' as const,
            at: seed.startDate + 'T12:00:00.000Z',
            actorId: 'u3',
            note: 'Borrador creado.',
          },
          {
            status: 'submitted' as const,
            at: seed.startDate + 'T13:00:00.000Z',
            actorId: 'u3',
            note: 'Enviada para evaluación.',
          },
          {
            status: 'under_review' as const,
            at: seed.startDate + 'T14:00:00.000Z',
            actorId: 'u2',
            note: 'Se asignó una evaluación.',
          },
        ],
      }
    : undefined;
  if (assigned) {
    assigned.details.title = assigned.title;
    assigned.updatedAt = assigned.history[2]!.at;
  }
  let legacy: RegisteredProposal[];
  try {
    legacy = z
      .array(registeredProposalSchema)
      .parse(JSON.parse(localStorage.getItem(proposalKey) ?? '[]') as unknown);
  } catch {
    throw new ApiError(409, 'Las propuestas de demostración almacenadas están dañadas.');
  }
  return databaseSchema.parse({
    users: demoUsers,
    proposals: [
      ...legacy,
      ...(assigned ? [assigned] : []),
      ...projects
        .filter((project) => project.proposalId === 'proposal-' + project.id)
        .map(proposalFrom),
    ],
    projects,
    evaluations: assigned
      ? [
          {
            id: 'evaluation-seed',
            proposalId: assigned.id,
            proposalTitle: assigned.title,
            reviewerId: 'u4',
            dueDate: addDays(7),
            status: 'assigned',
            scores: [],
            feedback: '',
            recommendation: null,
            submittedAt: null,
            submissionVersion: assigned.submissionVersion,
          },
        ]
      : [],
    activities: projects.some((project) => project.id === 'p1')
      ? [
          {
            id: 'a1',
            projectId: 'p1',
            title: 'Registrar mediciones de campo',
            description: 'Completa la hoja de mediciones y entrega el archivo como evidencia.',
            assigneeId: 'u5',
            assignedBy: 'u3',
            dueDate: '2026-12-01',
            status: 'pending',
            isMilestone: false,
          },
        ]
      : [],
    documents: [],
    audit: [],
    publications: [],
  });
}

export function readDatabase(): DemoDatabase {
  const stored = localStorage.getItem(demoStorageKey);
  if (!stored) return initialDatabase();
  try {
    return databaseSchema.parse(JSON.parse(stored) as unknown);
  } catch {
    throw new ApiError(
      409,
      'Los datos de demostración están dañados. Restablece el almacenamiento de demostración.',
    );
  }
}

export function updateDatabase<T>(change: (data: DemoDatabase) => T): T {
  const data = readDatabase();
  const result = change(data);
  localStorage.setItem(demoStorageKey, JSON.stringify(databaseSchema.parse(data)));
  return result;
}
