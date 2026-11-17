import { describe, expect, it } from 'vitest'
import { answerInputs, canSeal, sealAnswer } from './sealAnswer'
import { deviceHelpOf } from './deviceHelp'
import type { NotificationDto } from '@/types'

const COMMAND = '6f1c0d1e-2a3b-4c5d-8e9f-0a1b2c3d4e5f'
const EC = { name: 'ECDH', namedCurve: 'P-256' } as const
const b64 = (bytes: Uint8Array) => btoa(String.fromCharCode(...bytes)).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
const unb64 = (text: string) => Uint8Array.from(atob(text.replace(/-/g, '+').replace(/_/g, '/') + '='.repeat((4 - (text.length % 4)) % 4)), c => c.charCodeAt(0))

/** What the browser does with a sealed answer: the other side of the scheme. */
async function open(privateKey: CryptoKey, commandId: string, sealed: { epk: string; iv: string; ct: string }) {
  const subtle = crypto.subtle
  const epk = await subtle.importKey('raw', unb64(sealed.epk), EC, false, [])
  const bits = await subtle.deriveBits({ name: 'ECDH', public: epk }, privateKey, 256)
  const hkdf = await subtle.importKey('raw', bits, 'HKDF', false, ['deriveKey'])
  const key = await subtle.deriveKey({ name: 'HKDF', hash: 'SHA-256', salt: new Uint8Array(0), info: new TextEncoder().encode(`jizza answer v1:${commandId}`) },
    hkdf, { name: 'AES-GCM', length: 256 }, false, ['decrypt'])
  const plain = await subtle.decrypt({ name: 'AES-GCM', iv: unb64(sealed.iv), additionalData: new TextEncoder().encode(commandId) }, key, unb64(sealed.ct))
  return new TextDecoder().decode(plain)
}

describe('sealing a secret answer to the browser', () => {
  it('only the browser key opens it, for that command', async () => {
    expect(canSeal()).toBe(true)
    const pair = await crypto.subtle.generateKey(EC, false, ['deriveBits'])
    const key = b64(new Uint8Array(await crypto.subtle.exportKey('raw', pair.publicKey)))
    const sealed = await sealAnswer(key, COMMAND, '481516')
    expect(JSON.stringify(sealed)).not.toContain('481516')
    expect(sealed.epk).toMatch(/^[A-Za-z0-9_-]{87}$/)
    expect(sealed.iv).toMatch(/^[A-Za-z0-9_-]{16}$/)
    expect(await open(pair.privateKey, COMMAND, sealed)).toBe('481516')
    await expect(open(pair.privateKey, 'another-command', sealed)).rejects.toBeTruthy()
  })

  it('sends a sealed answer when the browser gave a key, else the answer', async () => {
    const pair = await crypto.subtle.generateKey(EC, false, ['deriveBits'])
    const key = b64(new Uint8Array(await crypto.subtle.exportKey('raw', pair.publicKey)))
    const sealed = await answerInputs({ commandId: COMMAND, answer: { key } }, 'X7K2P')
    expect('sealed' in sealed && sealed.sealed.v).toBe(1)
    expect(await answerInputs({ commandId: COMMAND, answer: {} }, 'X7K2P')).toEqual({ answer: 'X7K2P' })
  })

  it('keeps the key only for a secret answer, and only a well-formed one', () => {
    const card = (answer: unknown) => ({ id: 'n1', kind: 'PERMISSION_REQUEST', context: { deviceHelp: { deviceId: 'd1', commandId: COMMAND, kind: 'otp', answer } } }) as unknown as NotificationDto
    const key = `B${'A'.repeat(86)}`
    expect(deviceHelpOf(card({ secret: true, required: true, key }))?.answer?.key).toBe(key)
    expect(deviceHelpOf(card({ secret: false, required: true, key }))?.answer?.key).toBeUndefined()
    expect(deviceHelpOf(card({ secret: true, required: true, key: 'short' }))?.answer?.key).toBeUndefined()
  })
})
