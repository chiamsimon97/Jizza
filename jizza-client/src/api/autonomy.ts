import { api } from '@/lib/api'
import type { AutonomyRuleDto } from '@/types'

export const autonomyApi = {
  rules: () =>
    api.get<AutonomyRuleDto[]>('/api/autonomy/rules'),

  upsertRule: (rule: AutonomyRuleDto) =>
    api.post<AutonomyRuleDto>('/api/autonomy/rules', rule),

  deleteRule: (id: string) =>
    api.delete<void>(`/api/autonomy/rules/${id}`),

  killSwitch: () =>
    api.get<{ enabled: boolean }>('/api/autonomy/kill-switch'),

  setKillSwitch: (enabled: boolean) =>
    api.post<void>('/api/autonomy/kill-switch', { enabled }),
}
