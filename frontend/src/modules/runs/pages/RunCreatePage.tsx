import { useMemo, useState } from 'react'
import { useNavigate, useSearchParams } from 'react-router-dom'
import { useForm } from 'react-hook-form'
import { Play } from 'lucide-react'
import { useRunnableAgents } from '@/modules/agents/queries/agent-queries'
import { useCreateRunMutation } from '@/modules/runs/queries/run-queries'
import { EmptyState, ErrorState, LoadingState, PageShell } from '@/shared/ui/StateViews'
import { isCanonicalError } from '@/shared/contracts/common/canonical-error'

type FormValues = {
  agentId: string
  agentVersion: number
  text: string
}

export function RunCreatePage() {
  const [params] = useSearchParams()
  const navigate = useNavigate()
  const agentsQuery = useRunnableAgents()
  const createMutation = useCreateRunMutation()
  const [formError, setFormError] = useState<string | null>(null)

  const defaultAgentId = params.get('agentId') || ''
  const defaultVersion = Number(params.get('version') || 0)

  const { register, handleSubmit, watch, setValue } = useForm<FormValues>({
    defaultValues: {
      agentId: defaultAgentId,
      agentVersion: defaultVersion || 1,
      text: '',
    },
  })

  const selectedAgentId = watch('agentId')
  const selectedAgent = useMemo(
    () => agentsQuery.data?.items.find((item) => item.id === selectedAgentId || item.code === selectedAgentId),
    [agentsQuery.data, selectedAgentId],
  )

  async function onSubmit(values: FormValues) {
    setFormError(null)
    try {
      const agent = agentsQuery.data?.items.find(
        (item) => item.id === values.agentId || item.code === values.agentId,
      )
      const created = await createMutation.mutateAsync({
        agentId: values.agentId,
        agentVersion: values.agentVersion || agent?.publishedVersion || 1,
        sessionId: `web-${crypto.randomUUID()}`,
        text: values.text,
        responseMode: 'STREAM',
      })
      navigate(`/studio/runs/${created.runId}`, { replace: true })
    } catch (error) {
      setFormError(isCanonicalError(error) ? error.message : '创建 Run 失败')
    }
  }

  if (agentsQuery.isLoading) return <LoadingState label="加载可运行 Agent…" />
  if (agentsQuery.isError) {
    return (
      <ErrorState
        error={isCanonicalError(agentsQuery.error) ? agentsQuery.error : '加载失败'}
        onRetry={() => void agentsQuery.refetch()}
      />
    )
  }
  if (!agentsQuery.data?.items.length) {
    return <EmptyState title="没有可运行 Agent" description="当前 scope 下没有已发布且可运行的 Agent。" />
  }

  return (
    <PageShell title="创建 Agent Run">
      <form className="surface stack" style={{ padding: '1rem' }} onSubmit={handleSubmit(onSubmit)}>
        <label className="field">
          <span>Agent</span>
          <select
            {...register('agentId')}
            onChange={(event) => {
              const agent = agentsQuery.data?.items.find(
                (item) => item.id === event.target.value || item.code === event.target.value,
              )
              setValue('agentId', event.target.value)
              if (agent) setValue('agentVersion', agent.publishedVersion)
            }}
          >
            <option value="" disabled>
              选择 Agent
            </option>
            {agentsQuery.data.items.map((agent) => (
              <option key={agent.id} value={agent.id}>
                {agent.name} ({agent.code}) · v{agent.publishedVersion}
              </option>
            ))}
          </select>
        </label>
        <label className="field">
          <span>版本</span>
          <input
            type="number"
            min={1}
            {...register('agentVersion', { valueAsNumber: true })}
            defaultValue={selectedAgent?.publishedVersion || defaultVersion || 1}
          />
        </label>
        <label className="field">
          <span>输入</span>
          <textarea rows={6} {...register('text', { required: true })} placeholder="输入问题或任务…" />
        </label>
        {formError ? <p className="field-error">{formError}</p> : null}
        <button className="button" type="submit" disabled={createMutation.isPending}>
          <Play aria-hidden="true" size={16} />
          {createMutation.isPending ? '创建中…' : '创建并观察'}
        </button>
      </form>
    </PageShell>
  )
}
