import { describe, expect, it } from 'vitest';
import { matchRoutes } from 'react-router-dom';
import { modules } from '../features/modules';
import { protectedRoutes } from './protected-routes';
import { publicRoutes } from './public-routes';

describe('Catálogo de arquitectura y rutas', () => {
  it('incluye doce módulos sin identificadores ni rutas repetidos', () => {
    expect(modules).toHaveLength(12);
    expect(new Set(modules.map((module) => module.id)).size).toBe(modules.length);
    const paths = modules.flatMap((module) => module.screens.map((screen) => screen.path));
    expect(new Set(paths).size).toBe(paths.length);
  });
  for (const module of modules) {
    for (const screen of module.screens.filter((view) =>
      ['INTEGRATED', 'MOCK_COMPLETE'].includes(view.status),
    )) {
      it(`registra la pantalla operativa ${module.id}/${screen.id}`, () => {
        const matches = matchRoutes(
          [...publicRoutes, ...protectedRoutes],
          screen.path.replaceAll(':id', 'example-id'),
        );
        expect(matches).not.toBeNull();
        expect(matches?.at(-1)?.route.path).not.toBe('*');
      });
    }
  }
});
