import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('../../lib/config', () => ({ config: { demo: true, apiUrl: '/api' } }));
import { authApi } from '../../api/auth.api';
import { ProjectCreatePage } from './project-create-page';
import { projectsApi } from '../../api/projects.api';

describe('Registro desde el formulario', () => {
  beforeEach(async () => {
    localStorage.clear();
    await authApi.demoLogin('researcher');
  });
  it('muestra errores accesibles y permite corregir y registrar un proyecto', async () => {
    const user = userEvent.setup();
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(
      <QueryClientProvider client={client}>
        <MemoryRouter initialEntries={['/proyectos/nuevo']}>
          <Routes>
            <Route path="/proyectos/nuevo" element={<ProjectCreatePage />} />
            <Route path="/proyectos/:id" element={<p>Ficha del proyecto creado</p>} />
          </Routes>
        </MemoryRouter>
      </QueryClientProvider>,
    );
    await user.click(screen.getByRole('button', { name: 'Registrar proyecto' }));
    expect(
      await screen.findByText('Escribe un título de al menos 8 caracteres.'),
    ).toBeInTheDocument();
    expect(screen.getByLabelText('Título del proyecto *')).toHaveAttribute('aria-invalid', 'true');
    await user.type(
      screen.getByLabelText('Título del proyecto *'),
      'Calidad del aire en la región',
    );
    await user.type(
      screen.getByLabelText('Descripción y objetivo *'),
      'Análisis de contaminantes atmosféricos y sus efectos sobre la calidad de vida en la región.',
    );
    await user.type(screen.getByLabelText('Fecha de inicio *'), '2026-10-01');
    await user.type(screen.getByLabelText('Fecha de cierre *'), '2027-06-01');
    await user.click(screen.getByRole('button', { name: 'Registrar proyecto' }));
    expect(await screen.findByText('Ficha del proyecto creado')).toBeInTheDocument();
    await waitFor(async () => {
      expect(
        (await projectsApi.list()).some(
          (project) => project.title === 'Calidad del aire en la región',
        ),
      ).toBe(true);
    });
    client.clear();
  });
});
