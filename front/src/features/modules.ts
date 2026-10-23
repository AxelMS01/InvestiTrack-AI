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

// Architecture catalog only. Planned screens are deliberately not registered as routes.
export const modules: readonly ModuleDefinition[] = [
  authModule,
  dashboardModule,
  proposalsModule,
  evaluationsModule,
  projectsModule,
  activitiesModule,
  documentsModule,
  aiAssistantModule,
  reportsModule,
  adminModule,
];
