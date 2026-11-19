import '@testing-library/jest-dom/vitest';
import { cleanup } from '@testing-library/react';
import { afterEach, vi } from 'vitest';
import { transferableAbortController } from 'node:util';

// React Router constructs a Fetch Request. Node's Fetch needs a native AbortSignal.
const controller = transferableAbortController();
vi.stubGlobal('AbortController', controller.constructor);
vi.stubGlobal('AbortSignal', controller.signal.constructor);

afterEach(cleanup);
