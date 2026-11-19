import type { Role } from '../types';

export type ModuleId =
  | 'auth'
  | 'dashboard'
  | 'proposals'
  | 'evaluations'
  | 'projects'
  | 'activities'
  | 'documents'
  | 'ai-assistant'
  | 'reports'
  | 'admin';
export interface ModuleScreen {
  id: string;
  label: string;
  path: string;
  status: 'INTEGRATED' | 'MOCK_COMPLETE' | 'UI_COMPLETE_BLOCKED' | 'PARTIAL' | 'PENDING';
  roles?: readonly Role[];
}
export interface ModuleDefinition {
  id: ModuleId;
  label: string;
  screens: readonly ModuleScreen[];
}
