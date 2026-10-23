import { z } from 'zod';
import { projectSchema, type Project } from '../../types/project';

const initialProjects: Project[] = [
  {
    id: 'p1',
    departmentId: 'environment',
    code: 'INV-2026-001',
    title: 'Monitoreo inteligente de la calidad del agua',
    description:
      'Desarrollo de una red de sensores para evaluar la calidad del agua en comunidades rurales y facilitar decisiones basadas en evidencia.',
    area: 'Ciencias ambientales',
    budget: 480000,
    startDate: '2026-02-01',
    endDate: '2026-12-15',
    status: 'active',
    progress: 72,
    lead: 'Elena Torres',
    leadId: 'u3',
  },
  {
    id: 'p2',
    departmentId: 'health',
    code: 'INV-2026-002',
    title: 'Inteligencia artificial para el diagnóstico temprano',
    description:
      'Diseño y evaluación de modelos de aprendizaje automático como apoyo a la identificación temprana de factores de riesgo clínico.',
    area: 'Salud y bienestar',
    budget: 720000,
    startDate: '2026-03-15',
    endDate: '2027-03-15',
    status: 'active',
    progress: 48,
    lead: 'Carlos Mendoza',
    leadId: 'u2',
  },
  {
    id: 'p3',
    departmentId: 'engineering',
    code: 'INV-2026-003',
    title: 'Transición energética en espacios universitarios',
    description:
      'Estudio de viabilidad de fuentes renovables y estrategias de eficiencia energética para la infraestructura del campus universitario.',
    area: 'Tecnología e innovación',
    budget: 350000,
    startDate: '2026-01-12',
    endDate: '2026-11-10',
    status: 'review',
    progress: 92,
    lead: 'Elena Torres',
    leadId: 'u3',
  },
  {
    id: 'p4',
    departmentId: 'social',
    code: 'INV-2026-004',
    title: 'Inclusión digital y desarrollo comunitario',
    description:
      'Análisis participativo de las barreras de acceso a herramientas digitales y su relación con el desarrollo de comunidades locales.',
    area: 'Ciencias sociales',
    budget: 180000,
    startDate: '2026-11-01',
    endDate: '2027-06-30',
    status: 'planning',
    progress: 0,
    lead: 'Andrea Martínez',
    leadId: 'u1',
  },
  {
    id: 'p5',
    departmentId: 'environment',
    code: 'INV-2026-005',
    title: 'Biodiversidad y restauración de ecosistemas',
    description:
      'Caracterización de especies locales y evaluación de prácticas de restauración ecológica en zonas de alta vulnerabilidad ambiental.',
    area: 'Ciencias ambientales',
    budget: 290000,
    startDate: '2025-08-01',
    endDate: '2026-09-30',
    status: 'completed',
    progress: 100,
    lead: 'Carlos Mendoza',
    leadId: 'u2',
  },
];
const storageKey = 'invesctictrack.demo.projects.v1';
export function readDemoProjects(): Project[] {
  const stored = localStorage.getItem(storageKey);
  if (!stored) return structuredClone(initialProjects);
  try {
    return z.array(projectSchema).parse(JSON.parse(stored) as unknown);
  } catch {
    throw new Error(
      'Los datos de demostración están dañados. Borra la clave invesctictrack.demo.projects.v1 del almacenamiento del navegador para restablecerlos.',
    );
  }
}
export function writeDemoProjects(projects: Project[]) {
  localStorage.setItem(storageKey, JSON.stringify(z.array(projectSchema).parse(projects)));
}
