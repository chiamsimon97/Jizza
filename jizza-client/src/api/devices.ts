import { api } from '@/lib/api'
import type {
  Device, DeviceActionSpec, DeviceCommand, DeviceEnrollmentToken, DeviceLogLine, DevicePairingCode,
  DevicePairingStatus,
} from '@/types'

const base = (id: string) => `/api/devices/${id}`

/** Computers and phones running the Jizza Agent app: pairing, settings, commands and logs. */
export const devicesApi = {
  list: () => api.get<Device[]>('/api/devices'),
  get: (id: string) => api.get<Device>(base(id)),
  /** Every action device apps build to, for the builder's Device action step. */
  catalog: () => api.get<DeviceActionSpec[]>('/api/devices/catalog'),
  /** Asks for step-up. A six-digit code and QR payload, valid for ten minutes, used once. */
  pair: () => api.post<DevicePairingCode>('/api/devices/pairings'),
  /** Whether an agent has used the key yet. */
  pairing: (pairingId: string) => api.get<DevicePairingStatus>(`/api/devices/pairings/${pairingId}`),
  /** Download links by platform and architecture (windows-x64, macos-arm64, android, ...). */
  installers: () => api.get<Record<string, string>>('/api/devices/installers'),
  update: (id: string, body: { name?: string; tags?: string[]; shellEnabled?: false; blockedActions?: string[]; autoUpdate?: boolean }) =>
    api.patch<Device>(base(id), body),
  /** Asks for step-up: shell widens what the device can be told to do. */
  enableShell: (id: string) => api.post<Device>(`${base(id)}/shell`),
  disable: (id: string) => api.post<Device>(`${base(id)}/disable`),
  enable: (id: string) => api.post<Device>(`${base(id)}/enable`),
  remove: (id: string) => api.delete<void>(base(id)),
  test: (id: string) => api.post<DeviceCommand>(`${base(id)}/test`),
  commands: (id: string) => api.get<DeviceCommand[]>(`${base(id)}/commands?limit=20`),
  cancelCommand: (id: string, commandId: string) =>
    api.post<DeviceCommand>(`${base(id)}/commands/${commandId}/cancel`),
  logs: (id: string) => api.get<DeviceLogLine[]>(`${base(id)}/logs?limit=100`),
  fileUrl: (id: string, fileId: string) => `${base(id)}/files/${fileId}`,
  enrollmentTokens: () => api.get<DeviceEnrollmentToken[]>('/api/devices/enrollment-tokens'),
  /** Asks for step-up. The token is in this answer only. */
  createEnrollmentToken: (body: { name: string; uses: number; validDays: number; tags: string[] }) =>
    api.post<DeviceEnrollmentToken>('/api/devices/enrollment-tokens', body),
  revokeEnrollmentToken: (tokenId: string) => api.delete<void>(`/api/devices/enrollment-tokens/${tokenId}`),
}
