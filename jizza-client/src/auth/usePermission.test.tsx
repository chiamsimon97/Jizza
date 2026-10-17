import { describe, expect, it, beforeEach } from 'vitest'
import { renderHook } from '@testing-library/react'
import { useAuthStore } from '@/store/authStore'
import { makeSnapshot, testQueryClient, wrapperFor } from '@/test/utils'
import { useModuleVisible, usePermission } from './usePermission'

function hook<T>(fn: () => T, snapshot = makeSnapshot()) {
  return renderHook(fn, { wrapper: wrapperFor(testQueryClient(snapshot)) }).result.current
}

describe('usePermission', () => {
  beforeEach(() => {
    useAuthStore.setState({ isAuthenticated: true, userId: 'user-1', enrollmentRequired: false })
  })

  it('is true for a permission in the snapshot', () => {
    expect(hook(() => usePermission('connector_view'))).toBe(true)
  })

  it('is false for a permission the snapshot does not grant', () => {
    expect(hook(() => usePermission('members_view'))).toBe(false)
  })

  it('follows the snapshot for each organization role', () => {
    expect(hook(() => usePermission('members_invite'), makeSnapshot('ORGANIZATION', 'ADMIN'))).toBe(true)
    expect(hook(() => usePermission('members_invite'), makeSnapshot('ORGANIZATION', 'DEVELOPER'))).toBe(false)
    expect(hook(() => usePermission('connector_modify'), makeSnapshot('ORGANIZATION', 'DEVELOPER'))).toBe(true)
    expect(hook(() => usePermission('connector_view'), makeSnapshot('ORGANIZATION', 'USER'))).toBe(false)
  })

  it('never derives grants from the role name', () => {
    const owner = makeSnapshot('ORGANIZATION', 'OWNER', { permissions: ['members_view'] })
    expect(hook(() => usePermission('members_invite'), owner)).toBe(false)
  })

  it('is false while there is no snapshot', () => {
    useAuthStore.setState({ isAuthenticated: false })
    const { result } = renderHook(() => usePermission('connector_view'), { wrapper: wrapperFor(testQueryClient()) })
    expect(result.current).toBe(false)
  })
})

describe('useModuleVisible', () => {
  beforeEach(() => {
    useAuthStore.setState({ isAuthenticated: true, userId: 'user-1', enrollmentRequired: false })
  })

  it('is true when any permission in the module is granted', () => {
    expect(hook(() => useModuleVisible('members'), makeSnapshot('ORGANIZATION', 'USER'))).toBe(true)
    expect(hook(() => useModuleVisible('connector'), makeSnapshot('ORGANIZATION', 'USER'))).toBe(false)
    expect(hook(() => useModuleVisible('members'), makeSnapshot('PERSONAL'))).toBe(false)
  })
})
