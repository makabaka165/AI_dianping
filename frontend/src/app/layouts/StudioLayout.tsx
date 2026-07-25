import { Outlet } from 'react-router-dom'
import { ShellChrome } from '@/app/layouts/ShellChrome'

export function StudioLayout() {
  return (
    <div className="studio-shell">
      <ShellChrome
        brand="AI Studio"
        navItems={[
          { to: '/studio/agents', label: '可运行 Agent', permission: 'AGENT_RUN' },
          { to: '/studio/runs', label: 'Run 历史', permission: 'AGENT_RUN' },
          { to: '/studio/prompts', label: 'Prompt', permission: 'PROMPT_MANAGE' },
          { to: '/studio/models', label: '模型', permission: 'MODEL_MANAGE' },
          { to: '/studio/knowledge', label: '知识库', permission: 'KNOWLEDGE_READ' },
          { to: '/studio/approvals', label: '审批', permission: 'TOOL_APPROVE' },
        ]}
      >
        <Outlet />
      </ShellChrome>
    </div>
  )
}
