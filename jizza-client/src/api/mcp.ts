import { api } from '@/lib/api'
import type { ActionType, McpCatalog, McpServer, McpServerInput, ToolRunResult } from '@/types'

const base = (id: string) => `/api/mcp-servers/${id}`

/** Remote MCP servers added as connectors: their sign-in, tools and agents. */
export const mcpApi = {
  list: () => api.get<McpServer[]>('/api/mcp-servers'),
  /** Ready-made servers to install with one click. */
  catalog: () => api.get<McpCatalog>('/api/mcp-servers/catalog'),
  get: (id: string) => api.get<McpServer>(base(id)),
  create: (body: McpServerInput) => api.post<McpServer>('/api/mcp-servers', body),
  update: (id: string, body: McpServerInput) => api.patch<McpServer>(base(id), body),
  remove: (id: string) => api.delete<void>(base(id)),
  refresh: (id: string) => api.post<McpServer>(`${base(id)}/refresh`),
  updateTool: (id: string, tool: string, body: { enabled?: boolean; actionType?: ActionType }) =>
    api.patch<McpServer>(`${base(id)}/tools/${encodeURIComponent(tool)}`, body),
  testTool: (id: string, tool: string, args: unknown) =>
    api.post<ToolRunResult>(`${base(id)}/tools/${encodeURIComponent(tool)}/test`, args ?? {}),
  authorize: (id: string) => api.post<{ url: string }>(`${base(id)}/authorize`),
  signOut: (id: string) => api.post<McpServer>(`${base(id)}/sign-out`),
}
