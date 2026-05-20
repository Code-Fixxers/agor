import { describe, expect, it } from 'vitest';
import { clampMcpLimit, clampMcpOffset } from './utils.js';

describe('MCP pagination helpers', () => {
  it('clamps limits to the configured maximum', () => {
    expect(clampMcpLimit(10_000, 10)).toBe(100);
    expect(clampMcpLimit(10_000, 200, 500)).toBe(500);
  });

  it('uses the fallback for missing, invalid, or non-positive limits', () => {
    expect(clampMcpLimit(undefined, 10)).toBe(10);
    expect(clampMcpLimit(Number.NaN, 10)).toBe(10);
    expect(clampMcpLimit(0, 10)).toBe(10);
    expect(clampMcpLimit(-1, 10)).toBe(10);
  });

  it('normalizes offsets to non-negative integers', () => {
    expect(clampMcpOffset(undefined)).toBe(0);
    expect(clampMcpOffset(-5)).toBe(0);
    expect(clampMcpOffset(4.9)).toBe(4);
  });

  it('caps offsets to avoid unbounded skip scans', () => {
    expect(clampMcpOffset(100_000)).toBe(10_000);
    expect(clampMcpOffset(100_000, 500)).toBe(500);
  });
});
