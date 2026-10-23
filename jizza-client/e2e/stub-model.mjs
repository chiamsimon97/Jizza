// A stand-in for the model provider's Messages API, for the browser end-to-end suite.
//
// The backend is pointed at it with ANTHROPIC_BASE_URL. It answers every request with a short,
// predictable reply (streamed or not, as asked), so a test can assert what the app does with a
// reply rather than what a real model happens to say. It never calls a tool.
import http from 'node:http'

const port = Number(process.env.STUB_MODEL_PORT ?? 4010)

function lastUserText(body) {
  const messages = Array.isArray(body?.messages) ? body.messages : []
  for (let i = messages.length - 1; i >= 0; i--) {
    const m = messages[i]
    if (m.role !== 'user') continue
    if (typeof m.content === 'string') return m.content
    const text = (m.content ?? []).find(c => c.type === 'text')
    if (text) return text.text
  }
  return ''
}

function replyFor(body) {
  const asked = lastUserText(body).replace(/\s+/g, ' ').trim().slice(0, 80)
  return `Test model reply to: ${asked || 'your message'}`
}

const server = http.createServer((req, res) => {
  if (req.method === 'GET' && req.url === '/health') {
    res.writeHead(200, { 'content-type': 'text/plain' }).end('ok')
    return
  }
  if (req.method !== 'POST' || !req.url?.startsWith('/v1/messages')) {
    res.writeHead(404, { 'content-type': 'application/json' }).end('{"error":"not found"}')
    return
  }
  let raw = ''
  req.on('data', chunk => { raw += chunk })
  req.on('end', () => {
    let body = {}
    try { body = JSON.parse(raw) } catch { /* answered below */ }
    const text = replyFor(body)
    const usage = { input_tokens: 12, output_tokens: 8 }
    const model = body.model ?? 'stub-model'
    if (!body.stream) {
      res.writeHead(200, { 'content-type': 'application/json' })
      res.end(JSON.stringify({
        id: 'msg_stub', type: 'message', role: 'assistant', model,
        content: [{ type: 'text', text }], stop_reason: 'end_turn', usage,
      }))
      return
    }
    res.writeHead(200, { 'content-type': 'text/event-stream', 'cache-control': 'no-cache' })
    const send = (event, data) => res.write(`event: ${event}\ndata: ${JSON.stringify(data)}\n\n`)
    send('message_start', { type: 'message_start', message: {
      id: 'msg_stub', type: 'message', role: 'assistant', model, content: [],
      stop_reason: null, usage: { input_tokens: usage.input_tokens, output_tokens: 0 } } })
    send('content_block_start', { type: 'content_block_start', index: 0, content_block: { type: 'text', text: '' } })
    for (const piece of text.match(/.{1,12}/g) ?? []) {
      send('content_block_delta', { type: 'content_block_delta', index: 0, delta: { type: 'text_delta', text: piece } })
    }
    send('content_block_stop', { type: 'content_block_stop', index: 0 })
    send('message_delta', { type: 'message_delta', delta: { stop_reason: 'end_turn' }, usage: { output_tokens: usage.output_tokens } })
    send('message_stop', { type: 'message_stop' })
    res.end()
  })
})

server.listen(port, () => console.log(`stub model listening on ${port}`))
