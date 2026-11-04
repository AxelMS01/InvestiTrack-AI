import { describe, expect, it } from 'vitest';
import { matchRoutes } from 'react-router-dom';
import { modules } from '../features/modules';
import { protectedRoutes } from './protected-routes';
import { publicRoutes } from './public-routes';

describe('Catálogo de arquitectura y rutas', () => {
  it('incluye diez módulos sin identificadores ni rutas repetidos', () => {
    expect(modules).toHaveLength(10);
    expect(new Set(modules.map((module) => module.id)).size).toBe(10);
    const paths = modules.flatMap((module) => module.screens.map((screen) => screen.path));
    expect(new Set(paths).size).toBe(paths.length);
  });
  for (const module of modules) {
    for (const screen of module.screens.filter((view) => view.status === 'implemented')) {
      it(`registra la pantalla implementada ${module.id}/${screen.id}`, () => {
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
