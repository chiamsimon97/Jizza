import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Link } from 'react-router-dom'
import { KeyRound, Mail, RefreshCw, Trash2 } from 'lucide-react'
import { financeApi } from '@/api/finance'
import { Button } from '@/components/ui/Button'
import type { FinAccount, FinPerson, MailCheckResult, MailKind, MailSource, MailSourceInput, MailStatus } from '@/types/finance'
import { formatDay } from '../components/AsOf'
import { SelectField, TextField } from '../components/Fields'
import { Panel, Pill } from '../components/Panel'
import { errorText } from '../data'
import { financeKeys } from '../queryKeys'
import { accountLabel } from './Connect'

const BANK_KINDS = ['savings', 'current', 'credit_card', 'wallet', 'deposit', 'other']

const STATUS: Record<MailStatus, { label: string; tone: 'ok' | 'info' | 'warn' | 'bad' }> = {
  to_review: { label: 'To review', tone: 'info' },
  nothing_new: { label: 'Up to date', tone: 'ok' },
  needs_sign_in: { label: 'Sign in needed', tone: 'warn' },
  needs_password: { label: 'Password needed', tone: 'warn' },
  failed: { label: 'Failed', tone: 'bad' },
}

/**
 * Statements from email (Kai part B): a mail connector such as Gmail, the sender whose mails carry
 * the statement, and where it goes. Kai only searches and reads mail, finds the PDF, opens it with
 * the saved password and brings each new statement back as an import to review.
 */
