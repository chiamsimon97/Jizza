/**
 * Text another page hands to the chat box: "Ask Kai" on a Portfolio screen opens the Chat page with
 * a question about that screen already typed, for the user to finish and send. Nothing is sent.
 */
let pending: string | null = null

export function setChatDraft(text: string) {
  pending = text
}

/** The handed-over text, once; later calls get null. */
export function takeChatDraft(): string | null {
  const text = pending
  pending = null
  return text
}
