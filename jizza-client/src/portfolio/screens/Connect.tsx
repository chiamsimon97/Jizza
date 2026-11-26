import { useMemo, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { AlertTriangle, CheckCircle2, FileUp, RotateCcw } from 'lucide-react'
import { financeApi } from '@/api/finance'
import { Button } from '@/components/ui/Button'
import type {
  AccountKind, ColumnMapping, FinAccount, FinCategory, FinPerson, ImportBatch, ImportInput, ImportKind, ImportRow,
  ImportStatus,
} from '@/types/finance'
import { formatDay } from '../components/AsOf'
import { DataTable } from '../components/DataTable'
import { SelectField, TextField } from '../components/Fields'
import { Money } from '../components/Money'
import { LoadFailed, Panel, Pill, Row } from '../components/Panel'
import { SkeletonRows } from '../components/SkeletonRows'
import { errorText } from '../data'
import { financeKeys } from '../queryKeys'
import { ConnectedSources } from './ConnectedSources'

interface Source {
  kind: ImportKind
  title: string
  file: string
  can: string[]
  cannot: string[]
  /** The accounts a file of this kind belongs to; null when it covers a whole person (CAS). */
  accountKinds: AccountKind[] | null
  accept: string
}

export const SOURCES: Source[] = [
  {
    kind: 'bank_csv', title: 'Bank statement', file: 'CSV from any bank',
    can: ['Each payment and credit, sorted into need, want and save', 'The closing balance of a savings or current account',
      'Any bank: Kai finds the columns, or you pick them once and Kai remembers'],
    cannot: ['Bank PDFs yet: download CSV, or save the Excel file as CSV', 'A card’s outstanding: only its spending comes in'],
    accountKinds: ['savings', 'current', 'credit_card', 'wallet', 'deposit', 'other'], accept: '.csv,text/csv',
  },
  {
    kind: 'cas_pdf', title: 'Mutual funds (CAS)', file: 'CAMS or KFintech consolidated statement (PDF)',
    can: ['Every folio and scheme for one person', 'Units and value on the statement date', 'SIPs, purchases, redemptions and switches'],
    cannot: ['Shares in a demat account: use the broker files', 'Keep your PDF password: you type it each time'],
    accountKinds: null, accept: '.pdf,application/pdf',
  },
  {
    kind: 'broker_holdings', title: 'Broker holdings', file: 'Holdings CSV from your broker',
    can: ['Shares, ETFs and gold bonds held today, with their value', 'Replaces the account’s last snapshot'],
    cannot: ['When each lot was bought: that comes with Holdings'],
    accountKinds: ['demat', 'us_broker', 'other'], accept: '.csv,text/csv',
  },
  {
    kind: 'broker_tradebook', title: 'Broker tradebook', file: 'Tradebook CSV from your broker',
    can: ['Each buy and sell with its quantity and price'], cannot: ['Brokerage and taxes on each trade'],
    accountKinds: ['demat', 'us_broker', 'other'], accept: '.csv,text/csv',
  },
]

const ACCOUNT_KIND_LABELS: Record<AccountKind, string> = {
  savings: 'Savings account', current: 'Current account', credit_card: 'Credit card', demat: 'Demat account',
  mf_folio: 'Mutual fund folio', retirement: 'Retirement account', deposit: 'Deposit', wallet: 'Wallet', cash: 'Cash',
  us_broker: 'US broker', other: 'Other account',
}

const STATUS: Record<ImportStatus, { label: string; tone: 'ok' | 'warn' | 'neutral' | 'bad' | 'info' }> = {
  parsed: { label: 'To review', tone: 'info' },
  needs_review: { label: 'Choose columns', tone: 'warn' },
  committed: { label: 'Saved', tone: 'ok' },
  failed: { label: 'Failed', tone: 'bad' },
  undone: { label: 'Undone', tone: 'neutral' },
}

const ROLES: { key: keyof Omit<ColumnMapping, 'headerRow'>; label: string }[] = [
  { key: 'date', label: 'Date' }, { key: 'description', label: 'Description' }, { key: 'debit', label: 'Money out (debit)' },
  { key: 'credit', label: 'Money in (credit)' }, { key: 'amount', label: 'One amount column' },
  { key: 'drCr', label: 'Dr / Cr column' }, { key: 'balance', label: 'Balance' },
]

/** The most rows the review shows; the counts cover them all. */
const SHOWN_ROWS = 200

export function accountLabel(a: FinAccount): string {
  return [a.provider || ACCOUNT_KIND_LABELS[a.kind], a.nickname, a.last4 ? `••${a.last4}` : null].filter(Boolean).join(' · ')
}

/** Columns that are enough to read a bank CSV: a date, a description, and debit and credit or one amount. */
export function mappingComplete(m: Partial<ColumnMapping>): boolean {
  return !!m.date && !!m.description && ((!!m.debit && !!m.credit) || !!m.amount)
}

function kindTitle(kind: ImportKind) {
  return SOURCES.find(s => s.kind === kind)?.title ?? 'Bank PDF'
}

/**
 * Connect and imports (7.21): one card per source with what it can and can't do, importing a file
 * with a review before anything is saved, the history with undo, and the last runs.
 */
export function Connect({ people }: { people: FinPerson[] }) {
  const qc = useQueryClient()
  const imports = useQuery({ queryKey: financeKeys.imports, queryFn: financeApi.imports })
  const accounts = useQuery({ queryKey: financeKeys.accounts, queryFn: financeApi.accounts })
  const [source, setSource] = useState<ImportKind | null>(null)
  const [open, setOpen] = useState<string | null>(null)
  /** The last upload, so the columns can be chosen without picking the file again. */
  const [last, setLast] = useState<ImportInput | null>(null)
  const undo = useMutation({
    mutationFn: (id: string) => financeApi.undoImport(id),
    onSuccess: () => qc.invalidateQueries({ queryKey: financeKeys.all }),
  })

  if (imports.isLoading || accounts.isLoading) return <SkeletonRows rows={6} />
  if (imports.error || accounts.error || !imports.data || !accounts.data) {
    return <LoadFailed text={errorText(imports.error ?? accounts.error, 'Could not load your imports.')} onRetry={() => { imports.refetch(); accounts.refetch() }} />
  }
  const history = imports.data
  const accountById = new Map(accounts.data.map(a => [a.id, a]))
  const personById = new Map(people.map(p => [p.id, p]))
  const whose = (b: ImportBatch) => b.accountId && accountById.get(b.accountId)
    ? accountLabel(accountById.get(b.accountId)!)
    : (personById.get(b.personId)?.kind === 'self' ? 'You' : personById.get(b.personId)?.displayName ?? '')

  const start = (kind: ImportKind) => { setSource(kind); setOpen(null); setLast(null) }
  const close = () => { setSource(null); setOpen(null); setLast(null) }

  return (
    <div className="flex flex-col gap-4">
      <div>
        <h2 className="text-base font-semibold text-gray-900 dark:text-gray-100">Connect and imports</h2>
        <p className="text-sm text-gray-600 dark:text-gray-400">
          Kai reads the files you download from your bank, fund registrar or broker. You see every row before it is saved, and you can undo an import later.
          Files stay in your account; nothing is sent anywhere.
        </p>
      </div>

      <ConnectedSources accounts={accounts.data} onReview={id => { setSource('broker_holdings'); setOpen(id); setLast(null) }} />

      <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
        {SOURCES.map(s => {
          const latest = history.find(b => b.kind === s.kind)
          return (
            <Panel key={s.kind} title={s.title} className={source === s.kind ? 'ring-2 ring-brand-400' : ''}>
              <p className="text-xs text-gray-500 dark:text-gray-400">{s.file}</p>
              <ul className="flex flex-col gap-1 text-sm text-gray-700 dark:text-gray-300" aria-label={`What ${s.title} brings in`}>
                {s.can.map(c => <li key={c} className="flex gap-1.5"><CheckCircle2 className="mt-0.5 h-3.5 w-3.5 shrink-0 text-green-600" aria-hidden />{c}</li>)}
              </ul>
              <ul className="flex flex-col gap-1 text-xs text-gray-500 dark:text-gray-400" aria-label={`What ${s.title} can't do`}>
                {s.cannot.map(c => <li key={c}>Not: {c}</li>)}
              </ul>
              <div className="mt-auto flex flex-wrap items-center gap-2 pt-1">
                <Button size="sm" onClick={() => start(s.kind)}><FileUp className="h-4 w-4" aria-hidden />Import a file</Button>
                {latest
                  ? <span className="text-xs text-gray-500 dark:text-gray-400">Last: {formatDay(latest.createdAt)} <Pill tone={STATUS[latest.status].tone}>{STATUS[latest.status].label}</Pill></span>
                  : <span className="text-xs text-gray-500 dark:text-gray-400">Not used yet</span>}
              </div>
            </Panel>
          )
        })}
      </div>

      {source && !open && (
        <ImportForm key={source} source={SOURCES.find(s => s.kind === source)!} people={people} accounts={accounts.data}
          onRead={(batch, input) => { setLast(input); setOpen(batch.id) }} onCancel={close} />
      )}
      {open && <Review key={open} id={open} last={last} accounts={accounts.data} onReread={(b, input) => { setLast(input); setOpen(b.id) }} onDone={close} />}

      <Panel title="Import history">
        <DataTable caption="Import history" rows={history} rowKey={b => b.id} empty="No imports yet."
          columns={[
            { header: 'When', cell: b => formatDay(b.createdAt) },
            { header: 'File', cell: b => <span className="break-all">{b.stats.fileName ?? 'File'}</span> },
            { header: 'What', cell: b => kindTitle(b.kind) },
            { header: 'For', cell: whose },
            { header: 'Rows', numeric: true, cell: b => b.status === 'committed' ? b.stats.counts?.imported ?? 0 : b.stats.counts?.new ?? 0 },
            { header: 'State', cell: b => <Pill tone={STATUS[b.status].tone}>{STATUS[b.status].label}</Pill> },
            {
              header: 'Actions', cell: b => b.status === 'committed'
                ? <Button size="sm" variant="secondary" loading={undo.isPending && undo.variables === b.id} onClick={() => undo.mutate(b.id)}><RotateCcw className="h-3.5 w-3.5" aria-hidden />Undo</Button>
                : b.status === 'parsed' || b.status === 'needs_review'
                  ? <Button size="sm" variant="ghost" onClick={() => { setSource(b.kind); setOpen(b.id); setLast(null) }}>Review</Button>
                  : null,
            },
          ]} />
        {undo.error && <p role="alert" className="text-sm text-red-600 dark:text-red-400">{errorText(undo.error, 'Could not undo this import.')}</p>}
      </Panel>

      <LastRuns />
    </div>
  )
}

function ImportForm({ source, people, accounts, onRead, onCancel }: {
  source: Source
  people: FinPerson[]
  accounts: FinAccount[]
  onRead: (batch: ImportBatch, input: ImportInput) => void
  onCancel: () => void
}) {
  const qc = useQueryClient()
  const active = people.filter(p => p.status === 'active')
  const self = active.find(p => p.kind === 'self')
  const fitting = source.accountKinds ? accounts.filter(a => a.status === 'active' && source.accountKinds!.includes(a.kind)) : []
  const [personId, setPersonId] = useState(self?.id ?? active[0]?.id ?? '')
  const [accountId, setAccountId] = useState(fitting[0]?.id ?? 'new')
  const [file, setFile] = useState<File | null>(null)
  const [password, setPassword] = useState('')
  const [draft, setDraft] = useState({ kind: source.accountKinds?.[0] ?? 'savings' as AccountKind, provider: '', last4: '', nickname: '' })

  const read = useMutation({
    mutationFn: async () => {
      let account = accountId
      if (source.accountKinds && accountId === 'new') {
        const made = await financeApi.addAccount({
          personId, kind: draft.kind, provider: draft.provider.trim() || undefined,
          last4: draft.last4.trim() || undefined, nickname: draft.nickname.trim() || undefined,
        })
        account = made.id
        setAccountId(made.id)
        qc.invalidateQueries({ queryKey: financeKeys.accounts })
      }
      const input: ImportInput = source.accountKinds
        ? { kind: source.kind, file: file!, accountId: account }
        : { kind: source.kind, file: file!, personId, password: password || undefined }
      const batch = await financeApi.upload(input)
      return { batch, input }
    },
    onSuccess: ({ batch, input }) => {
      setPassword('')
      qc.invalidateQueries({ queryKey: financeKeys.imports })
      onRead(batch, { ...input, password: undefined })
    },
  })
  const personOptions = active.map(p => ({ value: p.id, label: p.kind === 'self' ? 'Me' : p.displayName }))
  const last4Bad = draft.last4 !== '' && !/^\d{4}$/.test(draft.last4)
  const ready = !!file && (!source.accountKinds || accountId !== 'new' || (!last4Bad && !!personId)) && (!!source.accountKinds || !!personId)

  return (
    <Panel title={`Import: ${source.title}`} action={<Button size="sm" variant="ghost" onClick={onCancel}>Cancel</Button>}>
      <div className="grid gap-3 sm:grid-cols-2">
        {source.accountKinds ? (
          <SelectField label="Account" value={accountId} onChange={setAccountId}
            options={[...fitting.map(a => ({ value: a.id, label: accountLabel(a) })), { value: 'new', label: 'Add an account…' }]} />
        ) : (
          <SelectField label="Whose statement" value={personId} onChange={setPersonId} options={personOptions}
            hint="One CAS covers one person's funds (one PAN)." />
        )}
        <label className="flex flex-col gap-1 text-sm">
          <span className="font-medium text-gray-700 dark:text-gray-300">File</span>
          <input type="file" accept={source.accept} aria-label="File" className="text-sm"
            onChange={e => setFile(e.target.files?.[0] ?? null)} />
          <span className="text-xs text-gray-500 dark:text-gray-400">{source.file}. Up to 10 MB.</span>
        </label>
      </div>
      {source.accountKinds && accountId === 'new' && (
        <div className="grid gap-3 rounded-lg border border-dashed border-gray-300 p-3 sm:grid-cols-2 lg:grid-cols-4 dark:border-gray-700">
          <SelectField label="Whose account" value={personId} onChange={setPersonId} options={personOptions} />
          <SelectField label="Kind" value={draft.kind} onChange={v => setDraft(d => ({ ...d, kind: v }))}
            options={source.accountKinds.map(k => ({ value: k, label: ACCOUNT_KIND_LABELS[k] }))} />
          <TextField label={source.kind === 'bank_csv' ? 'Bank' : 'Broker'} value={draft.provider} maxLength={60}
            placeholder={source.kind === 'bank_csv' ? 'Any bank' : 'Any broker'} onChange={v => setDraft(d => ({ ...d, provider: v }))} />
          <TextField label="Last 4 digits (optional)" value={draft.last4} maxLength={4}
            hint={last4Bad ? 'Four digits only.' : 'Only the last four, to tell accounts apart.'} onChange={v => setDraft(d => ({ ...d, last4: v }))} />
        </div>
      )}
      {source.kind === 'cas_pdf' && (
        <label className="flex flex-col gap-1 text-sm sm:max-w-sm">
          <span className="font-medium text-gray-700 dark:text-gray-300">PDF password</span>
          <input type="password" aria-label="PDF password" autoComplete="off" maxLength={64} value={password} onChange={e => setPassword(e.target.value)}
            className="rounded-lg border border-gray-200 bg-white px-3 py-2 text-sm dark:border-gray-700 dark:bg-gray-900" />
          <span className="text-xs text-gray-500 dark:text-gray-400">The one you set when you asked for the statement. Kai uses it to open the file once and never keeps it.</span>
        </label>
      )}
      <div className="flex flex-wrap items-center gap-2">
        <Button loading={read.isPending} disabled={!ready} onClick={() => read.mutate()}>Read the file</Button>
        <span className="text-xs text-gray-500 dark:text-gray-400">Nothing is saved until you check the rows and press Save.</span>
      </div>
      {read.error && <p role="alert" className="text-sm text-red-600 dark:text-red-400">{errorText(read.error, 'Kai could not read this file.')}</p>}
    </Panel>
  )
}

function Review({ id, last, accounts, onReread, onDone }: {
  id: string
  last: ImportInput | null
  accounts: FinAccount[]
  onReread: (batch: ImportBatch, input: ImportInput) => void
  onDone: () => void
}) {
  const qc = useQueryClient()
  const batch = useQuery({ queryKey: financeKeys.importBatch(id), queryFn: () => financeApi.importBatch(id) })
  const categories = useQuery({ queryKey: financeKeys.categories, queryFn: financeApi.categories })
  const [chosen, setChosen] = useState<Record<string, string>>({})
  const [always, setAlways] = useState<Set<number>>(new Set())
  const refresh = () => qc.invalidateQueries({ queryKey: financeKeys.all })
  const commit = useMutation({
    mutationFn: () => financeApi.commitImport(id, { categories: chosen, always: [...always] }),
    onSuccess: v => { qc.setQueryData(financeKeys.importBatch(id), v); refresh() },
  })
  const discard = useMutation({ mutationFn: () => financeApi.undoImport(id), onSuccess: () => { refresh(); onDone() } })

  if (batch.isLoading) return <SkeletonRows rows={5} />
  if (batch.error || !batch.data) return <LoadFailed text={errorText(batch.error, 'Could not load this import.')} onRetry={() => batch.refetch()} />
  const b = batch.data
  const counts = b.stats.counts
  const account = b.accountId ? accounts.find(a => a.id === b.accountId) : undefined

  if (b.status === 'needs_review') {
    return <ColumnChooser batch={b} last={last} onRead={onReread} onCancel={() => discard.mutate()} discarding={discard.isPending} />
  }
  if (b.status === 'committed') {
    return (
      <Panel title={<>Saved <Pill tone="ok"><CheckCircle2 className="h-3 w-3" aria-hidden />{counts?.imported ?? 0} rows</Pill></>}
        action={<Button size="sm" variant="ghost" onClick={onDone}>Close</Button>}>
        <p className="text-sm text-gray-700 dark:text-gray-300">
          {b.stats.fileName} is saved{account ? ` to ${accountLabel(account)}` : ''}. Home, Cash flow and Net worth now include it.
          You can undo it from the history below.
        </p>
      </Panel>
    )
  }
  if (b.status !== 'parsed') return null

  const bank = b.kind === 'bank_csv'
  const rows = b.stats.rows ?? []
  const cats = categories.data ?? []
  return (
    <Panel title={<>Check before saving <span className="text-xs font-normal text-gray-500">{b.stats.fileName}</span></>}>
      <div className="flex flex-wrap gap-2" aria-label="What this file holds">
        <Pill tone="ok">{counts?.new ?? 0} new</Pill>
        <Pill tone="neutral">{counts?.duplicates ?? 0} already imported</Pill>
        <Pill tone={counts?.needsLook ? 'warn' : 'neutral'}>{counts?.needsLook ?? 0} need a look</Pill>
        {!!counts?.unreadable && <Pill tone="bad">{counts.unreadable} couldn’t be read</Pill>}
        {!!counts?.holdings && <Pill tone="info">{counts.holdings} holdings</Pill>}
      </div>
      {b.stats.balance && (
        <Row label={`Closing balance on ${formatDay(b.stats.balance.date)}`}><Money amount={b.stats.balance.amount} /></Row>
      )}
      {!!b.stats.groups?.length && (
        <p className="text-sm text-gray-600 dark:text-gray-300">
          {b.stats.groups.length} folio{b.stats.groups.length === 1 ? '' : 's'}: {b.stats.groups.map(g => `${g.provider ?? 'Folio'} ••${g.last4}${g.accountId ? '' : ' (new)'}`).join(', ')}
        </p>
      )}
      {!!b.stats.holdings?.length && (
        <DataTable caption="Holdings in this file" rows={b.stats.holdings} rowKey={h => `${h.group ?? ''}${h.name}`}
          columns={[
            { header: 'Holding', cell: h => h.name },
            { header: 'Class', cell: h => h.assetClass },
            { header: 'Units', numeric: true, cell: h => h.units.toLocaleString('en-IN', { maximumFractionDigits: 3 }) },
            { header: 'Value', numeric: true, cell: h => <Money amount={h.value} /> },
            { header: 'On', cell: h => formatDay(h.asOf) },
          ]} />
      )}
      {rows.length > 0 && (
        <>
          <DataTable caption="Rows in this file" rows={rows.slice(0, SHOWN_ROWS)} rowKey={r => String(r.i)}
            columns={[
              { header: 'Date', cell: r => formatDay(r.date) },
              { header: 'Description', cell: r => <span className="break-all">{r.description || '—'}</span> },
              { header: 'Amount', numeric: true, cell: r => <Money amount={r.direction === 'out' ? -r.amount : r.amount} signed /> },
              {
                header: bank ? 'Category' : 'Kind', cell: r => r.duplicate
                  ? <Pill tone="neutral">Already imported</Pill>
                  : bank
                    ? <CategoryPick row={r} categories={cats} value={chosen[r.i] ?? r.categoryId ?? ''}
                        always={always.has(r.i)}
                        onChange={v => setChosen(c => ({ ...c, [r.i]: v }))}
                        onAlways={on => setAlways(s => { const n = new Set(s); if (on) n.add(r.i); else n.delete(r.i); return n })} />
                    : <span className="flex items-center gap-1">{r.kind.replace('_', ' ')}{r.needsLook && <Pill tone="warn">Needs a look</Pill>}</span>,
              },
            ]} />
          {rows.length > SHOWN_ROWS && <p className="text-xs text-gray-500 dark:text-gray-400">Showing the first {SHOWN_ROWS} of {rows.length} rows; Save brings in all of them.</p>}
        </>
      )}
      {!!b.stats.unreadable?.length && (
        <details className="text-sm">
          <summary className="cursor-pointer text-gray-700 dark:text-gray-300">
            <AlertTriangle className="mr-1 inline h-4 w-4 text-yellow-600" aria-hidden />{b.stats.unreadable.length} line{b.stats.unreadable.length === 1 ? '' : 's'} Kai couldn’t read (left out)
          </summary>
          <ul className="mt-1 flex flex-col gap-0.5 text-xs text-gray-600 dark:text-gray-400">
            {b.stats.unreadable.map(u => <li key={u.line}>Line {u.line}: {u.reason}</li>)}
          </ul>
        </details>
      )}
      <div className="flex flex-wrap items-center gap-2">
        <Button loading={commit.isPending} disabled={!counts?.new && !counts?.holdings && !b.stats.balance} onClick={() => commit.mutate()}>Save</Button>
        <Button variant="secondary" loading={discard.isPending} onClick={() => discard.mutate()}>Discard</Button>
        {bank && !!counts?.needsLook && <span className="text-xs text-gray-500 dark:text-gray-400">Rows left without a category are saved as “Needs a look”; you can place them later.</span>}
      </div>
      {(commit.error || discard.error) && (
        <p role="alert" className="text-sm text-red-600 dark:text-red-400">{errorText(commit.error ?? discard.error, 'That did not work.')}</p>
      )}
    </Panel>
  )
}

function CategoryPick({ row, categories, value, always, onChange, onAlways }: {
  row: ImportRow
  categories: FinCategory[]
  value: string
  always: boolean
  onChange: (v: string) => void
  onAlways: (on: boolean) => void
}) {
  const changed = value !== (row.categoryId ?? '')
  return (
    <div className="flex flex-col gap-1">
      <select aria-label={`Category for ${row.description || `line ${row.line}`}`} value={value} onChange={e => onChange(e.target.value)}
        className={`rounded-lg border bg-white px-2 py-1 text-sm dark:bg-gray-900 ${value ? 'border-gray-200 dark:border-gray-700' : 'border-yellow-400 dark:border-yellow-600'}`}>
        <option value="">Needs a look</option>
        {(['need', 'want', 'save', 'income', 'transfer'] as const).map(bucket => (
          <optgroup key={bucket} label={{ need: 'Need', want: 'Want', save: 'Save', income: 'Income', transfer: 'Transfer' }[bucket]}>
            {categories.filter(c => c.bucket === bucket && !c.hidden).map(c => <option key={c.id} value={c.id}>{c.name}</option>)}
          </optgroup>
        ))}
      </select>
      {changed && value && (
        <label className="flex items-center gap-1 text-xs text-gray-600 dark:text-gray-400">
          <input type="checkbox" checked={always} onChange={e => onAlways(e.target.checked)} /> Always for rows like this
        </label>
      )}
    </div>
  )
}

function ColumnChooser({ batch, last, onRead, onCancel, discarding }: {
  batch: ImportBatch
  last: ImportInput | null
  onRead: (batch: ImportBatch, input: ImportInput) => void
  onCancel: () => void
  discarding: boolean
}) {
  const qc = useQueryClient()
  const sample = batch.stats.columns?.sample ?? []
  const [mapping, setMapping] = useState<Partial<ColumnMapping>>(() => ({ headerRow: batch.stats.columns?.headerRow ?? 0, ...batch.mapping }))
  const headerRow = mapping.headerRow ?? 0
  const headers = useMemo(() => (sample[headerRow] ?? []).filter(h => h.trim() !== ''), [sample, headerRow])
  const read = useMutation({
    mutationFn: () => financeApi.upload({ ...last!, mapping: mapping as ColumnMapping }),
    onSuccess: b => { qc.invalidateQueries({ queryKey: financeKeys.imports }); onRead(b, last!) },
  })
  const set = (key: keyof ColumnMapping, v: string) => setMapping(m => ({ ...m, [key]: v || undefined }))

  return (
    <Panel title={<>Which column is which? <span className="text-xs font-normal text-gray-500">{batch.stats.fileName}</span></>}>
      <p className="text-sm text-gray-700 dark:text-gray-300">
        {batch.error ?? 'Kai couldn’t tell which column is which.'} Kai remembers your choice for this account’s next statement.
      </p>
      <div className="overflow-x-auto">
        <table className="min-w-[28rem] text-left text-xs">
          <caption className="sr-only">The first lines of the file</caption>
          <tbody>
            {sample.map((r, i) => (
              <tr key={i} className={i === headerRow ? 'bg-brand-50 font-semibold dark:bg-brand-900/30' : ''}>
                <th scope="row" className="pr-2 text-gray-400">{i + 1}</th>
                {r.map((c, j) => <td key={j} className="whitespace-nowrap py-0.5 pr-3 text-gray-700 dark:text-gray-300">{c}</td>)}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
        <SelectField label="Line with the column names" value={String(headerRow)} onChange={v => setMapping({ headerRow: Number(v) })}
          options={sample.map((r, i) => ({ value: String(i), label: `Line ${i + 1}: ${r.filter(Boolean).slice(0, 3).join(', ') || '(blank)'}` }))} />
        {ROLES.map(role => (
          <SelectField key={role.key} label={role.label} value={(mapping[role.key] as string | undefined) ?? ''} onChange={v => set(role.key, v)}
            options={[{ value: '', label: '—' }, ...headers.map(h => ({ value: h, label: h }))]} />
        ))}
      </div>
      <p className="text-xs text-gray-500 dark:text-gray-400">Kai needs a date, a description, and either money out and money in, or one amount column.</p>
      <div className="flex flex-wrap items-center gap-2">
        {last
          ? <Button loading={read.isPending} disabled={!mappingComplete(mapping)} onClick={() => read.mutate()}>Read with these columns</Button>
          : <span className="text-sm text-gray-600 dark:text-gray-300">Choose the file again above to read it with these columns.</span>}
        <Button variant="secondary" loading={discarding} onClick={onCancel}>Discard</Button>
      </div>
      {read.error && <p role="alert" className="text-sm text-red-600 dark:text-red-400">{errorText(read.error, 'Kai could not read the file with these columns.')}</p>}
    </Panel>
  )
}

/** The checks that run by themselves, when each last ran, and a way to run one now. */
function LastRuns() {
  const qc = useQueryClient()
  const health = useQuery({ queryKey: financeKeys.health(false), queryFn: financeApi.health })
  const run = useMutation({ mutationFn: financeApi.runHealth, onSuccess: v => { qc.setQueryData(financeKeys.health(false), v); qc.invalidateQueries({ queryKey: financeKeys.all }) } })
  const trend = health.data?.trend ?? []
  const lastRun = trend.length ? trend[trend.length - 1].at : null
  return (
    <Panel title="Last runs">
      <div className="flex flex-wrap items-center justify-between gap-2 text-sm">
        <span className="text-gray-700 dark:text-gray-300">Money Health check: {lastRun ? formatDay(lastRun) : 'not run yet'}</span>
        <Button size="sm" variant="secondary" loading={run.isPending} onClick={() => run.mutate()}>Run now</Button>
      </div>
      <div className="flex flex-wrap items-center justify-between gap-2 text-sm">
        <span className="text-gray-700 dark:text-gray-300">Daily prices and NAVs</span>
        <span className="text-xs text-gray-500 dark:text-gray-400">Start with Holdings. Until then values are as of each statement.</span>
      </div>
      {run.error && <p role="alert" className="text-sm text-red-600 dark:text-red-400">{errorText(run.error, 'The check did not run.')}</p>}
    </Panel>
  )
}