export function MailSources({ accounts, people, onReview }: {
  accounts: FinAccount[]
  people: FinPerson[]
  onReview: (batchId: string, kind: MailKind) => void
}) {
  const qc = useQueryClient()
  const view = useQuery({ queryKey: financeKeys.mailSources, queryFn: financeApi.mailSources })
  const bankAccounts = accounts.filter(a => a.status === 'active' && BANK_KINDS.includes(a.kind))
  const active = people.filter(p => p.status === 'active')
  const [kind, setKind] = useState<MailKind>('cas')
  const [serverId, setServerId] = useState('')
  const [personId, setPersonId] = useState('')
  const [accountId, setAccountId] = useState('')
  const [sender, setSender] = useState('')
  const [subject, setSubject] = useState('')
  const [password, setPassword] = useState('')
  const [editing, setEditing] = useState<string | null>(null)
  const [newPassword, setNewPassword] = useState('')
  const [results, setResults] = useState<Record<string, MailCheckResult>>({})
  const refresh = () => qc.invalidateQueries({ queryKey: financeKeys.mailSources })

  const link = useMutation({
    mutationFn: (body: MailSourceInput) => financeApi.linkMailSource(body),
    onSuccess: () => { setSender(''); setSubject(''); setPassword(''); refresh() },
  })
  const check = useMutation({
    mutationFn: (s: MailSource) => financeApi.checkMailSource(s.id),
    onSuccess: (r, s) => {
      setResults(prev => ({ ...prev, [s.id]: r }))
      qc.invalidateQueries({ queryKey: financeKeys.imports })
      refresh()
      if (r.status === 'to_review' && r.batchId) onReview(r.batchId, s.kind)
    },
  })
  const change = useMutation({
    mutationFn: (v: { id: string; password?: string; enabled?: boolean }) =>
      financeApi.changeMailSource(v.id, { password: v.password, enabled: v.enabled }),
    onSuccess: () => { setEditing(null); setNewPassword(''); refresh() },
  })
  const unlink = useMutation({ mutationFn: (id: string) => financeApi.unlinkMailSource(id), onSuccess: refresh })

  if (!view.data) return null
  const { sources, servers, casSenders } = view.data
  const chosenServer = serverId || servers[0]?.serverId || ''
  const chosenPerson = personId || active.find(p => p.kind === 'self')?.id || active[0]?.id || ''
  const chosenAccount = accountId || bankAccounts[0]?.id || ''
  const whose = (s: MailSource) => {
    if (s.kind === 'bank') {
      const a = accounts.find(x => x.id === s.accountId)
      return a ? accountLabel(a) : s.accountName ?? 'Bank account'
    }
    const p = people.find(x => x.id === s.personId)
    return !p || p.kind === 'self' ? 'your funds' : `${p.displayName}'s funds`
  }
  const ready = !!chosenServer && (kind === 'cas' ? !!chosenPerson : !!chosenAccount && sender.trim() !== '')

  return (
    <Panel title={<><Mail className="h-4 w-4 text-brand-600 dark:text-brand-300" aria-hidden />Statements from your email</>}>
      <p className="text-sm text-gray-600 dark:text-gray-400">
        Kai looks in your mailbox, through a mail connector such as Gmail, for the CAS and bank statements that get mailed to you each month.
        It only searches and reads mail, opens the PDF with the password you save here, and brings each new statement back as an import you review.
        It checks every morning, or when you press Check now.
      </p>

      {sources.length > 0 && (
        <ul className="flex flex-col divide-y divide-gray-100 dark:divide-gray-800" aria-label="Email sources">
          {sources.map(s => {
            const st = s.lastStatus ? STATUS[s.lastStatus] : null
            const r = results[s.id]
            return (
              <li key={s.id} className="flex flex-col gap-1.5 py-2.5">
                <div className="flex flex-wrap items-center gap-2">
                  <Mail className="h-4 w-4 text-gray-400" aria-hidden />
                  <span className="text-sm font-medium text-gray-900 dark:text-gray-100">{s.kind === 'cas' ? 'CAS' : 'Bank statement'}</span>
                  <span className="text-sm text-gray-500 dark:text-gray-400">from {s.sender.split(' ').join(', ')} for {whose(s)}</span>
                  {!s.enabled && <Pill>Paused</Pill>}
                  {s.enabled && !s.signedIn && <Pill tone="warn">Sign in needed</Pill>}
                  {!s.hasPassword && <Pill>No password saved</Pill>}
                  {st && <Pill tone={st.tone}>{st.label}</Pill>}
                  {s.lastCheckedAt && <span className="text-xs text-gray-500 dark:text-gray-400">Checked {formatDay(s.lastCheckedAt)}</span>}
                  <span className="flex-1" />
                  {s.lastStatus === 'to_review' && s.lastBatchId && (
                    <Button size="sm" variant="secondary" onClick={() => onReview(s.lastBatchId!, s.kind)}>Review</Button>
                  )}
                  <Button size="sm" loading={check.isPending && check.variables?.id === s.id} disabled={!s.enabled} onClick={() => check.mutate(s)}>
                    <RefreshCw className="h-3.5 w-3.5" aria-hidden />Check now
                  </Button>
                  <Button size="sm" variant="ghost" onClick={() => { setEditing(editing === s.id ? null : s.id); setNewPassword('') }}>
                    <KeyRound className="h-3.5 w-3.5" aria-hidden />{s.hasPassword ? 'Change password' : 'Add password'}
                  </Button>
                  <Button size="sm" variant="ghost" loading={change.isPending && change.variables?.id === s.id && change.variables.enabled !== undefined}
                    onClick={() => change.mutate({ id: s.id, enabled: !s.enabled })}>{s.enabled ? 'Pause' : 'Resume'}</Button>
                  <Button size="sm" variant="ghost" aria-label={`Remove the ${s.kind === 'cas' ? 'CAS' : 'bank statement'} source`}
                    loading={unlink.isPending && unlink.variables === s.id} onClick={() => unlink.mutate(s.id)}>
                    <Trash2 className="h-3.5 w-3.5" aria-hidden />
                  </Button>
                </div>
                {editing === s.id && (
                  <div className="flex flex-wrap items-end gap-2">
                    <TextField label="PDF password" type="password" value={newPassword} onChange={setNewPassword} maxLength={100} className="min-w-[14rem]"
                      hint="Kept encrypted, used only to open these statements, never shown again." />
                    <Button size="sm" loading={change.isPending && change.variables?.password !== undefined} disabled={!newPassword}
                      onClick={() => change.mutate({ id: s.id, password: newPassword })}>Save password</Button>
                  </div>
                )}
                {(r || (s.enabled && !s.signedIn)) && (
                  <p role="status" className="text-xs text-gray-600 dark:text-gray-300">
                    {r ? r.message : `Sign in to ${s.serverName} on Connectors, then check.`}
                    {(r?.status === 'needs_sign_in' || (!r && !s.signedIn)) && (
                      <> <Link to={`/connectors/mcp/${s.serverId}`} className="font-medium text-brand-600 hover:underline dark:text-brand-300">Open {s.serverName}</Link></>
                    )}
                  </p>
                )}
              </li>
            )
          })}
        </ul>
      )}

      {servers.length === 0 ? (
        <p className="text-sm text-gray-600 dark:text-gray-300">
          No mail connector yet. Add Gmail (or another mail server that can search and read mail) on{' '}
          <Link to="/connectors" className="font-medium text-brand-600 hover:underline dark:text-brand-300">Connectors</Link>, sign in, then come back here.
        </p>
      ) : (
        <div className="flex flex-col gap-2 rounded-lg border border-dashed border-gray-300 p-3 dark:border-gray-700">
          <div className="flex flex-wrap items-end gap-2">
            <SelectField label="What to look for" value={kind} onChange={v => setKind(v as MailKind)} className="min-w-[12rem]"
              options={[{ value: 'cas', label: 'Mutual fund CAS' }, { value: 'bank', label: 'Bank statement' }]} />
            <SelectField label="Mail connector" value={chosenServer} onChange={setServerId} className="min-w-[10rem]"
              options={servers.map(s => ({ value: s.serverId, label: s.name + (s.signedIn ? '' : ' (sign in needed)') }))} />
            {kind === 'cas' ? (
              <SelectField label="Whose funds" value={chosenPerson} onChange={setPersonId} className="min-w-[10rem]"
                options={active.map(p => ({ value: p.id, label: p.kind === 'self' ? 'Me' : p.displayName }))} />
            ) : bankAccounts.length === 0 ? (
              <p className="text-sm text-gray-600 dark:text-gray-300">Add a bank account (Net worth, then Accounts) first.</p>
            ) : (
              <SelectField label="Into account" value={chosenAccount} onChange={setAccountId} className="min-w-[12rem]"
                options={bankAccounts.map(a => ({ value: a.id, label: accountLabel(a) }))} />
            )}
          </div>
          <div className="flex flex-wrap items-end gap-2">
            <TextField label="From" value={sender} onChange={setSender} maxLength={200} className="min-w-[16rem]"
              placeholder={kind === 'cas' ? casSenders.split(' ').join(', ') : 'statements@yourbank.com'}
              hint={kind === 'cas' ? 'Leave empty for CAMS and KFintech.' : 'The address on your bank’s statement emails.'} />
            <TextField label="Subject has (optional)" value={subject} onChange={setSubject} maxLength={120} className="min-w-[12rem]"
              placeholder={kind === 'cas' ? 'Consolidated Account Statement' : 'Statement'} />
            <TextField label="PDF password" type="password" value={password} onChange={setPassword} maxLength={100} className="min-w-[12rem]"
              hint={kind === 'cas' ? 'Usually your PAN in capitals.' : 'Often your customer ID or date of birth.'} />
            <Button size="sm" loading={link.isPending} disabled={!ready}
              onClick={() => link.mutate({
                serverId: chosenServer, kind,
                personId: kind === 'cas' ? chosenPerson : undefined,
                accountId: kind === 'bank' ? chosenAccount : undefined,
                sender: sender.trim() || undefined, subject: subject.trim() || undefined, password: password || undefined,
              })}>Add</Button>
          </div>
          <p className="text-xs text-gray-500 dark:text-gray-400">The password is kept encrypted and is never shown again. Kai never sends, moves or deletes mail.</p>
        </div>
      )}
      {[link.error, check.error, change.error, unlink.error].filter(Boolean).slice(0, 1).map((e, i) => (
        <p key={i} role="alert" className="text-sm text-red-600 dark:text-red-400">{errorText(e, 'That did not work.')}</p>
      ))}
    </Panel>
  )
}
