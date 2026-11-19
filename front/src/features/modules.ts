import { coordinationModule } from './coordination/module';
import { evidencesModule } from './evidences/module';
import { authModule } from './auth/module';
import { dashboardModule } from './dashboard/module';
import { proposalsModule } from './proposals/module';
import { evaluationsModule } from './evaluations/module';
import { projectsModule } from './projects/module';
import { activitiesModule } from './activities/module';
import { documentsModule } from './documents/module';
import { aiAssistantModule } from './ai-assistant/module';
import { reportsModule } from './reports/module';
import { adminModule } from './admin/module';
import type { ModuleDefinition } from './module.types';

// El catálogo documenta el estado de cada pantalla. Los permisos se aplican en el router y el adaptador.
export const modules: readonly ModuleDefinition[] = [
  authModule,
  dashboardModule,
  proposalsModule,
  evaluationsModule,
  projectsModule,
  activitiesModule,
  evidencesModule,
  coordinationModule,
  documentsModule,
  aiAssistantModule,
  reportsModule,
  adminModule,
];
