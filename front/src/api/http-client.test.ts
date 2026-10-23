import { afterEach, describe, expect, it, vi } from 'vitest';
import { z } from 'zod';
import { request } from './http-client';

afterEach(() => vi.unstubAllGlobals());
describe('Contrato HTTP', () => {
  it('rechaza una respuesta exitosa con estructura inválida', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({ name: 42 }))));
    await expect(request('/example', z.object({ name: z.string() }))).rejects.toThrow(
      'formato inesperado',
    );
  });
  it('conserva el código de acceso denegado sin revelar mensajes internos', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(new Response('internal database info', { status: 403 })),
    );
    await expect(request('/example', z.unknown())).rejects.toMatchObject({
      status: 403,
      message: 'No tienes permiso para realizar esta acción.',
    });
  });
  it('admite respuestas sin cuerpo al cerrar sesión', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(null, { status: 204 })));
    await expect(
      request('/auth/logout', z.undefined(), { method: 'POST' }),
    ).resolves.toBeUndefined();
  });
});
