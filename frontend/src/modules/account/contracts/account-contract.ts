export type LoginFormValues = {
  phone: string
  code: string
}

export type SendCodeResult = {
  mock?: boolean
  verifyCode?: string
  message?: string
}
