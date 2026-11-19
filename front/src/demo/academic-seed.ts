import { departmentLabels } from '../types/department';
import { rubricSchema } from '../types/evidence';

export const defaultUnits = Object.entries(departmentLabels).map(([id, name]) => ({
  id,
  name,
  code: id.toUpperCase(),
  active: true,
}));
export const demoRubric = rubricSchema.parse({
  id: 'demo-rubric-evidence',
  title: 'Rúbrica de evidencias de demostración',
  version: 1,
  criteria: [
    { id: 'traceability', name: 'Trazabilidad de la evidencia', maxScore: 10, weight: 1 },
    { id: 'clarity', name: 'Claridad de la entrega', maxScore: 10, weight: 1 },
  ],
});

export const seedFileIds = ['demo-field-file-1', 'demo-field-file-2', 'demo-field-file-3'];

export function seededFile(id: string) {
  if (!seedFileIds.includes(id)) return undefined;
  const title = 'Registro de campo - InvescticTrack - Demostracion';
  const stream = 'BT /F1 16 Tf 40 730 Td (' + title + ') Tj ET';
  const objects = [
    '<< /Type /Catalog /Pages 2 0 R >>',
    '<< /Type /Pages /Kids [3 0 R] /Count 1 >>',
    '<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>',
    '<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>',
    '<< /Length ' + stream.length + ' >>\nstream\n' + stream + '\nendstream',
  ];
  let pdf = '%PDF-1.4\n';
  const offsets = objects.map((object, index) => {
    const offset = pdf.length;
    pdf += index + 1 + ' 0 obj\n' + object + '\nendobj\n';
    return String(offset).padStart(10, '0') + ' 00000 n \n';
  });
  const start = pdf.length;
  pdf +=
    'xref\n0 6\n0000000000 65535 f \n' +
    offsets.join('') +
    'trailer\n<< /Size 6 /Root 1 0 R >>\nstartxref\n' +
    start +
    '\n%%EOF\n';
  return new File([pdf], id + '.pdf', { type: 'application/pdf' });
}

export function seedEvidence(at: string) {
  const evidenceIds = ['demo-field-evidence-1', 'demo-field-evidence-2', 'demo-field-evidence-3'];
  const versionIds = evidenceIds.map((id) => id + '-v1');
  return {
    evidences: evidenceIds.map((id, index) => ({
      id,
      evidenceNumber: 'EVD-DEMO-00' + (index + 1),
      projectId: 'p1',
      activityId: 'a1',
      ownerUserId: 'u5',
      title: [
        'Registro inicial de observaciones',
        'Bitácora de mediciones',
        'Resultados de la visita de campo',
      ][index]!,
      description: 'Entrega de ejemplo vinculada a la actividad de campo.',
      status: index < 2 ? 'ACCEPTED' : 'SUBMITTED',
      currentVersionNumber: 1,
      version: 3,
      createdAt: at,
      updatedAt: at,
      history: [
        { status: 'SUBMITTED', actorId: 'u5', at, versionNumber: 1 },
        ...(index < 2 ? [{ status: 'ACCEPTED', actorId: 'u4', at, versionNumber: 1 }] : []),
      ],
    })),
    evidenceVersions: versionIds.map((id, index) => ({
      id,
      evidenceId: evidenceIds[index]!,
      versionNumber: 1,
      documentIds: [seedFileIds[index]!],
      submittedBy: 'u5',
      createdAt: at,
      submittedAt: at,
      submissionNotes: '',
    })),
    documents: seedFileIds.map((id, index) => {
      const file = seededFile(id)!;
      return {
        id,
        name: file.name,
        projectId: 'p1',
        activityId: 'a1',
        evidenceVersionId: versionIds[index]!,
        mimeType: file.type,
        sizeBytes: file.size,
        version: 1,
        uploadedBy: 'u5',
        uploadedAt: at,
        category: 'evidence',
      };
    }),
    evidenceGrades: versionIds.slice(0, 2).map((id, index) => ({
      id: 'demo-grade-' + (index + 1),
      evidenceId: evidenceIds[index]!,
      evidenceVersionId: id,
      evaluatorAssignmentId: 'demo-project-evaluator',
      evaluatorUserId: 'u4',
      rubricId: demoRubric.id,
      rubricScores: demoRubric.criteria.map(({ id }) => ({
        criterionId: id,
        score: 8,
        comment: 'Criterio atendido.',
      })),
      decision: 'ACCEPTED',
      status: 'SUBMITTED',
      feedback: 'Ejemplo de evaluación de campo.',
      totalScore: 8,
      submittedAt: at,
      publishedAt: index === 0 ? at : null,
    })),
    evidenceReviews: [
      {
        id: 'demo-review-1',
        evidenceId: evidenceIds[0]!,
        evidenceVersionId: versionIds[0]!,
        reviewerUserId: 'u3',
        reviewKind: 'FORMATIVE',
        outcome: 'COMMENT',
        feedback: 'Incluye las unidades de medida en la próxima entrega.',
        createdAt: at,
      },
    ],
  };
}
