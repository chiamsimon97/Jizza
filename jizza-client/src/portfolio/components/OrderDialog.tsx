import { useEffect, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ExternalLink, Lock, Send } from 'lucide-react'
import { financeApi } from '@/api/finance'
import { Button } from '@/components/ui/Button'
import { Dialog } from '@/components/ui/Dialog'
import { fieldClass } from '@/components/ui/SlideOver'
import type { OrderInput, OrderPlaced, OrderSide, OrderType } from '@/types/finance'
import { errorText } from '../data'
import { financeKeys } from '../queryKeys'
import { formatDay } from './AsOf'
import { AmountField, SelectField } from './Fields'
import { Money } from './Money'
import { Pill, Row } from './Panel'

const TERM = { st: 'Short term', lt: 'Long term' }

/**
 * Prepare order (Kai part D): Kai fills in the order and shows what it comes to and, for a sale,
 * which lots go first and the gain on each. The person changes what they like, then places it
 * with their password, every time, when a connected broker can take it; otherwise they open their
 * broker and record the trade afterwards. Kai never places an order on its own.
 */
export function OrderDialog({ accountId: fixedAccount, instrumentId, name, side: startSide = 'buy', quantity: startQty, onClose, onBought }: {
  /** The account, or none to let the person choose a demat account. */
  accountId?: string
  instrumentId: string
  name: string
  side?: OrderSide
  quantity?: number
  onClose: () => void
  /** Where "I bought it" records the trade by hand, when the broker can't take the order here. */
  onBought?: () => void
}) {
  const qc = useQueryClient()
  const accounts = useQuery({ queryKey: financeKeys.accounts, queryFn: financeApi.accounts, enabled: !fixedAccount })
  const demats = (accounts.data ?? []).filter(a => a.kind === 'demat' || a.kind === 'us_broker' || a.kind === 'other')
  const [chosen, setChosen] = useState('')
  const accountId = fixedAccount ?? (chosen || demats[0]?.id || '')
  const [side, setSide] = useState<OrderSide>(startSide)
  const [qty, setQty] = useState(startQty ? String(startQty) : '1')
  const [type, setType] = useState<OrderType>('market')
  const [limit, setLimit] = useState<number | null>(null)
  const [password, setPassword] = useState('')
  const [confirming, setConfirming] = useState(false)
  const [placed, setPlaced] = useState<OrderPlaced | null>(null)
  const quantity = Number(qty)
  const valid = !!accountId && Number.isInteger(quantity) && quantity > 0 && (type === 'market' || (limit != null && limit > 0))
  const input: OrderInput = { accountId, instrumentId, side, quantity, orderType: type, limitPrice: type === 'limit' ? limit : null }
  const preview = useQuery({
    queryKey: ['finance', 'order-preview', input],
    queryFn: () => financeApi.prepareOrder(input),
    enabled: valid,
    retry: false,
  })
  useEffect(() => { setConfirming(false); setPassword('') }, [side, qty, type, limit])
  const place = useMutation({
    mutationFn: () => financeApi.placeOrder({ ...input, password }),
    onSuccess: r => { setPlaced(r); setPassword(''); qc.invalidateQueries({ queryKey: financeKeys.all }) },
  })
  const p = preview.data

  return (
    <Dialog open title={`Prepare an order: ${name}`} description="You check it and place it yourself. Kai never places an order on its own." onClose={onClose} size="lg">
      {placed ? (
        <div className="flex flex-col gap-3" role="status">
          <Pill tone={placed.status === 'sent' ? 'ok' : 'bad'}>{placed.status === 'sent' ? 'Sent to your broker' : 'Not taken'}</Pill>
          <p className="text-sm text-gray-700 dark:text-gray-200">{placed.message}</p>
          <div className="flex justify-end"><Button onClick={onClose}>Done</Button></div>
        </div>
      ) : (
        <div className="flex flex-col gap-3">
          {!fixedAccount && (demats.length === 0
            ? <p className="text-sm text-gray-600 dark:text-gray-300">Add a demat account first (Net worth, then Accounts).</p>
            : <SelectField label="Account" value={accountId} onChange={setChosen}
                options={demats.map(a => ({ value: a.id, label: [a.provider, a.nickname].filter(Boolean).join(' · ') || 'Demat account' }))} />)}
          <div className="grid gap-3 sm:grid-cols-4">
            <SelectField label="Buy or sell" value={side} onChange={v => setSide(v)} options={[{ value: 'buy', label: 'Buy' }, { value: 'sell', label: 'Sell' }]} />
            <div className="flex flex-col gap-1">
              <label htmlFor="order-qty" className="text-sm font-medium text-gray-700 dark:text-gray-300">Quantity</label>
              <input id="order-qty" inputMode="numeric" className={fieldClass} value={qty} onChange={e => setQty(e.target.value)} />
            </div>
            <SelectField label="Order type" value={type} onChange={v => setType(v)} options={[{ value: 'market', label: 'Market' }, { value: 'limit', label: 'Limit' }]} />
            {type === 'limit' && <AmountField label="Limit price" value={limit} onChange={setLimit} />}
          </div>
          {!valid && <p className="text-xs text-gray-500 dark:text-gray-400">Shares go in whole units; a limit order needs a price.</p>}
          {preview.error && <p role="alert" className="text-sm text-red-600 dark:text-red-400">{errorText(preview.error, 'Kai could not prepare this order.')}</p>}
          {p && (
            <div className="flex flex-col gap-1.5 rounded-xl bg-gray-50 p-3 dark:bg-gray-800/60">
              <Row label={`${p.side === 'buy' ? 'Buy' : 'Sell'} ${p.quantity} × ${p.symbol ?? p.instrumentName}`}>
                {p.estimate != null ? <>about <Money amount={p.estimate} /></> : 'No price yet'}
              </Row>
              {p.price != null && <Row label="Last price">{<Money amount={p.price} />}{p.priceDate ? ` · ${formatDay(p.priceDate)}` : ''}</Row>}
              <Row label="You hold">{p.held} units</Row>
              {p.side === 'sell' && p.lots.length > 0 && (
                <div className="mt-1 flex flex-col gap-1" aria-label="Lots this sale uses">
                  <p className="text-xs font-medium text-gray-600 dark:text-gray-300">Lots this sale uses, oldest first</p>
                  {p.lots.map((l, i) => (
                    <div key={i} className="flex flex-wrap justify-between gap-2 text-xs text-gray-600 dark:text-gray-300">
                      <span>{l.quantity} units{l.acquiredOn ? ` bought ${formatDay(l.acquiredOn)}` : ', date not known'}{l.term ? ` · ${TERM[l.term]}` : ''}</span>
                      <span>{l.gain == null ? 'gain not known' : <>gain <Money amount={l.gain} /></>}</span>
                    </div>
                  ))}
                  {p.shortTermGain != null && <Row label="Short-term gain">{<Money amount={p.shortTermGain} />}</Row>}
                  {p.longTermGain != null && <Row label="Long-term gain">{<Money amount={p.longTermGain} />}</Row>}
                  <p className="text-xs text-gray-500 dark:text-gray-400">At the last price. The broker's fill price and charges will differ a little.</p>
                </div>
              )}
            </div>
          )}

          {p && p.canPlace && !confirming && (
            <div className="flex justify-end gap-2">
              <Button variant="secondary" onClick={onClose}>Cancel</Button>
              <Button onClick={() => setConfirming(true)}><Send className="h-4 w-4" aria-hidden />Place with {p.placeVia}</Button>
            </div>
          )}
          {p && p.canPlace && confirming && (
            <form className="flex flex-col gap-2 rounded-xl border border-amber-300 p-3 dark:border-amber-700"
              onSubmit={e => { e.preventDefault(); if (password) place.mutate() }}>
              <p className="flex items-center gap-1.5 text-sm font-medium text-gray-900 dark:text-gray-100">
                <Lock className="h-4 w-4" aria-hidden />Confirm with your password to {p.side} {p.quantity} {p.symbol} through {p.placeVia}.
              </p>
              <label htmlFor="order-password" className="sr-only">Password</label>
              <input id="order-password" type="password" autoComplete="current-password" className={fieldClass} value={password}
                placeholder="Your Jizza password" onChange={e => setPassword(e.target.value)} />
              {place.error && <p role="alert" className="text-sm text-red-600 dark:text-red-400">{errorText(place.error, 'The order was not placed.')}</p>}
              <div className="flex justify-end gap-2">
                <Button type="button" variant="secondary" onClick={() => setConfirming(false)}>Back</Button>
                <Button type="submit" disabled={!password} loading={place.isPending}>Place order</Button>
              </div>
            </form>
          )}
          {p && !p.canPlace && (
            <div className="flex flex-col gap-2">
              <p className="text-sm text-gray-600 dark:text-gray-300">{p.whyNot}</p>
              <div className="flex flex-wrap justify-end gap-2">
                <a href={p.brokerUrl} target="_blank" rel="noreferrer noopener"
                  className="inline-flex items-center gap-1.5 rounded-lg border border-gray-300 px-3 py-1.5 text-sm font-medium text-gray-700 hover:bg-gray-50 dark:border-gray-700 dark:text-gray-200 dark:hover:bg-gray-800">
                  <ExternalLink className="h-4 w-4" aria-hidden />Open in my broker
                </a>
                {onBought && <Button variant="secondary" onClick={onBought}>I bought it</Button>}
                <Button variant="ghost" onClick={onClose}>Close</Button>
              </div>
            </div>
          )}
        </div>
      )}
    </Dialog>
  )
}
