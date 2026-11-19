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

import { academicUnitSchema, studentRequestSchema } from '../types/academic';
import {
  evidenceSchema,
  evidenceGradeSchema,
  evidenceReviewSchema,
  evidenceVersionSchema,
  rubricSchema,
} from '../types/evidence';
import { defaultUnits, demoRubric, seedEvidence } from './academic-seed';

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
    academicUnitIds: ['environment', 'social'],
    coordinationUnitIds: ['environment', 'social'],
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
  {
    id: 'u6',
    name: 'Mariana Vega',
    email: 'mariana@institucion.edu.mx',
    roles: ['COORDINATOR'],
    departmentId: 'health',
    academicUnitIds: ['health', 'engineering'],
    coordinationUnitIds: ['health', 'engineering'],
    status: 'ACTIVE',
  },
  {
    id: 'u7',
    name: 'Mateo Cruz',
    email: 'mateo@institucion.edu.mx',
    roles: ['STUDENT'],
    departmentId: 'environment',
    status: 'ACTIVE',
  },
  {
    id: 'u8',
    name: 'Lucía Ramos',
    email: 'lucia@institucion.edu.mx',
    roles: ['EVALUATOR'],
    departmentId: 'social',
    status: 'ACTIVE',
  },
  {
    id: 'u9',
    name: 'Raúl Neri',
    email: 'raul@institucion.edu.mx',
    roles: ['RESEARCHER'],
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
  academicUnits: z.array(academicUnitSchema).default(defaultUnits),
  studentRequests: z.array(studentRequestSchema).default([]),
  evidences: z.array(evidenceSchema).default([]),
  evidenceVersions: z.array(evidenceVersionSchema).default([]),
  evidenceReviews: z.array(evidenceReviewSchema).default([]),
  evidenceGrades: z.array(evidenceGradeSchema).default([]),
  rubrics: z.array(rubricSchema).default([demoRubric]),
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
        actorId: project.supervisorIds?.[0] ?? 'u2',
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
      academicUnitId: project.departmentId,
      supervisorIds: [
        project.departmentId === 'health' || project.departmentId === 'engineering' ? 'u6' : 'u2',
      ],
      evaluatorAssignments:
        project.id === 'p1'
          ? [
              {
                id: 'demo-project-evaluator',
                projectId: 'p1',
                evaluatorUserId: 'u4',
                status: 'ACTIVE' as const,
                assignedBy: 'u2',
                assignedAt: '2026-03-01T12:00:00Z',
                reason: 'Seguimiento de demostración.',
              },
            ]
          : [],
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
    studentRequests: seed
      ? [
          {
            id: 'request-seed',
            projectId: 'p1',
            studentUserId: 'u7',
            proposedBy: 'u3',
            proposedAt: '2026-11-18T12:00:00Z',
            rationale: 'Apoyará el registro de mediciones de campo.',
            status: 'PENDING',
            version: 1,
          },
        ]
      : [],
    ...(seed ? seedEvidence('2026-11-18T12:00:00Z') : {}),
    audit: [],
    publications: [],
  });
}

export function readDatabase(): DemoDatabase {
  const stored = localStorage.getItem(demoStorageKey);
  if (!stored) return initialDatabase();
  try {
    const data = databaseSchema.parse(JSON.parse(stored) as unknown);
    for (const profile of demoUsers.filter((item) => ['u6', 'u7', 'u8', 'u9'].includes(item.id))) {
      if (!data.users.some((item) => item.id === profile.id))
        data.users.push(structuredClone(profile));
    }
    const originalCoordinator = data.users.find(
      (item) => item.id === 'u2' && item.departmentId === 'environment',
    );
    if (originalCoordinator && !originalCoordinator.coordinationUnitIds) {
      originalCoordinator.coordinationUnitIds = ['environment', 'social'];
      originalCoordinator.academicUnitIds ??= ['environment', 'social'];
    }
    for (const project of data.projects) {
      // Seed projects from the previous demo used one coordinator for all units.
      if (!project.academicUnitId && /^p[1-5]$/.test(project.id)) {
        project.academicUnitId = project.departmentId;
        if (project.departmentId === 'engineering' || project.departmentId === 'health')
          project.supervisorIds = ['u6'];
      }
    }
    return data;
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
