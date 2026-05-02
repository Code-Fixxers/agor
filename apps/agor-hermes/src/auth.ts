import { timingSafeEqual } from 'node:crypto';
import type { FastifyReply, FastifyRequest } from 'fastify';

/**
 * Bearer-token auth between the Android app and Hermes.
 *
 * Both ends are inside a Tailnet, so this is a soft second factor on top of
 * Tailscale's mesh identity. Constant-time comparison still applies — the
 * Tailnet protects against external attackers, not buggy clients in the LAN.
 */
export function makeBearerAuth(expected: string) {
  const expectedBuf = Buffer.from(`Bearer ${expected}`);
  return async function bearerAuth(req: FastifyRequest, reply: FastifyReply) {
    const header = req.headers.authorization ?? '';
    const got = Buffer.from(header);
    if (got.length !== expectedBuf.length || !timingSafeEqual(got, expectedBuf)) {
      reply.code(401).send({ error: 'unauthorized' });
    }
  };
}
