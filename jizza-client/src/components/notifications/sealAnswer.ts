/**
 * Seals a secret answer (a CAPTCHA's text, a one-time code) to the browser's one-off key for the
 * command that asked, in this page, so Jizza's server relays it without being able to read it
 * (plan 05, option B). The browser holding the key opens it and types it into the page.
 *
 * Scheme `v: 1`, the same as the browser extension's: ECDH P-256 with a one-off key made here,
 * HKDF-SHA-256 (empty salt, info "jizza answer v1:" + command id) to an AES-256-GCM key, a
 * 12-byte iv, the command id as additional data; binary values base64url without padding.
 */

export interface SealedAnswer {
  v: 1
  epk: string
  iv: string
  ct: string
}

const EC = { name: 'ECDH', namedCurve: 'P-256' } as const

function toBase64Url(bytes: Uint8Array): string {
  let binary = ''
  for (const b of bytes) binary += String.fromCharCode(b)
  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
}

function fromBase64Url(text: string): Uint8Array<ArrayBuffer> {
  const padded = text.replace(/-/g, '+').replace(/_/g, '/') + '='.repeat((4 - (text.length % 4)) % 4)
  const binary = atob(padded)
  const out = new Uint8Array(binary.length)
  for (let i = 0; i < binary.length; i += 1) out[i] = binary.charCodeAt(i)
  return out
}

/** Whether this page can seal: Web Crypto needs HTTPS or localhost. */
export function canSeal(): boolean {
  return typeof globalThis.crypto?.subtle?.deriveBits === 'function'
}

export async function sealAnswer(publicKey: string, commandId: string, answer: string): Promise<SealedAnswer> {
  const subtle = globalThis.crypto.subtle
  const theirs = await subtle.importKey('raw', fromBase64Url(publicKey), EC, false, [])
  const mine = await subtle.generateKey(EC, true, ['deriveBits'])
  const bits = await subtle.deriveBits({ name: 'ECDH', public: theirs }, mine.privateKey, 256)
  const hkdf = await subtle.importKey('raw', bits, 'HKDF', false, ['deriveKey'])
  const key = await subtle.deriveKey(
    { name: 'HKDF', hash: 'SHA-256', salt: new Uint8Array(0), info: new TextEncoder().encode(`jizza answer v1:${commandId}`) },
    hkdf, { name: 'AES-GCM', length: 256 }, false, ['encrypt'])
  const iv = globalThis.crypto.getRandomValues(new Uint8Array(12))
  const ct = await subtle.encrypt(
    { name: 'AES-GCM', iv, additionalData: new TextEncoder().encode(commandId) }, key, new TextEncoder().encode(answer))
  const epk = new Uint8Array(await subtle.exportKey('raw', mine.publicKey))
  return { v: 1, epk: toBase64Url(epk), iv: toBase64Url(iv), ct: toBase64Url(new Uint8Array(ct)) }
}

/**
 * What a device card sends for an answer: sealed when the browser gave a key and this page can
 * seal, else the answer itself (still typed into the page only and kept nowhere).
 */
export async function answerInputs(help: { commandId: string; answer?: { key?: string } }, answer: string):
  Promise<{ sealed: SealedAnswer } | { answer: string }> {
  const key = help.answer?.key
  if (key && canSeal()) return { sealed: await sealAnswer(key, help.commandId, answer) }
  return { answer }
}
