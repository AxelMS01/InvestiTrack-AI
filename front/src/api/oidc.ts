import { z } from 'zod';

const clientId = 'investitrack-web';
const transactionKey = 'investitrack.oidc';
const tokensSchema = z.object({
  access_token: z.string().min(1),
  id_token: z.string().min(1).optional(),
  refresh_token: z.string().min(1),
  expires_in: z.number().positive(),
});
type Session = z.infer<typeof tokensSchema> & { expiresAt: number };
let session: Session | undefined;
let refresh: Promise<void> | undefined;
let timer: number | undefined;

function endpoint(action: string) {
  const issuer = import.meta.env.VITE_OIDC_ISSUER || '/realms/investitrack';
  return `${issuer.replace(/\/$/, '')}/protocol/openid-connect/${action}`;
}

function base64url(bytes: Uint8Array) {
  return btoa(String.fromCharCode(...bytes))
    .replace(/\+/g, '-')
    .replace(/\//g, '_')
    .replace(/=+$/, '');
}

function redirectUri() {
  return new URL('/auth/callback', location.origin).href;
}

export async function beginLogin(from: string) {
  const state = base64url(crypto.getRandomValues(new Uint8Array(32)));
  const verifier = base64url(crypto.getRandomValues(new Uint8Array(32)));
  const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(verifier));
  sessionStorage.setItem(transactionKey, JSON.stringify({ state, verifier, from }));
  const url = new URL(endpoint('auth'), location.origin);
  url.search = new URLSearchParams({
    response_type: 'code',
    response_mode: 'query',
    client_id: clientId,
    redirect_uri: redirectUri(),
    scope: 'openid',
    state,
    code_challenge_method: 'S256',
    code_challenge: base64url(new Uint8Array(digest)),
  }).toString();
  location.assign(url);
}

async function tokenRequest(params: Record<string, string>) {
  const response = await fetch(endpoint('token'), {
    method: 'POST',
    credentials: 'omit',
    body: new URLSearchParams({ client_id: clientId, ...params }),
    signal: AbortSignal.timeout(10000),
  });
  if (!response.ok) throw new Error('Keycloak rechazó la sesión. Inicia sesión de nuevo.');
  const tokens = tokensSchema.parse(await response.json());
  session = {
    ...tokens,
    id_token: tokens.id_token ?? session?.id_token,
    expiresAt: Date.now() + tokens.expires_in * 1000,
  };
  window.clearTimeout(timer);
  timer = window.setTimeout(
    () => {
      void accessToken().catch(() => undefined);
    },
    Math.max(1000, tokens.expires_in * 1000 - 20000),
  );
}

export async function completeLogin() {
  const params = new URLSearchParams(location.search);
  const raw = sessionStorage.getItem(transactionKey);
  sessionStorage.removeItem(transactionKey);
  history.replaceState(history.state, '', location.pathname);
  const pending =
    raw &&
    z.object({ state: z.string(), verifier: z.string(), from: z.string() }).parse(JSON.parse(raw));
  const code = params.get('code');
  if (!pending || params.get('state') !== pending.state || !code || params.has('error')) {
    throw new Error('No se pudo verificar la respuesta de inicio de sesión.');
  }
  await tokenRequest({
    grant_type: 'authorization_code',
    code,
    code_verifier: pending.verifier,
    redirect_uri: redirectUri(),
  });
  const target = new URL(pending.from, location.origin);
  return target.origin === location.origin && !target.pathname.startsWith('/auth/')
    ? target.pathname + target.search
    : '/';
}

export const sessionExpiredEvent = 'investitrack:session-expired';

export function expireSession() {
  if (!session) return;
  session = undefined;
  window.clearTimeout(timer);
  window.dispatchEvent(new Event(sessionExpiredEvent));
}

export async function accessToken() {
  if (!session) return undefined;
  if (session.expiresAt <= Date.now() + 21000) {
    refresh ??= tokenRequest({ grant_type: 'refresh_token', refresh_token: session.refresh_token })
      .catch((error: unknown) => {
        expireSession();
        throw error;
      })
      .finally(() => {
        refresh = undefined;
      });
    await refresh;
  }
  return session?.access_token;
}

export async function endSession() {
  if (refresh) await refresh.catch(() => undefined);
  const hint = session?.id_token;
  session = undefined;
  window.clearTimeout(timer);
  sessionStorage.removeItem(transactionKey);
  const url = new URL(endpoint('logout'), location.origin);
  url.search = new URLSearchParams({
    client_id: clientId,
    post_logout_redirect_uri: new URL('/login', location.origin).href,
    ...(hint ? { id_token_hint: hint } : {}),
  }).toString();
  location.assign(url);
}
