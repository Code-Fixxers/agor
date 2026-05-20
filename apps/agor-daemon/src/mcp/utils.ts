/**
 * Helper: coerce unknown value to trimmed non-empty string or undefined.
 */
export function coerceString(value: unknown): string | undefined {
  if (typeof value !== 'string') return undefined;
  const trimmed = value.trim();
  return trimmed.length > 0 ? trimmed : undefined;
}

/**
 * Helper: coerce a possibly-stringified JSON value to a Record, or return as-is.
 *
 * Some MCP clients double-serialize nested objects as JSON strings (especially
 * with large or complex content). This helper transparently parses those back.
 * Returns the original value unchanged if it's not a string or not valid JSON.
 */
export function coerceJsonRecord(value: unknown): unknown {
  if (typeof value !== 'string') return value;
  try {
    return JSON.parse(value);
  } catch {
    return value;
  }
}

/**
 * Clamp user-controlled MCP pagination limits. External API-key callers can
 * otherwise request huge result sets and turn list tools into accidental scans.
 */
export function clampMcpLimit(value: unknown, fallback: number, max = 100): number {
  const fallbackLimit = Math.min(Math.max(1, Math.floor(fallback)), max);
  if (typeof value !== 'number' || !Number.isFinite(value)) return fallbackLimit;
  const requested = Math.floor(value);
  if (requested <= 0) return fallbackLimit;
  return Math.min(requested, max);
}

export function clampMcpOffset(value: unknown, max = 10_000): number {
  const raw = typeof value === 'number' && Number.isFinite(value) ? value : 0;
  return Math.min(Math.max(0, Math.floor(raw) || 0), max);
}

/**
 * Helper: format a value as MCP text content response.
 */
export function textResult(data: unknown) {
  return {
    content: [{ type: 'text' as const, text: JSON.stringify(data, null, 2) }],
  };
}

export const SESSION_CONTEXT_REQUIRED_MESSAGE =
  'This tool requires session context. Pass X-Agor-Session-Id or ?sessionId= to scope the request.';

export function sessionContextRequiredResult() {
  return textResult({ error: SESSION_CONTEXT_REQUIRED_MESSAGE });
}
