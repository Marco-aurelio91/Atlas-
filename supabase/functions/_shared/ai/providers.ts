// Shared by atlas-ai and atlas-sdk — moved out of atlas-ai/index.ts verbatim (pure refactor, no
// behavior change). atlas-sdk only calls invokeAnthropic today, but all three are kept together
// since they share MAX_MESSAGES/MAX_TOOLS/MAX_OUTPUT_TOKENS and the record()/providerError()/relay()
// helpers from ./http.ts.
import { providerError, record, relay } from "./http.ts"

export const MAX_MESSAGES = 40
export const MAX_TOOLS = 32
export const MAX_OUTPUT_TOKENS = 4096

export async function invokeAnthropic(payload: Record<string, unknown>): Promise<Response> {
  const apiKey = Deno.env.get("ANTHROPIC_API_KEY")
  const model = Deno.env.get("ANTHROPIC_MODEL")
  if (!apiKey || !model) return providerError(503, "anthropic_not_configured")
  if (!Array.isArray(payload.messages) || payload.messages.length === 0 || payload.messages.length > MAX_MESSAGES) {
    return providerError(400, "invalid_messages")
  }
  if (payload.tools !== undefined && (!Array.isArray(payload.tools) || payload.tools.length > MAX_TOOLS)) {
    return providerError(400, "invalid_tools")
  }

  const requestedMax = Number(payload.max_tokens)
  if (!Number.isInteger(requestedMax) || requestedMax < 1) return providerError(400, "invalid_max_tokens")
  const requestBody: Record<string, unknown> = {
    model,
    max_tokens: Math.min(requestedMax, MAX_OUTPUT_TOKENS),
    stream: payload.stream === true,
    // Sonnet 5 defaults to high effort and adaptive thinking. Atlas handles mostly short,
    // conversational turns; lower effort reduces time to the first visible text while preserving
    // adaptive reasoning for requests that benefit from it.
    output_config: { effort: "low" },
    messages: payload.messages,
  }
  if (typeof payload.system === "string" || Array.isArray(payload.system)) requestBody.system = payload.system
  if (Array.isArray(payload.tools)) requestBody.tools = payload.tools
  if (record(payload.tool_choice)) requestBody.tool_choice = payload.tool_choice

  const upstream = await fetch("https://api.anthropic.com/v1/messages", {
    method: "POST",
    headers: {
      "content-type": "application/json",
      "anthropic-version": "2023-06-01",
      "x-api-key": apiKey,
    },
    body: JSON.stringify(requestBody),
    signal: AbortSignal.timeout(35_000),
  })
  return relay(upstream)
}

export async function invokeGeminiChat(payload: Record<string, unknown>): Promise<Response> {
  const apiKey = Deno.env.get("GEMINI_API_KEY")
  const model = Deno.env.get("GEMINI_CHAT_MODEL")
  if (!apiKey || !model) return providerError(503, "gemini_chat_not_configured")
  if (!Array.isArray(payload.contents) || payload.contents.length === 0 || payload.contents.length > MAX_MESSAGES) {
    return providerError(400, "invalid_contents")
  }

  const requestBody: Record<string, unknown> = { contents: payload.contents }
  if (record(payload.systemInstruction)) requestBody.systemInstruction = payload.systemInstruction
  const generationConfig = record(payload.generationConfig)
  if (generationConfig) {
    const requestedMax = Number(generationConfig.maxOutputTokens ?? 1024)
    if (!Number.isInteger(requestedMax) || requestedMax < 1) return providerError(400, "invalid_max_tokens")
    requestBody.generationConfig = {
      ...generationConfig,
      maxOutputTokens: Math.min(requestedMax, MAX_OUTPUT_TOKENS),
    }
  }
  const tools = Array.isArray(payload.tools) ? payload.tools : undefined
  if (tools && tools.length > MAX_TOOLS) return providerError(400, "invalid_tools")
  if (tools) requestBody.tools = tools

  const stream = payload.stream === true
  const generationMethod = stream ? "streamGenerateContent?alt=sse" : "generateContent"
  const upstream = await fetch(
    `https://generativelanguage.googleapis.com/v1beta/models/${encodeURIComponent(model)}:${generationMethod}`,
    {
      method: "POST",
      headers: { "content-type": "application/json", "x-goog-api-key": apiKey },
      body: JSON.stringify(requestBody),
      signal: AbortSignal.timeout(25_000),
    },
  )
  return relay(upstream)
}

export async function invokeGeminiEmbedding(payload: Record<string, unknown>): Promise<Response> {
  const apiKey = Deno.env.get("GEMINI_API_KEY")
  const model = Deno.env.get("GEMINI_EMBEDDING_MODEL") ?? "gemini-embedding-001"
  if (!apiKey) return providerError(503, "gemini_embedding_not_configured")

  const content = record(payload.content)
  const parts = content && Array.isArray(content.parts) ? content.parts : null
  const firstPart = parts?.length === 1 ? record(parts[0]) : null
  const text = firstPart?.text
  if (typeof text !== "string" || text.length === 0 || text.length > 6000) {
    return providerError(400, "invalid_embedding_input")
  }

  const upstream = await fetch(
    `https://generativelanguage.googleapis.com/v1beta/models/${encodeURIComponent(model)}:embedContent`,
    {
      method: "POST",
      headers: { "content-type": "application/json", "x-goog-api-key": apiKey },
      body: JSON.stringify({
        model: `models/${model}`,
        content: { parts: [{ text }] },
        outputDimensionality: 1536,
      }),
      signal: AbortSignal.timeout(30_000),
    },
  )
  return relay(upstream, { "X-Atlas-Embedding-Model": model })
}
