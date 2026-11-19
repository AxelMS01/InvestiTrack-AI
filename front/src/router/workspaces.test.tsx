import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { sessionKey } from '../api/auth.queries';
import { authApi } from '../api/auth.api';
import { AuthProvider } from '../features/auth/auth-provider';
import { pdf } from '../test/workflow-fixtures';
import { protectedRoutes } from './protected-routes';
import { publicRoutes } from './public-routes';

vi.mock('../lib/config', () => ({ config: { demo: true, apiUrl: '/api' } }));

async function open(role: string, path = '/') {
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

afterEach(() => {
  vi.restoreAllMocks();
});

it('autoriza una incorporación desde su detalle y muestra la decisión confirmada', async () => {
  vi.spyOn(window, 'confirm').mockReturnValue(true);
  const { user } = await open('coordinator', '/solicitudes-estudiantes/request-seed');
  expect(
    await screen.findByRole('heading', { name: 'Solicitud de incorporación' }),
  ).toBeInTheDocument();
  expect(screen.getByText('Mateo Cruz')).toBeInTheDocument();
  await user.selectOptions(screen.getByLabelText('Decisión'), 'APPROVED');
  await user.click(screen.getByRole('button', { name: 'Confirmar decisión' }));
  expect(await screen.findByText('Solicitud actualizada.')).toBeInTheDocument();
  expect(screen.getByText('Aprobada', { exact: true })).toBeInTheDocument();
});

it('muestra solo las unidades de la cuenta de coordinación y conserva el ámbito al navegar', async () => {
  const { user } = await open('u6', '/coordinacion');
  const select = await screen.findByLabelText('Unidad académica');
  expect(
    within(select)
      .getAllByRole('option')
      .map((item) => item.textContent),
  ).toEqual(['Ingeniería y tecnología', 'Ciencias de la salud']);
  await user.selectOptions(select, 'health');
  await user.click(await screen.findByRole('link', { name: 'Consultar proyectos' }));
  expect(
    await screen.findByRole('heading', { name: 'Proyectos de investigación' }),
  ).toBeInTheDocument();
  expect(
    await screen.findByText('Inteligencia artificial para el diagnóstico temprano'),
  ).toBeInTheDocument();
  expect(
    screen.queryByText('Monitoreo inteligente de la calidad del agua'),
  ).not.toBeInTheDocument();
});

it('crea, entrega y califica desde las rutas V2 sin mostrar una nota pendiente al estudiante', async () => {
  vi.spyOn(window, 'confirm').mockReturnValue(true);
  const revoke = vi.fn();
  vi.stubGlobal(
    'URL',
    class extends URL {
      static createObjectURL() {
        return 'blob:demo-test';
      }
      static revokeObjectURL = revoke;
    },
  );
  const { user } = await open('student', '/evidencias/nueva?actividad=a1');
  await user.type(
    await screen.findByLabelText('Nombre de la evidencia'),
    'Informe de mediciones V2',
  );
  await user.type(screen.getByLabelText('Descripción'), 'Mediciones de la visita.');
  await user.upload(
    screen.getByLabelText('Archivos adjuntos (opcional en borrador)'),
    pdf('mediciones.pdf'),
  );
  await user.click(screen.getByRole('button', { name: 'Guardar borrador' }));
  expect(
    await screen.findByRole('heading', { name: 'Informe de mediciones V2' }),
  ).toBeInTheDocument();
  expect(await screen.findByLabelText('Vista previa de mediciones.pdf')).toBeInTheDocument();
  await user.click(screen.getByRole('button', { name: 'Confirmar entrega' }));
  expect(await screen.findByText('Versión entregada para revisión.')).toBeInTheDocument();
  await user.selectOptions(screen.getByLabelText('Simulador de perfiles'), 'reviewer');
  const main = await screen.findByRole('main');
  await user.click(await within(main).findByRole('link', { name: 'Evaluar evidencia' }));
  await user.click(await screen.findByRole('button', { name: 'Iniciar evaluación' }));
  for (const input of await screen.findAllByRole('spinbutton')) await user.type(input, '8');
  await user.selectOptions(screen.getByLabelText('Decisión oficial'), 'ACCEPTED');
  await user.click(
    screen.getByLabelText('Confirmo el envío de la calificación para esta versión.'),
  );
  await user.click(screen.getByRole('button', { name: 'Registrar calificación' }));
  expect(
    await screen.findByRole('heading', { name: 'Calificación registrada · 8' }),
  ).toBeInTheDocument();
  expect(screen.getByText(/Pendiente de publicación/)).toBeInTheDocument();
  await user.selectOptions(screen.getByLabelText('Simulador de perfiles'), 'student');
  expect(
    await screen.findByText('No hay calificaciones visibles para esta evidencia.'),
  ).toBeInTheDocument();
  expect(
    screen.queryByText(/Pendiente de publicación|Calificación registrada/),
  ).not.toBeInTheDocument();
  expect(revoke).toHaveBeenCalled();
});
