import type { RouteObject } from 'react-router-dom'
import { StudioLayout } from '@/app/layouts/StudioLayout'
import { RequirePermission } from '@/app/routes/guards/RequirePermission'
import { RequireScope } from '@/app/routes/guards/RequireScope'
import { RequireSession } from '@/app/routes/guards/RequireSession'
import { RoutePlaceholder } from '@/app/routes/RoutePlaceholder'
import { AgentListPage } from '@/modules/agents'
import { RunCreatePage, RunDetailPage, RunListPage } from '@/modules/runs'

export const studioRoutes: RouteObject = {
  path: '/studio',
  element: (
    <RequireSession>
      <RequireScope>
        <StudioLayout />
      </RequireScope>
    </RequireSession>
  ),
  children: [
    {
      path: 'agents',
      element: (
        <RequirePermission permission="AGENT_RUN">
          <AgentListPage />
        </RequirePermission>
      ),
    },
    {
      path: 'runs',
      element: (
        <RequirePermission permission="AGENT_RUN">
          <RunListPage />
        </RequirePermission>
      ),
    },
    {
      path: 'runs/new',
      element: (
        <RequirePermission permission="AGENT_RUN">
          <RunCreatePage />
        </RequirePermission>
      ),
    },
    {
      path: 'runs/:runId',
      element: (
        <RequirePermission permission="AGENT_RUN">
          <RunDetailPage />
        </RequirePermission>
      ),
    },
    {
      path: 'prompts',
      element: (
        <RoutePlaceholder title="Prompt" description="该模块暂未开放。" />
      ),
    },
    {
      path: 'models',
      element: (
        <RoutePlaceholder title="模型" description="该模块暂未开放。" />
      ),
    },
    {
      path: 'knowledge',
      element: (
        <RoutePlaceholder title="知识库" description="该模块暂未开放。" />
      ),
    },
    {
      path: 'approvals',
      element: (
        <RoutePlaceholder title="审批" description="该模块暂未开放。" />
      ),
    },
  ],
}
