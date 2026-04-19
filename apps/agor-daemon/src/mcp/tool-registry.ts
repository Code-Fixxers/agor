/**
 * Tool Registry — Captures tool metadata for search-based discovery.
 *
 * When tool search is enabled, agents see only a few essential tools in
 * `tools/list` and discover the rest via `agor_search_tools`. All tools
 * remain registered and callable; only the listing is filtered.
 *
 * Tools are organized into domains (e.g. "sessions", "worktrees", "cards")
 * and support progressive detail levels and annotation filtering.
 */

import type { ToolAnnotations } from '@modelcontextprotocol/sdk/types.js';

const TOOL_DESCRIPTION_MAX = 180;
const TOOL_SUMMARY_DESCRIPTION_MAX = 100;
const ROOT_SCHEMA_DESCRIPTION_MAX = 120;
const NESTED_SCHEMA_DESCRIPTION_MAX = 80;

export interface ToolEntry {
  name: string;
  description: string;
  inputSchema: Record<string, unknown>;
  annotations?: ToolAnnotations;
  domain: string;
}

/** Lightweight tool info returned for "list" detail level. */
export interface ToolSummary {
  name: string;
  description: string;
  domain: string;
}

export interface DomainInfo {
  domain: string;
  description: string;
  count: number;
}

export interface SearchOptions {
  maxResults?: number;
  domain?: string;
  readOnly?: boolean;
  destructive?: boolean;
}

/** Domain descriptions for the domain listing. */
const DOMAIN_DESCRIPTIONS: Record<string, string> = {
  sessions: 'Agent sessions, genealogy, prompts, tasks, messages',
  repos: 'Repositories',
  worktrees: 'Git worktrees, zones, assistants',
  environment: 'Worktree dev environments',
  boards: 'Spatial canvases',
  cards: 'Kanban cards + types',
  artifacts: 'Sandpack artifacts',
  users: 'Users & admin',
  analytics: 'Usage leaderboard',
  'mcp-servers': 'External MCP configs',
  proxies: 'Configured HTTP proxies for third-party APIs',
};

/** Tools always visible in `tools/list` even when search mode is enabled. */
const ALWAYS_VISIBLE = new Set(['agor_search_tools', 'agor_execute_tool']);

function normalizeText(value: string, maxLength: number): string {
  const normalized = value.replace(/\s+/g, ' ').trim();
  if (normalized.length <= maxLength) return normalized;
  return `${normalized.slice(0, maxLength - 1).trimEnd()}…`;
}

function compactJsonSchema(value: unknown, depth = 0): unknown {
  if (Array.isArray(value)) {
    return value.map((item) => compactJsonSchema(item, depth + 1));
  }
  if (!value || typeof value !== 'object') return value;

  const input = value as Record<string, unknown>;
  const output: Record<string, unknown> = {};

  for (const [key, child] of Object.entries(input)) {
    if (key === 'title') continue;
    if (key === 'description' && typeof child === 'string') {
      const maxLength = depth <= 1 ? ROOT_SCHEMA_DESCRIPTION_MAX : NESTED_SCHEMA_DESCRIPTION_MAX;
      output[key] = normalizeText(child, maxLength);
      continue;
    }
    output[key] = compactJsonSchema(child, depth + 1);
  }

  return output;
}

export class ToolRegistry {
  private tools: Map<string, ToolEntry> = new Map();
  private currentDomain = 'general';

  /** Set the domain for subsequent register() calls. */
  setCurrentDomain(domain: string): void {
    this.currentDomain = domain;
  }

  register(entry: Omit<ToolEntry, 'domain'>): void {
    this.tools.set(entry.name, {
      ...entry,
      description: normalizeText(entry.description, TOOL_DESCRIPTION_MAX),
      inputSchema: compactJsonSchema(entry.inputSchema) as Record<string, unknown>,
      domain: this.currentDomain,
    });
  }

  get size(): number {
    return this.tools.size;
  }

  /** Return only the always-visible tools (for filtered tools/list). */
  getAlwaysVisible(): ToolEntry[] {
    const result: ToolEntry[] = [];
    for (const [name, entry] of this.tools) {
      if (ALWAYS_VISIBLE.has(name)) result.push(entry);
    }
    return result;
  }

  /** Return domain listing with descriptions and tool counts. */
  listDomains(): DomainInfo[] {
    const counts = new Map<string, number>();
    for (const entry of this.tools.values()) {
      if (ALWAYS_VISIBLE.has(entry.name)) continue;
      counts.set(entry.domain, (counts.get(entry.domain) ?? 0) + 1);
    }
    const domains: DomainInfo[] = [];
    for (const [domain, count] of counts) {
      domains.push({
        domain,
        description: DOMAIN_DESCRIPTIONS[domain] ?? domain,
        count,
      });
    }
    return domains;
  }

  /** Apply domain and annotation filters, returning matching entries. */
  private applyFilters(options?: SearchOptions): ToolEntry[] {
    let entries = Array.from(this.tools.values());

    if (options?.domain) {
      entries = entries.filter((e) => e.domain === options.domain);
    }
    if (options?.readOnly !== undefined) {
      entries = entries.filter((e) => e.annotations?.readOnlyHint === options.readOnly);
    }
    if (options?.destructive !== undefined) {
      entries = entries.filter((e) => e.annotations?.destructiveHint === options.destructive);
    }

    return entries;
  }

  /** Search tools by keyword with optional domain/annotation filters. */
  search(query: string | undefined, options?: SearchOptions): ToolEntry[] {
    const maxResults = options?.maxResults ?? 10;
    const filtered = this.applyFilters(options);

    // No query — return filtered results (or all if no filters)
    if (!query || query.trim().length === 0) {
      return filtered.slice(0, maxResults);
    }

    const terms = query
      .toLowerCase()
      .split(/\s+/)
      .filter((t) => t.length > 0);

    const scored: Array<{ entry: ToolEntry; score: number }> = [];

    for (const entry of filtered) {
      const haystack = `${entry.name} ${entry.description} ${entry.domain}`.toLowerCase();
      let score = 0;
      for (const term of terms) {
        if (haystack.includes(term)) score++;
      }
      if (score > 0) scored.push({ entry, score });
    }

    scored.sort((a, b) => b.score - a.score);
    return scored.slice(0, maxResults).map((s) => s.entry);
  }

  /** Convert entries to summary format (list detail level). */
  static toSummaries(entries: ToolEntry[]): ToolSummary[] {
    return entries.map((e) => ({
      name: e.name,
      description: normalizeText(e.description, TOOL_SUMMARY_DESCRIPTION_MAX),
      domain: e.domain,
    }));
  }
}
