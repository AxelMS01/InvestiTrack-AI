import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import { beforeEach, expect, it, vi } from 'vitest';
import { sessionKey } from '../api/auth.queries';
import { authApi } from '../api/auth.api';
import { AuthProvider } from '../features/auth/auth-provider';
import type { Role } from '../types';
import { protectedRoutes } from './protected-routes';
import { publicRoutes } from './public-routes';

vi.mock('../lib/config', () => ({ config: { demo: true, apiUrl: '/api' } }));

async function open(role: Role, path = '/') {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  client.setQueryData(sessionKey, await authApi.demoLogin(role));
  const router = createMemoryRouter(
    [
      {
        hydrateFallbackElement: <p>Cargando pantalla…</p>,
        children: [...publicRoutes, ...protectedRoutes],
      },
    ],
    { initialEntries: [path] },
  );
  render(
    <QueryClientProvider client={client}>
      <AuthProvider>
        <RouterProvider router={router} />
      </AuthProvider>
    </QueryClientProvider>,
  );
  return { client, router, user: userEvent.setup() };
}

beforeEach(async () => {
  localStorage.clear();
  await authApi.logout();
});

it.each([
  ['admin', 'Resumen administrativo', 'Usuarios', 'Asistente IA'],
  ['coordinator', 'Centro de coordinación', 'Asignación de evaluadores', 'Usuarios'],
  ['researcher', 'Mi investigación', 'Mis propuestas', 'Asignación de evaluadores'],
  ['reviewer', 'Centro de evaluaciones', 'Propuestas asignadas', 'Mis proyectos'],
  ['student', 'Mis actividades de investigación', 'Documentos compartidos', 'Usuarios'],
] as const)('muestra el panel y menú de %s', async (role, title, allowed, denied) => {
  await open(role);
  expect(await screen.findByRole('heading', { name: title })).toBeInTheDocument();
  const menu = within(screen.getByRole('complementary', { name: 'Navegación principal' }));
  expect(menu.getByRole('link', { name: allowed })).toBeInTheDocument();
  expect(menu.queryByRole('link', { name: denied })).not.toBeInTheDocument();
});

it('cambia de responsabilidad sin cambiar la sesión ni agregar capacidades', async () => {
  const { client, user, router } = await open('researcher', '/panel/investigador');
  expect(await screen.findByRole('heading', { name: 'Mi investigación' })).toBeInTheDocument();
  const identity = client.getQueryData(sessionKey);
  await user.selectOptions(screen.getByLabelText('Espacio de trabajo'), 'reviewer');
  expect(
    await screen.findByRole('heading', { name: 'Centro de evaluaciones' }),
  ).toBeInTheDocument();
  expect(client.getQueryData(sessionKey)).toEqual(identity);
  await router.navigate('/usuarios');
  expect(
    await screen.findByRole('heading', { name: /No tienes acceso|Acceso denegado/i }),
  ).toBeInTheDocument();
});

it.each([
  ['student', '/usuarios', /No tienes acceso|Acceso denegado/i],
  ['researcher', '/proyectos/p5', /No pudimos cargar/],
  ['reviewer', '/propuestas/proposal-p1', /No pudimos cargar/],
  ['admin', '/documentos/privado', /No tienes acceso|Acceso denegado/i],
] as const)('bloquea la URL directa %s → %s', async (role, path, message) => {
  await open(role, path);
  if (path === '/usuarios' || path.startsWith('/documentos')) {
    expect(await screen.findByRole('heading', { name: message })).toBeInTheDocument();
  } else {
    expect(await screen.findByText(message)).toBeInTheDocument();
  }
});

it('envía una rúbrica desde la ruta real y muestra sus resultados', async () => {
  const { user } = await open('reviewer', '/evaluaciones/evaluation-seed/rubrica');
  expect(
    await screen.findByRole('heading', { name: 'Protocolo de monitoreo comunitario' }),
  ).toBeInTheDocument();
  await user.click(screen.getByRole('button', { name: 'Enviar evaluación' }));
  expect(await screen.findByRole('alert')).toHaveTextContent(/Revisa|Elige|Escribe/);
  for (const input of screen.getAllByRole('spinbutton')) await user.type(input, '90');
  await user.selectOptions(screen.getByLabelText('Recomendación del evaluador'), 'approve');
  await user.type(
    screen.getByLabelText('Retroalimentación'),
    'El protocolo cumple los criterios solicitados.',
  );
  await user.click(screen.getByRole('button', { name: 'Enviar evaluación' }));
  expect(
    await screen.findByRole('heading', { name: 'Resultados de evaluación' }),
  ).toBeInTheDocument();
  expect(screen.getByText('Recomendación académica · Aprobar')).toBeInTheDocument();
});
