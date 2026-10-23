import { z } from 'zod';
import { dateSchema } from '../../types/shared';
import { areas } from '../../types/project';

export const proposalInputSchema = z
  .object({
    title: z.string().trim().min(12, 'Escribe al menos 12 caracteres.').max(180),
    abstract: z.string().trim().min(80, 'El resumen debe tener al menos 80 caracteres.').max(3000),
    area: z.enum(areas),
    objective: z
      .string()
      .trim()
      .min(30, 'Describe el objetivo con al menos 30 caracteres.')
      .max(1500),
    methodology: z
      .string()
      .trim()
      .min(80, 'La metodología debe tener al menos 80 caracteres.')
      .max(4000),
    startDate: dateSchema,
    endDate: dateSchema,
    team: z
      .array(
        z.object({
          name: z.string().trim().min(3, 'Ingresa el nombre completo.'),
          email: z.email('Correo inválido.'),
          contribution: z
            .string()
            .trim()
            .min(10, 'Describe su contribución con al menos 10 caracteres.'),
        }),
      )
      .min(1)
      .max(10, 'Máximo 10 colaboradores.'),
    budgetItems: z
      .array(
        z.object({
          concept: z.string().trim().min(5, 'Describe el concepto con al menos 5 caracteres.'),
          category: z.enum(['personnel', 'equipment', 'services', 'materials']),
          amount: z
            .number('Ingresa un monto válido.')
            .finite()
            .positive('El monto debe ser mayor a cero.')
            .multipleOf(0.01, 'Usa como máximo dos decimales.')
            .max(1_000_000),
        }),
      )
      .min(1)
      .max(20, 'Máximo 20 partidas.'),
    requestedFunding: z
      .number('Ingresa un monto válido.')
      .finite()
      .positive('El financiamiento debe ser mayor a cero.')
      .multipleOf(0.01, 'Usa como máximo dos decimales.'),
    involvesParticipants: z.boolean(),
    ethicsApproval: z.boolean(),
    declaration: z
      .boolean()
      .refine((value) => value, 'Debes confirmar la integridad de la propuesta.'),
  })
  .superRefine((values, context) => {
    if (values.endDate <= values.startDate)
      context.addIssue({
        code: 'custom',
        path: ['endDate'],
        message: 'El cierre debe ser posterior al inicio.',
      });
    const emails = values.team.map((member) => member.email.trim().toLowerCase());
    emails.forEach((email, index) => {
      if (emails.indexOf(email) !== index)
        context.addIssue({
          code: 'custom',
          path: ['team', index, 'email'],
          message: 'Este correo ya está en el equipo.',
        });
    });
    const total =
      values.budgetItems.reduce((sum, item) => sum + Math.round(item.amount * 100), 0) / 100;
    if (total > 1_000_000)
      context.addIssue({
        code: 'custom',
        path: ['budgetItems'],
        message: 'El presupuesto total no puede superar $1,000,000 MXN.',
      });
    if (values.requestedFunding > total)
      context.addIssue({
        code: 'custom',
        path: ['requestedFunding'],
        message: 'El financiamiento no puede superar el presupuesto desglosado.',
      });
    if (values.involvesParticipants && !values.ethicsApproval)
      context.addIssue({
        code: 'custom',
        path: ['ethicsApproval'],
        message: 'Se requiere aprobación ética para estudios con participantes.',
      });
  });
export type ProposalInput = z.infer<typeof proposalInputSchema>;
