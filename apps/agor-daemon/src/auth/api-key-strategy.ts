/**
 * API Key Authentication Strategy
 *
 * Authenticates requests using personal API keys (agor_sk_...).
 * Supports both Authorization: Bearer and X-API-Key headers.
 */

import { createHash } from 'node:crypto';
import type { UserApiKeysRepository } from '@agor/core/db';
import { AuthenticationBaseStrategy, NotAuthenticated } from '@agor/core/feathers';

interface CachedKey {
  keyRow: { id: string; user_id: string };
  expiresAt: number;
}

const CACHE_TTL_MS = 60 * 60 * 1000; // 1 hour
const LAST_USED_DEBOUNCE_MS = 60 * 1000; // 1 minute

// Module-level caches so they survive across ApiKeyStrategy instantiations
// (Feathers/MCP creates a new instance or calls it statelessly)
const globalKeyCache = new Map<string, CachedKey>();
const globalLastUsedWrites = new Map<string, number>();

function fingerprintApiKey(apiKey: string): string {
  return createHash('sha256').update(apiKey).digest('hex');
}

export function clearApiKeyAuthCacheForKeyId(keyId: string): void {
  for (const [fingerprint, cached] of globalKeyCache.entries()) {
    if (cached.keyRow.id === keyId) {
      globalKeyCache.delete(fingerprint);
    }
  }
  globalLastUsedWrites.delete(keyId);
}

export class ApiKeyStrategy extends AuthenticationBaseStrategy {
  private apiKeysRepo: UserApiKeysRepository | null = null;
  // biome-ignore lint/suspicious/noExplicitAny: Feathers service type
  private usersService: any = null;

  // biome-ignore lint/suspicious/noExplicitAny: Feathers service type
  setDependencies(apiKeysRepo: UserApiKeysRepository, usersService: any) {
    this.apiKeysRepo = apiKeysRepo;
    this.usersService = usersService;
  }

  // biome-ignore lint/suspicious/noExplicitAny: Feathers type compatibility
  async authenticate(authentication: any, params: any): Promise<any> {
    if (!this.apiKeysRepo || !this.usersService) {
      throw new NotAuthenticated('ApiKeyStrategy not initialized');
    }

    const apiKey = authentication.apiKey;
    if (!apiKey || typeof apiKey !== 'string' || !apiKey.startsWith('agor_sk_')) {
      throw new NotAuthenticated('Invalid API key format');
    }

    const now = Date.now();
    let keyRow: { id: string; user_id: string } | null = null;
    const cacheKey = fingerprintApiKey(apiKey);

    // Check cache
    const cached = globalKeyCache.get(cacheKey);
    if (cached && cached.expiresAt > now) {
      keyRow = cached.keyRow;
    } else {
      // Cache miss or expired: Verify key against stored hashes
      keyRow = await this.apiKeysRepo.verifyKey(apiKey);
      if (!keyRow) {
        throw new NotAuthenticated('Invalid API key');
      }
      globalKeyCache.set(cacheKey, {
        keyRow,
        expiresAt: now + CACHE_TTL_MS,
      });
    }

    // Debounce last_used_at updates (non-blocking)
    const lastWrite = globalLastUsedWrites.get(keyRow.id) || 0;
    if (now - lastWrite > LAST_USED_DEBOUNCE_MS) {
      globalLastUsedWrites.set(keyRow.id, now);
      this.apiKeysRepo.updateLastUsed(keyRow.id).catch((err: unknown) => {
        console.warn('Failed to update API key last_used_at:', err);
      });
    }

    // Load the user
    const user = await this.usersService.get(keyRow.user_id);
    if (!user) {
      throw new NotAuthenticated('User not found for API key');
    }

    return {
      authentication: { strategy: 'api-key' },
      user,
    };
  }

  /**
   * Parse API key from request headers.
   * Supports:
   * - Authorization: Bearer agor_sk_...
   * - X-API-Key: agor_sk_...
   */
  // biome-ignore lint/suspicious/noExplicitAny: Feathers req type
  async parse(req: any): Promise<{ strategy: string; apiKey: string } | null> {
    // Check X-API-Key header first
    const xApiKey = req.headers?.['x-api-key'];
    if (xApiKey && typeof xApiKey === 'string' && xApiKey.startsWith('agor_sk_')) {
      return { strategy: 'api-key', apiKey: xApiKey };
    }

    // Check Authorization: Bearer header
    const authorization = req.headers?.authorization;
    if (authorization && typeof authorization === 'string') {
      const [scheme, token] = authorization.split(' ');
      if (scheme?.toLowerCase() === 'bearer' && token?.startsWith('agor_sk_')) {
        return { strategy: 'api-key', apiKey: token };
      }
    }

    return null;
  }
}
