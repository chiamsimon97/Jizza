import { describe, expect, it } from 'vitest'
import { filterCatalog, sameUrl, signInLabel } from './McpCatalog'
import type { McpCatalogEntry } from '@/types'

const entry = (over: Partial<McpCatalogEntry>): McpCatalogEntry => ({
  id: 'x', name: 'X', publisher: 'Acme', category: 'personal', description: 'Does things',
  url: 'https://mcp.example.com/mcp', authType: 'NONE', ownClient: false, agentKeys: ['jizza'], ...over,
})

describe('the MCP catalog', () => {
  const entries = [
    entry({ id: 'gmail', name: 'Gmail', publisher: 'Google', category: 'personal', authType: 'OAUTH', ownClient: true }),
    entry({ id: 'kite', name: 'Zerodha Kite', publisher: 'Zerodha', category: 'finance', description: 'Holdings and orders' }),
    entry({ id: 'pubmed', name: 'PubMed', category: 'health', description: 'Biomedical literature' }),
  ]

  it('filters by category and search', () => {
    expect(filterCatalog(entries, 'all', '').map(e => e.id)).toEqual(['gmail', 'kite', 'pubmed'])
    expect(filterCatalog(entries, 'finance', '').map(e => e.id)).toEqual(['kite'])
    expect(filterCatalog(entries, 'all', 'google').map(e => e.id)).toEqual(['gmail'])
    expect(filterCatalog(entries, 'all', 'LITERATURE').map(e => e.id)).toEqual(['pubmed'])
    expect(filterCatalog(entries, 'health', 'kite')).toEqual([])
  })

  it('says how each one signs in', () => {
    expect(signInLabel(entries[0])).toBe('Your own OAuth client')
    expect(signInLabel(entries[1])).toBe('No sign-in')
    expect(signInLabel(entry({ authType: 'OAUTH', publisher: 'Notion' }))).toBe('Sign in with Notion')
    expect(signInLabel(entry({ authType: 'BEARER', secretLabel: 'Personal access token' }))).toBe('Personal access token')
  })

  it('knows an installed server by its address', () => {
    expect(sameUrl('https://mcp.kite.trade/mcp', 'https://MCP.kite.trade/mcp/')).toBe(true)
    expect(sameUrl('https://mcp.kite.trade/mcp', 'https://mcp.kite.trade/sse')).toBe(false)
  })
})
