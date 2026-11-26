import { expect, test, type Page } from '@playwright/test';
import { randomUUID } from 'node:crypto';

async function login(page: Page, username: string, passwordVariable: string, target?: string) {
  const password = process.env[passwordVariable];
  if (!password) throw new Error(`Falta ${passwordVariable} en .env`);
  await page.goto(target ?? '/login');
  const authorize = page.waitForRequest((request) =>
    new URL(request.url()).pathname.endsWith('/protocol/openid-connect/auth'),
  );
  await page.getByRole('button', { name: 'Iniciar sesión con Keycloak' }).click();
  const params = new URL((await authorize).url()).searchParams;
  expect(params.get('response_type')).toBe('code');
  expect(params.get('code_challenge_method')).toBe('S256');
  expect(params.get('code_challenge')).toHaveLength(43);
  const token = page.waitForResponse(
    (response) =>
      response.url().endsWith('/protocol/openid-connect/token') && response.status() === 200,
  );
  const profile = page.waitForResponse(
    (response) => response.url().endsWith('/api/v1/me') && response.status() === 200,
  );
  await page.locator('#username').fill(username);
  await page.locator('#password').fill(password);
  await page.locator('#kc-login').click();
  await token;
  await profile;
  if (!target) await expect(page.getByRole('heading', { name: /^Panel de / })).toBeVisible();
}

async function logout(page: Page) {
  await page.getByRole('button', { name: 'Cerrar sesión' }).click();
  await expect(page).toHaveURL(/\/login$/);
  await expect(page.getByRole('button', { name: 'Iniciar sesión con Keycloak' })).toBeVisible();
}

test('Keycloak, propuesta, evaluación, aprobación y proyecto generado', async ({ page }) => {
  const title = 'Propuesta navegador ' + randomUUID();
  await login(page, 'investigador', 'LOCAL_RESEARCHER_PASSWORD');
  await page.getByRole('main').getByRole('link', { name: 'Crear propuesta' }).click();
  for (const [name, value] of [
    ['Título', title],
    ['Resumen', 'Seguimiento reproducible de una propuesta ficticia.'],
    ['Problema de investigación', 'Medir tiempos de respuesta del flujo institucional.'],
    ['Objetivo general', 'Observar el recorrido de una propuesta ficticia.'],
    ['Metodología', 'Registrar evidencias en cada etapa del recorrido.'],
    ['Resultados esperados', 'Una descripción reproducible del recorrido.'],
    ['Duración estimada en meses', '3'],
  ])
    await page.getByLabel(name, { exact: true }).fill(value);
  await page.getByLabel('Área de investigación').selectOption('TECNOLOGIA');
  await page.getByRole('button', { name: 'Guardar borrador' }).click();
  await expect(page.getByRole('heading', { name: title })).toBeVisible();
  const proposalPath = new URL(page.url()).pathname;
  await page.getByRole('button', { name: 'Enviar propuesta' }).click();
  await expect(page.getByText('Enviada', { exact: true })).toBeVisible();
  await logout(page);

  await login(page, 'coordinacion', 'LOCAL_COORDINATOR_PASSWORD');
  await page.getByRole('main').getByRole('link', { name: 'Evaluaciones' }).click();
  await page.getByRole('link', { name: title }).click();
  await page
    .getByRole('combobox', { name: 'Evaluador', exact: true })
    .selectOption({ label: 'Evaluador Local' });
  await page.getByRole('button', { name: 'Asignar evaluador' }).click();
  await page.getByRole('button', { name: 'Iniciar evaluación' }).click();
  await expect(page.getByText('En evaluación', { exact: true })).toBeVisible();
  await logout(page);

  await login(page, 'evaluador', 'LOCAL_EVALUATOR_PASSWORD');
  await page.getByRole('main').getByRole('link', { name: 'Evaluaciones' }).click();
  await page.getByRole('link', { name: title }).click();
  const scores = page.getByRole('spinbutton');
  await expect(scores.first()).toBeVisible();
  for (const score of await scores.all()) {
    await score.fill((await score.getAttribute('max'))!);
  }
  await page.getByRole('combobox', { name: 'Dictamen', exact: true }).selectOption('APPROVE');
  await page.getByRole('button', { name: 'Enviar evaluación' }).click();
  await expect(page.getByText('Dictamen enviado.', { exact: true })).toBeVisible();
  await logout(page);

  await login(page, 'coordinacion', 'LOCAL_COORDINATOR_PASSWORD');
  await page.getByRole('main').getByRole('link', { name: 'Evaluaciones' }).click();
  await page.getByRole('link', { name: title }).click();
  const plannedEndDate = new Date(Date.now() + 60 * 86_400_000).toISOString().slice(0, 10);
  await page.getByLabel('Fin previsto del proyecto').fill(plannedEndDate);
  await page.getByRole('button', { name: 'Aprobar propuesta' }).click();
  await expect(page.getByText('Aprobada', { exact: true })).toBeVisible();
  await page
    .getByRole('main')
    .getByRole('link', { name: /proyecto.*generad/i })
    .click();
  await expect(page).toHaveURL(/\/proyectos(?:\/[0-9a-f-]+)?$/);
  const projectLink = page.getByRole('link', { name: title });
  if (new URL(page.url()).pathname === '/proyectos') {
    await expect(projectLink).toBeVisible({ timeout: 90_000 });
    await projectLink.click();
  }
  await expect(page.getByRole('heading', { name: title })).toBeVisible();
  await expect(page.getByText(plannedEndDate)).toBeVisible();
  const projectPath = new URL(page.url()).pathname;
  console.log(`Navegador: ${proposalPath} → ${projectPath}`);
  await logout(page);

  await login(page, 'observador', 'LOCAL_OBSERVER_PASSWORD', projectPath);
  await expect(page.getByRole('heading', { name: title })).toHaveCount(0);
  await expect(page.getByText(/No tienes permiso|No se encontró el recurso/)).toBeVisible();
});
