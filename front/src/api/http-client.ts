import { z } from 'zod';
import { config } from '../lib/config';

export class ApiError extends Error {
  constructor(
    public readonly status: number,
    message: string,
  ) {
    super(message);
    this.name = 'ApiError';
  }
}

export async function request<T>(
  path: string,
  schema: z.ZodType<T>,
  init?: RequestInit,
): Promise<T> {
  const response = await fetch(`${config.apiUrl}${path}`, {
    ...init,
    credentials: 'include',
    headers: { ...(init?.body ? { 'Content-Type': 'application/json' } : {}), ...init?.headers },
  }).catch((error: unknown) => {
    if (init?.signal?.aborted) throw error;
    throw new ApiError(
      0,
      'No pudimos conectar con el servidor. Revisa tu conexión e inténtalo de nuevo.',
    );
  });
  if (!response.ok) {
    const messages: Record<number, string> = {
      401: 'La sesión expiró o las credenciales son incorrectas. Inicia sesión de nuevo.',
      403: 'No tienes permiso para realizar esta acción.',
      404: 'No se encontró el recurso solicitado.',
      412: 'El registro cambió. Actualiza los datos e inténtalo de nuevo.',
      422: 'Revisa los datos ingresados.',
      429: 'Hay demasiadas solicitudes. Inténtalo en unos momentos.',
      409: 'El registro cambió. Actualiza los datos e inténtalo de nuevo.',
    };
    throw new ApiError(
      response.status,
      messages[response.status] ?? 'No fue posible completar la solicitud. Inténtalo de nuevo.',
    );
  }
  const payload: unknown = response.status === 204 ? undefined : await response.json();
  const parsed = schema.safeParse(payload);
  if (!parsed.success) throw new Error('El servidor devolvió datos con un formato inesperado.');
  return parsed.data;
}
