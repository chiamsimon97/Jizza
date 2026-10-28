import { type ReactNode, useEffect, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import QRCode from 'qrcode'
import { Apple, CheckCircle2, Copy, Info, MonitorCog, Smartphone, X } from 'lucide-react'
import { devicesApi } from '@/api/devices'
import { Button } from '@/components/ui/Button'
import { Dialog } from '@/components/ui/Dialog'
import { Input } from '@/components/ui/Input'
import { Spinner } from '@/components/ui/Spinner'
import type { Device, DevicePairingCode } from '@/types'
import {
  type InstallOs, type MacArch, LinuxIcon, WindowsIcon, detectMacArch, detectOs, hostOf,
} from './platform'

function errorText(err: unknown) {
  return err instanceof Error ? err.message : 'That did not work'
}

const TABS: { os: InstallOs; label: string; icon: ReactNode }[] = [
  { os: 'windows', label: 'Windows', icon: <WindowsIcon className="h-5 w-5" /> },
  { os: 'macos', label: 'macOS', icon: <Apple className="h-5 w-5" aria-hidden="true" /> },
  { os: 'linux', label: 'Linux', icon: <LinuxIcon className="h-5 w-5" /> },
  { os: 'android', label: 'Android', icon: <Smartphone className="h-5 w-5" aria-hidden="true" /> },
]

/** The installer links each tab offers, by the keys the server's installer map uses. */
const DOWNLOADS: Record<InstallOs, { key: string; label: string }[]> = {
  windows: [{ key: 'windows-x86', label: '32 bit' }, { key: 'windows-x64', label: '64 bit' }],
  macos: [{ key: 'macos-arm64', label: 'ARM 64 bit (Apple silicon)' }, { key: 'macos-x64', label: 'x86 64 bit (Intel)' }],
  linux: [{ key: 'linux-x64', label: '64 bit' }],
  android: [{ key: 'android', label: 'Jizza Agent for Android' }],
}

const STEPS: Record<InstallOs, ReactNode[]> = {
  windows: [
    'Download the agent installer.',
    'Start the installation by double-clicking the installer.',
    'When the installation is complete, copy the key and paste it into the agent window.',
    <>Once the key is submitted, return to this browser window, then click <b>Next</b>.</>,
  ],
  macos: [
    "Download the agent installer for your Mac's chip.",
    'Open the downloaded file and drag Jizza Agent into Applications.',
    <>Open Jizza Agent. If macOS warns about it, right-click it and choose <b>Open</b>.</>,
    'Copy the key and paste it into the agent window.',
    'Allow Accessibility and Screen Recording when macOS asks, so it can work your apps.',
    <>Once the key is submitted, return to this browser window, then click <b>Next</b>.</>,
  ],
  linux: [
    'Download the agent installer.',
    'Open a terminal in the folder where the installer was downloaded.',
    <>Give the installer permission to run: <b className="font-mono">chmod 755 JizzaAgent_Linux.bin</b></>,
    <>Run <b className="font-mono">./JizzaAgent_Linux.bin</b></>,
    <>Note: the agent is installed in <b className="font-mono">~/JizzaAgent/</b></>,
    'Copy and paste the installation key into the agent window.',
    <>Once the key is submitted, return to this browser window, then click <b>Next</b>.</>,
  ],
  android: [
    'Install the Jizza Agent app on the phone.',
    <>Open it and tap <b>Scan QR code</b>, then scan the code here. Or copy the key to the phone and tap <b>Paste installation key</b>.</>,
    'Allow the permissions it asks for, such as notifications.',
    <>Once it says it is paired, return to this browser window, then click <b>Next</b>.</>,
  ],
}

/**
 * Create agent: pick the operating system, download the Jizza Agent app for it, and hand the app an
 * installation key (a one-time pairing link that also carries this server's address and key
 * fingerprint). The dialog watches the key, so Next opens once the agent has used it, and then the
 * agent can be named.
 */
export function CreateAgentDialog({ open, onClose }: { open: boolean; onClose: () => void }) {
  const [os, setOs] = useState<InstallOs>(() => detectOs())
  const [arch, setArch] = useState<MacArch | null>(null)
  const [code, setCode] = useState<DevicePairingCode | null>(null)
  const [error, setError] = useState('')
  const [attempt, setAttempt] = useState(0)
  const [step, setStep] = useState<'install' | 'name'>('install')

  useEffect(() => {
    if (!open) { setCode(null); setError(''); setStep('install'); return }
    let cancelled = false
    devicesApi.pair()
      .then(c => { if (!cancelled) setCode(c) })
      .catch(e => { if (!cancelled) setError(errorText(e)) })
    return () => { cancelled = true }
  }, [open, attempt])

  useEffect(() => {
    if (open && os === 'macos' && arch === null) detectMacArch().then(setArch).catch(() => setArch(null))
  }, [open, os, arch])

  const installers = useQuery({ queryKey: ['devices', 'installers'], queryFn: devicesApi.installers, enabled: open })
  const status = useQuery({
    queryKey: ['devices', 'pairing', code?.id],
    queryFn: () => devicesApi.pairing(code!.id),
    enabled: open && !!code?.id && step === 'install',
    refetchInterval: q => (q.state.data?.used || q.state.data?.expired ? false : 2000),
  })
  const agent = status.data?.device ?? null
  const expired = status.data?.expired ?? false

  return (
    <Dialog open={open} title="Create agent" onClose={onClose} size="xl" bare>
      <header className="flex items-center justify-between border-b border-gray-100 px-6 py-4 dark:border-gray-800">
        <h2 className="text-lg font-semibold text-gray-900 dark:text-gray-100">Create agent</h2>
        <button type="button" aria-label="Close" onClick={onClose}
          className="rounded-md p-1 text-gray-500 hover:bg-gray-100 hover:text-gray-900 dark:hover:bg-gray-800 dark:hover:text-gray-100">
          <X className="h-5 w-5" />
        </button>
      </header>
      <div className="grid md:grid-cols-[260px_minmax(0,1fr)]">
        <aside className="hidden flex-col items-center gap-3 bg-gray-50 px-6 py-8 text-center md:flex dark:bg-gray-950/40">
          <div className="grid h-24 w-24 place-items-center rounded-full bg-white shadow-sm dark:bg-gray-900">
            <MonitorCog className="h-10 w-10 text-brand-500" aria-hidden="true" />
          </div>
          <h3 className="font-semibold text-gray-900 dark:text-gray-100">Jizza Agent</h3>
          <p className="text-sm text-gray-500">
            The Jizza Agent app lets Jizza run steps on your computer or phone, such as opening apps, filling in
            forms and moving files, so workflows can join your cloud apps with your desktop ones. It only does what
            your guardrails allow.
          </p>
        </aside>
        <div className="flex min-w-0 flex-col">
          {step === 'install' ? (
            <div className="flex flex-col gap-5 px-6 py-6">
              <h3 className="text-base font-semibold text-gray-900 dark:text-gray-100">Install Jizza Agent</h3>
              <div role="tablist" aria-label="Operating system" className="flex flex-wrap gap-1 border-b border-gray-200 dark:border-gray-800">
                {TABS.map(t => (
                  <button key={t.os} type="button" role="tab" aria-selected={os === t.os} onClick={() => setOs(t.os)}
                    className={`-mb-px flex items-center gap-2 border-b-2 px-3 py-2 text-sm ${os === t.os
                      ? 'border-brand-500 font-medium text-brand-600 dark:text-brand-400'
                      : 'border-transparent text-gray-600 hover:text-gray-900 dark:text-gray-400 dark:hover:text-gray-100'}`}>
                    {t.icon}{t.label}
                  </button>
                ))}
              </div>

              <section>
                <h4 className="text-sm font-semibold text-gray-900 dark:text-gray-100">Set up agent</h4>
                <Downloads os={os} links={installers.data} loading={installers.isLoading} />
                {os === 'macos' && arch && (
                  <p className="mt-1 text-sm text-gray-600 dark:text-gray-300">
                    Note: your current Mac is running on {arch === 'arm64' ? 'ARM 64 bit (Apple silicon)' : 'x86 64 bit (Intel)'}.
                  </p>
                )}
              </section>

              <section>
                <h4 className="text-sm font-semibold text-gray-900 dark:text-gray-100">Installation key</h4>
                <InstallationKey code={code} error={error} expired={expired} showQr={os === 'android'}
                  onRenew={() => { setCode(null); setError(''); setAttempt(a => a + 1) }} />
              </section>

              <section>
                <h4 className="text-sm font-semibold text-gray-900 dark:text-gray-100">How to set up the agent:</h4>
                <ul className="mt-2 flex flex-col gap-2">
                  {STEPS[os].map((s, i) => (
                    <li key={i} className="flex gap-3 text-sm text-gray-700 dark:text-gray-300">
                      <span className="mt-1.5 h-1.5 w-1.5 shrink-0 rounded-full bg-brand-500" aria-hidden="true" />
                      <span>{s}</span>
                    </li>
                  ))}
                </ul>
              </section>

              {agent && (
                <p role="status" className="flex items-center gap-2 rounded-lg bg-green-50 px-3 py-2 text-sm text-green-800 dark:bg-green-950/40 dark:text-green-300">
                  <CheckCircle2 className="h-4 w-4" />
                  {agent.name} on {hostOf(agent)} is connected. Click Next to finish.
                </p>
              )}
            </div>
          ) : agent && <NameAgent agent={agent} onDone={onClose} />}

          {step === 'install' && (
            <footer className="mt-auto flex flex-wrap items-center justify-end gap-2 border-t border-gray-100 px-6 py-4 dark:border-gray-800">
              {!agent && code && !expired && <span className="mr-auto flex items-center gap-2 text-xs text-gray-500"><Spinner />Waiting for the agent to use the key…</span>}
              <Button variant="secondary" onClick={onClose}>Cancel</Button>
              <Button disabled={!agent} onClick={() => setStep('name')}>Next</Button>
            </footer>
          )}
        </div>
      </div>
    </Dialog>
  )
}

function Downloads({ os, links, loading }: { os: InstallOs; links?: Record<string, string>; loading: boolean }) {
  if (loading) return <p className="mt-1 text-sm text-gray-500">Looking up the installers…</p>
  const options = DOWNLOADS[os]
  const available = options.filter(o => links?.[o.key])
  return (
    <div className="mt-1 text-sm text-gray-700 dark:text-gray-300">
      {os === 'macos' ? 'Download and run the installer: '
        : os === 'android' ? 'Install the app: '
        : "Download and run the installer for your system's architecture: "}
      {options.map((o, i) => (
        <span key={o.key}>
          {i > 0 && <span className="mx-1 text-gray-400">|</span>}
          {links?.[o.key]
            ? <a href={links[o.key]} target="_blank" rel="noreferrer" className="text-brand-600 underline hover:text-brand-700 dark:text-brand-400">{o.label}</a>
            : <span className="text-gray-400">{o.label}</span>}
        </span>
      ))}
      {available.length === 0 && (
        <p className="mt-1 text-xs text-amber-700 dark:text-amber-300">
          The {os === 'android' ? 'Android' : 'desktop'} installer is not published yet. Your server admin can set its link with
          {' '}<code className="font-mono">jizza.devices.installers.{options[0].key}</code>.
        </p>
      )}
      {os === 'android' && available.length > 0 && (
        <p className="mt-1 text-xs text-gray-500">Until there is a store release, this opens the app's builds, where the latest debug APK can be downloaded.</p>
      )}
    </div>
  )
}

function InstallationKey({ code, error, expired, showQr, onRenew }: {
  code: DevicePairingCode | null; error: string; expired: boolean; showQr: boolean; onRenew: () => void
}) {
  const [copied, setCopied] = useState(false)
  const [qr, setQr] = useState('')
  const [now, setNow] = useState(Date.now())

  useEffect(() => {
    if (!code) { setQr(''); return }
    QRCode.toDataURL(code.qrPayload, { margin: 1, width: 180, errorCorrectionLevel: 'M' })
      .then(setQr).catch(() => setQr(''))
    const t = window.setInterval(() => setNow(Date.now()), 1000)
    return () => window.clearInterval(t)
  }, [code])

  const left = code ? Math.max(0, Math.round((new Date(code.expiresAt).getTime() - now) / 1000)) : 0
  const gone = expired || (code !== null && left === 0)

  function copy() {
    if (!code) return
    navigator.clipboard?.writeText(code.qrPayload)
      .then(() => { setCopied(true); window.setTimeout(() => setCopied(false), 2000) })
      .catch(() => setCopied(false))
  }

  return (
    <div className="mt-2 rounded-lg border border-blue-200 bg-blue-50/70 p-4 dark:border-blue-900 dark:bg-blue-950/30">
      <p className="flex items-start gap-2 text-sm text-gray-800 dark:text-gray-200">
        <Info className="mt-0.5 h-4 w-4 shrink-0 text-blue-600" aria-hidden="true" />
        Copy the key and paste it into the agent's window when it asks for one.
      </p>
      {error && <p className="mt-2 text-sm text-red-600" role="alert">{error}</p>}
      {!code && !error && <div className="mt-3"><Spinner /></div>}
      {code && (
        <>
          <div className="mt-3 flex flex-col gap-2 sm:flex-row">
            <input readOnly value={code.qrPayload} aria-label="Installation key" onFocus={e => e.currentTarget.select()}
              className="min-w-0 flex-1 rounded-md border border-gray-300 bg-white px-3 py-2 font-mono text-xs text-gray-800 dark:border-gray-700 dark:bg-gray-900 dark:text-gray-200" />
            <Button onClick={copy} disabled={gone}><Copy className="h-4 w-4" />{copied ? 'Copied' : 'Copy'}</Button>
          </div>
          <p className="mt-2 text-sm text-red-600">Don't share this key with others.</p>
          <p className="mt-1 text-xs text-gray-500">
            {gone
              ? <>This key has expired. <button type="button" className="text-brand-600 underline" onClick={onRenew}>Get a new key</button></>
              : <>It works once, for {Math.floor(left / 60)}:{String(left % 60).padStart(2, '0')} more. To type it instead, the code is <span className="font-mono font-medium text-gray-800 dark:text-gray-200" aria-label="Pairing code">{code.code}</span>.</>}
          </p>
          {showQr && qr && !gone && (
            <img src={qr} alt="Installation key as a QR code" className="mt-3 h-44 w-44 rounded-lg bg-white p-1" />
          )}
        </>
      )}
    </div>
  )
}

function NameAgent({ agent, onDone }: { agent: Device; onDone: () => void }) {
  const [name, setName] = useState(agent.name)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState('')

  async function save() {
    setSaving(true); setError('')
    try {
      if (name.trim() && name.trim() !== agent.name) await devicesApi.update(agent.id, { name: name.trim() })
      onDone()
    } catch (e) {
      setError(errorText(e))
    } finally {
      setSaving(false)
    }
  }

  return (
    <>
      <div className="flex flex-col gap-4 px-6 py-6">
        <h3 className="text-base font-semibold text-gray-900 dark:text-gray-100">Name your agent</h3>
        <p className="text-sm text-gray-600 dark:text-gray-300">
          It connected from <b>{hostOf(agent)}</b>. Give it a name you will recognise in workflows.
        </p>
        <Input id="new-agent-name" label="Agent name" value={name} maxLength={80} onChange={e => setName(e.target.value)} />
        {error && <p className="text-sm text-red-600" role="alert">{error}</p>}
      </div>
      <footer className="mt-auto flex justify-end gap-2 border-t border-gray-100 px-6 py-4 dark:border-gray-800">
        <Button loading={saving} disabled={!name.trim()} onClick={save}>Done</Button>
      </footer>
    </>
  )
}
