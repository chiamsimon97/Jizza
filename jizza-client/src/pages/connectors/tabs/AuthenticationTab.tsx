import { useEffect, useState, type FormEvent } from 'react'
import { useSearchParams } from 'react-router-dom'
import { useMutation, useQuery } from '@tanstack/react-query'
import { Card } from '@/components/ui/Card'
import { Badge } from '@/components/ui/Badge'
import { Button } from '@/components/ui/Button'
import { Input } from '@/components/ui/Input'
import { Spinner } from '@/components/ui/Spinner'
import { Field, fieldClass } from '@/components/ui/SlideOver'
import { servicesApi } from '@/api/services'
import type { AuthScheme } from '@/types'
import { AUTH_LABELS, SectionHeader, errorText } from '../shared'
import type { TabProps } from '../ServiceEditorPage'
import { Can } from '@/auth/Can'
import { PERMISSIONS } from '@/lib/permissions'

/** How the service signs in, and this account's connection to it. */
export function AuthenticationTab({ detail, refresh }: TabProps) {
  const id = detail.service.id
  const [params] = useSearchParams()
  const { data: conn, isLoading, refetch } = useQuery({ queryKey: ['connection', id], queryFn: () => servicesApi.connection(id) })

  const [scheme, setScheme] = useState<AuthScheme>((params.get('scheme') as AuthScheme) ?? detail.service.authScheme)
  const [form, setForm] = useState<Record<string, string>>({})
  const [pkce, setPkce] = useState(false)

  useEffect(() => {
    if (!conn) return
    setForm({
      clientId: conn.clientId ?? '', clientSecret: '', authorizationUrl: conn.authorizationUrl ?? '',
      accessTokenUrl: conn.accessTokenUrl ?? '', refreshTokenUrl: conn.refreshTokenUrl ?? '',
      scopes: conn.scopes ?? '', tokenPlacement: conn.tokenPlacement ?? 'HEADER',
      keyName: conn.extraFields?.apiKey?.name ?? 'Authorization', keyPrefix: conn.extraFields?.apiKey?.prefix ?? '',
      keyIn: conn.extraFields?.apiKey?.in ?? 'header',
    })
    setPkce(conn.pkce)
  }, [conn])

  const set = (k: string) => (e: { target: { value: string } }) => setForm(f => ({ ...f, [k]: e.target.value }))

  const save = useMutation({
    mutationFn: () => servicesApi.updateAuth(id, {
      authScheme: scheme,
      clientId: form.clientId || undefined,
      // Blank means "keep what is stored"; the old secret is never sent back to the browser.
      clientSecret: form.clientSecret || undefined,
      authorizationUrl: form.authorizationUrl || undefined,
      accessTokenUrl: form.accessTokenUrl || undefined,
      refreshTokenUrl: form.refreshTokenUrl || undefined,
      scopes: form.scopes || undefined,
      pkce,
      tokenPlacement: form.tokenPlacement,
      extraFields: scheme === 'API_KEY' ? { apiKey: { name: form.keyName, prefix: form.keyPrefix, in: form.keyIn } } : undefined,
    }),
    onSuccess: () => { refetch(); refresh() },
  })
  const connect = useMutation({
    mutationFn: () => servicesApi.authorize(id),
    onSuccess: r => { window.location.href = r.url },
  })
  const test = useMutation({ mutationFn: () => servicesApi.testAuth(id) })
  const revoke = useMutation({ mutationFn: () => servicesApi.revoke(id), onSuccess: () => { refetch(); refresh() } })

  if (isLoading || !conn) return <div className="flex justify-center py-10"><Spinner /></div>

  const redirectUri = detail.oauthRedirectUri

  function submit(e: FormEvent) {
    e.preventDefault()
    save.mutate()
  }

  return (
    <div className="flex flex-col gap-6">
      <div>
        <SectionHeader title="Connection" description="Your account's sign-in to this service." />
        <Card className="flex flex-wrap items-center gap-3">
          <Badge variant={conn.status === 'CONNECTED' ? 'success' : conn.status === 'ERROR' || conn.status === 'NEEDS_REAUTH' ? 'danger' : 'default'}>
            {conn.status === 'CONNECTED' ? 'Connected' : conn.status === 'NEEDS_REAUTH' ? 'Needs sign-in again' : conn.status === 'ERROR' ? 'Error' : 'Not connected'}
          </Badge>
          <span className="flex-1 text-sm text-gray-500 dark:text-gray-400">
            {conn.authScheme === 'OAUTH2'
              ? conn.tokenPresent ? `Token${conn.tokenExpiresAt ? ` expires ${new Date(conn.tokenExpiresAt).toLocaleString()}` : ' stored'}` : 'Not signed in yet'
              : conn.clientSecretSet ? 'Credential stored (encrypted)' : conn.authScheme === 'NO_AUTH' ? 'No credential needed' : 'No credential yet'}
          </span>
          {conn.authScheme === 'OAUTH2' && <Can permission={PERMISSIONS.CONNECTOR_MODIFY} mode="disable"><Button size="sm" loading={connect.isPending} onClick={() => connect.mutate()}>Sign in</Button></Can>}
          <Can permission={PERMISSIONS.CONNECTOR_MODIFY} mode="disable"><Button size="sm" variant="secondary" loading={test.isPending} onClick={() => test.mutate()}>Test</Button></Can>
          <Can permission={PERMISSIONS.CONNECTOR_DELETE} mode="disable"><Button size="sm" variant="ghost" loading={revoke.isPending} onClick={() => revoke.mutate()}>Disconnect</Button></Can>
        </Card>
        {test.data && <p className={`mt-2 text-sm ${test.data.ok ? 'text-green-600' : 'text-red-500'}`}>{test.data.ok ? 'Test call succeeded.' : `Test failed: ${test.data.message ?? test.data.httpStatus}`}</p>}
        {(test.error || connect.error || revoke.error) && <p className="mt-2 text-sm text-red-500">{errorText(test.error || connect.error || revoke.error)}</p>}
      </div>

      <form onSubmit={submit}>
        <SectionHeader title="Authentication scheme" action={<Can permission={PERMISSIONS.CONNECTOR_MODIFY} mode="disable"><Button size="sm" type="submit" loading={save.isPending}>Save</Button></Can>} />
        <Card className="flex flex-col gap-4">
          <div role="radiogroup" aria-label="Authentication scheme" className="flex flex-wrap gap-2">
            {(Object.keys(AUTH_LABELS) as AuthScheme[]).map(k => (
              <button type="button" key={k} role="radio" aria-checked={scheme === k} onClick={() => setScheme(k)}
                className={`rounded-lg border px-3 py-1.5 text-sm ${scheme === k
                  ? 'border-brand-400 bg-brand-50 text-brand-700 dark:border-brand-600 dark:bg-brand-950/40 dark:text-brand-200'
                  : 'border-gray-200 text-gray-600 dark:border-gray-800 dark:text-gray-300'}`}>{AUTH_LABELS[k]}</button>
            ))}
          </div>

          {scheme === 'API_KEY' && <>
            <Input label="API key" type="password" value={form.clientSecret} onChange={set('clientSecret')}
              placeholder={conn.clientSecretSet ? '•••••••• (stored; leave blank to keep)' : ''} autoComplete="off" />
            <div className="grid gap-4 sm:grid-cols-3">
              <Input label="Sent as" value={form.keyName} onChange={set('keyName')} hint="Header or query name" />
              <Input label="Prefix" value={form.keyPrefix} onChange={set('keyPrefix')} hint='e.g. "Bearer " or "Zoho-oauthtoken "' />
              <Field label="In">
                <select className={fieldClass} value={form.keyIn} onChange={set('keyIn')}>
                  <option value="header">Header</option><option value="query">Query string</option>
                </select>
              </Field>
            </div>
          </>}

          {scheme === 'BASIC' && <div className="grid gap-4 sm:grid-cols-2">
            <Input label="Username" value={form.clientId} onChange={set('clientId')} />
            <Input label="Password" type="password" value={form.clientSecret} onChange={set('clientSecret')}
              placeholder={conn.clientSecretSet ? 'Stored; leave blank to keep' : ''} autoComplete="off" />
          </div>}

          {scheme === 'OAUTH2' && <>
            <Field label="OAuth redirect URI" hint="Register this with the provider.">
              <input className={`${fieldClass} font-mono`} readOnly value={redirectUri} onFocus={e => e.target.select()} />
            </Field>
            <div className="grid gap-4 sm:grid-cols-2">
              <Input label="Client ID" value={form.clientId} onChange={set('clientId')} />
              <Input label="Client secret" type="password" value={form.clientSecret} onChange={set('clientSecret')}
                placeholder={conn.clientSecretSet ? 'Stored; leave blank to keep' : ''} autoComplete="off" />
              <Input label="Authorization URL" value={form.authorizationUrl} onChange={set('authorizationUrl')} />
              <Input label="Access token URL" value={form.accessTokenUrl} onChange={set('accessTokenUrl')} />
              <Input label="Refresh token URL" value={form.refreshTokenUrl} onChange={set('refreshTokenUrl')} hint="Defaults to the access token URL" />
              <Input label="Scopes" value={form.scopes} onChange={set('scopes')} hint="Space separated" />
              <Field label="Access token placement">
                <select className={fieldClass} value={form.tokenPlacement} onChange={set('tokenPlacement')}>
                  <option value="HEADER">Authorization header</option><option value="QUERY">Query string</option><option value="BODY">Request body</option>
                </select>
              </Field>
              <label className="flex items-center gap-2 self-end pb-2 text-sm text-gray-700 dark:text-gray-300">
                <input type="checkbox" checked={pkce} onChange={e => setPkce(e.target.checked)} /> Use PKCE
              </label>
            </div>
          </>}

          {scheme === 'CUSTOM' && <p className="text-sm text-gray-500 dark:text-gray-400">
            Add the credential as a secret variable, then reference it from each API's fixed headers, for example
            <code className="mx-1 rounded bg-gray-100 px-1 dark:bg-gray-800">X-Api-Token: {'{{var.token}}'}</code>.
          </p>}
          {scheme === 'NO_AUTH' && <p className="text-sm text-gray-500 dark:text-gray-400">Requests are sent without credentials.</p>}

          {save.error && <p className="text-sm text-red-500">{errorText(save.error)}</p>}
          {save.isSuccess && <p className="text-sm text-green-600">Saved.</p>}
        </Card>
      </form>
    </div>
  )
}
