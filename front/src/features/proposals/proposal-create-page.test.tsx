import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { expect, it, vi } from 'vitest';
vi.mock('../../lib/config', () => ({ config: { demo: true, apiUrl: '/api' } }));
import { AuthProvider } from '../auth/auth-provider';
import { authApi } from '../../api/auth.api';
import { sessionKey } from '../../api/auth.queries';
import { proposalsApi } from '../../api/proposals.api';
import { ProposalCreatePage } from './proposal-create-page';

it('valida el protocolo y la ética antes de registrar una propuesta completa', async () => {
  localStorage.clear();
  const researcher = await authApi.demoLogin('researcher');
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  client.setQueryData(sessionKey, researcher);
  const user = userEvent.setup();
  render(
    <QueryClientProvider client={client}>
      <AuthProvider>
        <MemoryRouter initialEntries={['/propuestas/nueva']}>
          <Routes>
            <Route path="/propuestas/nueva" element={<ProposalCreatePage />} />
            <Route path="/propuestas/:id" element={<p>Propuesta registrada</p>} />
          </Routes>
        </MemoryRouter>
      </AuthProvider>
    </QueryClientProvider>,
  );
  await user.click(screen.getByRole('button', { name: 'Enviar propuesta' }));
  expect(await screen.findByText('Escribe al menos 12 caracteres.')).toBeInTheDocument();
  const fill = (label: string, value: string) =>
    fireEvent.change(screen.getByLabelText(label), { target: { value } });
  fill('Título de la propuesta', 'Monitoreo del agua en comunidades rurales');
  fill(
    'Resumen científico',
    'Evaluación de la calidad del agua en comunidades rurales mediante análisis de laboratorio y participación de los actores locales.',
  );
  fill(
    'Objetivo general',
    'Caracterizar la calidad del agua para proponer mejoras en comunidades rurales.',
  );
  fill(
    'Metodología',
    'Se utilizarán muestras de agua recolectadas en puntos representativos, con análisis de laboratorio y comparación de resultados durante el periodo de estudio.',
  );
  fill('Fecha de inicio', '2026-11-01');
  fill('Fecha de cierre', '2027-06-30');
  fill('Nombre completo 1', 'Ana López');
  fill('Correo del colaborador 1', 'ana@example.com');
  fill('Contribución al proyecto 1', 'Análisis de laboratorio');
  fill('Concepto 1', 'Análisis de muestras');
  fill('Monto 1', '150000');
  fill('Financiamiento solicitado (MXN)', '120000');
  await user.click(screen.getByLabelText('La investigación involucra participantes humanos.'));
  await user.click(
    screen.getByLabelText(
      'Confirmo la integridad de los datos y la participación del equipo declarado.',
    ),
  );
  await user.click(screen.getByRole('button', { name: 'Enviar propuesta' }));
  expect(
    await screen.findByText('Se requiere aprobación ética para estudios con participantes.'),
  ).toBeInTheDocument();
  expect(await proposalsApi.list()).toEqual([]);
  await user.click(screen.getByLabelText('Cuento con aprobación del comité de ética.'));
  await user.click(screen.getByRole('button', { name: 'Enviar propuesta' }));
  expect(await screen.findByText('Propuesta registrada')).toBeInTheDocument();
  expect((await proposalsApi.list())[0]).toMatchObject({
    ownerId: 'u3',
    departmentId: 'environment',
    status: 'submitted',
  });
  await authApi.demoLogin('reviewer');
  await expect(proposalsApi.list()).rejects.toMatchObject({ status: 403 });
  client.clear();
});
