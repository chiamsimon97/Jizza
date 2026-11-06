import { describe, expect, it } from 'vitest'
import { agentKind, detectMacArch, detectOs, hostOf } from './platform'

describe('agent platform helpers', () => {
  it('opens the tab for the browser it runs in', () => {
    expect(detectOs({ userAgent: 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7)' })).toBe('macos')
    expect(detectOs({ userAgent: 'Mozilla/5.0 (Windows NT 10.0; Win64; x64)' })).toBe('windows')
    expect(detectOs({ userAgent: 'Mozilla/5.0 (X11; Linux x86_64)' })).toBe('linux')
    expect(detectOs({ userAgent: 'Mozilla/5.0 (Linux; Android 15; Pixel 8)' })).toBe('android')
  })

  it("tells a Mac's chip from the browser's hints, else from the graphics chip", async () => {
    const hints = (architecture: string) => ({ userAgentData: { getHighEntropyValues: async () => ({ architecture }) } })
    expect(await detectMacArch(hints('arm'), () => null)).toBe('arm64')
    expect(await detectMacArch(hints('x86'), () => null)).toBe('x64')
    expect(await detectMacArch({}, () => 'Apple M2 Pro')).toBe('arm64')
    expect(await detectMacArch({}, () => 'Intel(R) Iris(TM) Plus Graphics')).toBe('x64')
    expect(await detectMacArch({}, () => null)).toBeNull()
  })

  it('names the kind of agent and its host', () => {
    expect(agentKind({ platform: 'MACOS', ownerUserId: 'u' })).toBe('Desktop agent')
    expect(agentKind({ platform: 'ANDROID', ownerUserId: 'u' })).toBe('Phone agent')
    expect(agentKind({ platform: 'LINUX', ownerUserId: null })).toBe('Shared agent')
    expect(hostOf({ hostname: 'chiam-9790', name: 'Work' })).toBe('chiam-9790')
    expect(hostOf({ hostname: null, name: 'Pixel 8' })).toBe('Pixel 8')
  })
})
