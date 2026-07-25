import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { useNavigate, useLocation } from 'react-router-dom'
import { LogIn, Send } from 'lucide-react'
import { z } from 'zod'
import { useSession } from '@/app/providers/session-context'
import { createAccountAdapter } from '@/modules/account/adapters/http-account-adapter'
import type { LoginFormValues } from '@/modules/account/contracts/account-contract'
import { isCanonicalError } from '@/shared/contracts/common/canonical-error'

const schema = z.object({
  phone: z.string().regex(/^1\d{10}$/, '请输入 11 位手机号'),
  code: z.string().min(4, '请输入验证码'),
})

export function LoginPage() {
  const { http, loginWithToken } = useSession()
  const adapter = createAccountAdapter(http)
  const navigate = useNavigate()
  const location = useLocation()
  const [debugCode, setDebugCode] = useState<string | null>(null)
  const [formError, setFormError] = useState<string | null>(null)
  const [sending, setSending] = useState(false)
  const {
    register,
    handleSubmit,
    getValues,
    setError,
    formState: { errors, isSubmitting },
  } = useForm<LoginFormValues>({
    defaultValues: { phone: '', code: '' },
  })

  async function onSendCode() {
    setFormError(null)
    const phone = getValues('phone')
    const parsed = schema.shape.phone.safeParse(phone)
    if (!parsed.success) {
      setError('phone', { message: parsed.error.issues[0]?.message || '手机号无效' })
      return
    }
    setSending(true)
    try {
      const result = await adapter.sendCode(phone)
      if (result && typeof result === 'object' && 'verifyCode' in result && result.verifyCode) {
        setDebugCode(String(result.verifyCode))
      } else {
        setDebugCode(null)
      }
    } catch (error) {
      setFormError(isCanonicalError(error) ? error.message : '发送验证码失败')
    } finally {
      setSending(false)
    }
  }

  async function onSubmit(values: LoginFormValues) {
    setFormError(null)
    const parsed = schema.safeParse(values)
    if (!parsed.success) {
      for (const issue of parsed.error.issues) {
        const field = issue.path[0]
        if (field === 'phone' || field === 'code') {
          setError(field, { message: issue.message })
        }
      }
      return
    }
    try {
      const token = await adapter.login(parsed.data)
      await loginWithToken(token)
      const redirectTo =
        typeof location.state === 'object' &&
        location.state &&
        'from' in location.state &&
        typeof (location.state as { from?: unknown }).from === 'string'
          ? (location.state as { from: string }).from
          : '/'
      navigate(redirectTo, { replace: true })
    } catch (error) {
      setFormError(isCanonicalError(error) ? error.message : '登录失败')
    }
  }

  return (
    <div className="auth-page">
      <form className="auth-card surface" onSubmit={handleSubmit(onSubmit)} noValidate>
        <div className="stack">
          <h1>登录 AI 点评</h1>
          <p>使用手机号和验证码登录。</p>
        </div>
        <label className="field">
          <span>手机号</span>
          <input {...register('phone')} inputMode="numeric" autoComplete="tel" />
          {errors.phone ? <span className="field-error">{errors.phone.message}</span> : null}
        </label>
        <label className="field">
          <span>验证码</span>
          <div className="cluster">
            <input {...register('code')} inputMode="numeric" autoComplete="one-time-code" />
            <button type="button" className="button secondary" disabled={sending} onClick={() => void onSendCode()}>
              <Send aria-hidden="true" size={16} />
              {sending ? '发送中…' : '发送验证码'}
            </button>
          </div>
          {errors.code ? <span className="field-error">{errors.code.message}</span> : null}
          {debugCode ? (
            <span className="badge" data-testid="debug-verification-code">
              调试验证码：{debugCode}
            </span>
          ) : null}
        </label>
        {formError ? <p className="field-error">{formError}</p> : null}
        <button className="button" type="submit" disabled={isSubmitting}>
          <LogIn aria-hidden="true" size={16} />
          {isSubmitting ? '登录中…' : '登录'}
        </button>
      </form>
    </div>
  )
}
