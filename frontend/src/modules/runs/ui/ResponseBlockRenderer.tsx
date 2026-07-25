import DOMPurify from 'dompurify'
import type { ResponseBlock } from '@/modules/runs/contracts/run-contract'

function sanitizeMarkdown(markdown: string): string {
  // Keep renderer dependency-light: treat markdown as sanitized HTML-ish text.
  // Unknown HTML is stripped by DOMPurify; full markdown parsing can be added later.
  const escaped = markdown
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll(/\n/g, '<br />')
  return DOMPurify.sanitize(escaped)
}

export function ResponseBlockRenderer({
  blocks,
  fallbackReason,
}: {
  blocks: ResponseBlock[]
  fallbackReason?: string | null
}) {
  return (
    <div className="stack">
      {fallbackReason ? (
        <div className="state-view" role="status">
          <strong>结果已降级</strong>
          <p>{fallbackReason}</p>
        </div>
      ) : null}
      {blocks.length === 0 ? <p>暂无输出块。</p> : null}
      {blocks.map((block, index) => (
        <article key={`${block.type}-${index}`} className="surface" style={{ padding: '1rem' }}>
          <div className="cluster" style={{ marginBottom: '0.75rem' }}>
            <span className="badge">{block.type}</span>
          </div>
          <BlockBody block={block} />
        </article>
      ))}
    </div>
  )
}

function BlockBody({ block }: { block: ResponseBlock }) {
  if (block.type === 'TEXT') {
    return <p style={{ whiteSpace: 'pre-wrap' }}>{String(block.text ?? '')}</p>
  }
  if (block.type === 'MARKDOWN') {
    return (
      <div
        dangerouslySetInnerHTML={{
          __html: sanitizeMarkdown(String(block.markdown ?? '')),
        }}
      />
    )
  }
  if (block.type === 'TABLE') {
    const columns = Array.isArray(block.columns) ? block.columns.map(String) : []
    const rows = Array.isArray(block.rows)
      ? block.rows.map((row) => (Array.isArray(row) ? row.map((cell) => (cell == null ? '' : String(cell))) : []))
      : []
    return (
      <div style={{ overflowX: 'auto' }}>
        <table className="table">
          <thead>
            <tr>
              {columns.map((column) => (
                <th key={column}>{column}</th>
              ))}
            </tr>
          </thead>
          <tbody>
            {rows.map((row, rowIndex) => (
              <tr key={rowIndex}>
                {row.map((cell, cellIndex) => (
                  <td key={cellIndex}>{cell}</td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    )
  }
  if (block.type === 'CARD') {
    const fields = block.fields && typeof block.fields === 'object' ? block.fields : undefined
    return (
      <div className="stack">
        {block.title ? <h3>{String(block.title)}</h3> : null}
        {block.body ? <p>{String(block.body)}</p> : null}
        {fields
          ? Object.entries(fields).map(([key, value]) => (
              <div key={key}>
                <strong>{key}</strong>: {String(value)}
              </div>
            ))
          : null}
      </div>
    )
  }
  if (block.type === 'CITATION') {
    return (
      <div className="stack">
        {block.title ? <strong>{String(block.title)}</strong> : null}
        {block.snippet ? <p>{String(block.snippet)}</p> : null}
        {block.uri ? (
          <a href={String(block.uri)} target="_blank" rel="noreferrer noopener">
            {String(block.uri)}
          </a>
        ) : null}
      </div>
    )
  }
  if (block.type === 'FILE') {
    return (
      <div className="stack">
        <p>{block.name ? String(block.name) : '文件'}</p>
        {block.uri ? (
          <a href={String(block.uri)} target="_blank" rel="noreferrer noopener">
            打开/下载
          </a>
        ) : (
          <p>文件需要通过鉴权下载通道获取，未提供公开 URI。</p>
        )}
      </div>
    )
  }
  if (block.type === 'IMAGE') {
    return block.uri ? (
      <img src={String(block.uri)} alt={block.alt ? String(block.alt) : 'image'} />
    ) : (
      <p>图片地址不可用。</p>
    )
  }
  if (block.type === 'FORM') {
    return (
      <div className="stack">
        <p>表单块需要通过受控 schema 提交，当前仅展示字段定义。</p>
        <pre style={{ whiteSpace: 'pre-wrap' }}>{JSON.stringify(block.fields ?? [], null, 2)}</pre>
      </div>
    )
  }
  if (block.type === 'PROGRESS') {
    const percent = typeof block.percent === 'number' ? block.percent : 0
    return (
      <div className="stack">
        <p>{block.label ? String(block.label) : '进度'}</p>
        <progress max={100} value={percent} />
      </div>
    )
  }
  if (block.type === 'WARNING') {
    return <p role="status">{String(block.message ?? 'Warning')}</p>
  }
  return (
    <pre style={{ whiteSpace: 'pre-wrap', overflowX: 'auto' }}>{JSON.stringify(block, null, 2)}</pre>
  )
}
