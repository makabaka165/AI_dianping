import { EmptyState, PageShell } from '@/shared/ui/StateViews'

type RoutePlaceholderProps = {
  title: string
  description: string
}

export function RoutePlaceholder({ title, description }: RoutePlaceholderProps) {
  return (
    <PageShell title={title}>
      <EmptyState title="暂无内容" description={description} />
    </PageShell>
  )
}
