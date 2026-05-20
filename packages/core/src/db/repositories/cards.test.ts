/**
 * CardRepository tests.
 */

import type { BoardID, CardID } from '@agor/core/types';
import { describe, expect } from 'vitest';
import { generateId } from '../../lib/ids';
import type { Database } from '../client';
import { insert } from '../database-wrapper';
import { boards } from '../schema';
import { dbTest } from '../test-helpers';
import { BoardObjectRepository } from './board-objects';
import { CardRepository } from './cards';

async function createBoard(db: Database): Promise<BoardID> {
  const boardId = generateId() as BoardID;
  await insert(db, boards)
    .values({
      board_id: boardId,
      created_at: new Date(),
      created_by: 'test-user',
      name: 'Test Board',
      data: {},
    })
    .run();
  return boardId;
}

describe('CardRepository.findByZoneId', () => {
  dbTest('filters by zone with archive and pagination constraints', async ({ db }) => {
    const cardRepo = new CardRepository(db);
    const boardObjectRepo = new BoardObjectRepository(db);
    const boardId = await createBoard(db);

    const first = await cardRepo.create({ board_id: boardId, title: 'First' });
    const archived = await cardRepo.create({ board_id: boardId, title: 'Archived' });
    const second = await cardRepo.create({ board_id: boardId, title: 'Second' });
    const otherZone = await cardRepo.create({ board_id: boardId, title: 'Other Zone' });

    for (const card of [first, archived, second]) {
      await boardObjectRepo.create({
        board_id: boardId,
        card_id: card.card_id as CardID,
        position: { x: 0, y: 0 },
        zone_id: 'todo',
      });
    }
    await boardObjectRepo.create({
      board_id: boardId,
      card_id: otherZone.card_id as CardID,
      position: { x: 0, y: 0 },
      zone_id: 'done',
    });

    await cardRepo.archive(archived.card_id);

    const unarchived = await cardRepo.findByZoneId(boardId, 'todo', {
      archived: false,
      limit: 10,
    });
    expect(new Set(unarchived.map((card) => card.card_id))).toEqual(
      new Set([first.card_id, second.card_id])
    );

    const pageOne = await cardRepo.findByZoneId(boardId, 'todo', { archived: false, limit: 1 });
    const pageTwo = await cardRepo.findByZoneId(boardId, 'todo', {
      archived: false,
      limit: 1,
      offset: 1,
    });

    expect(pageOne).toHaveLength(1);
    expect(pageTwo).toHaveLength(1);
    expect(pageOne[0].card_id).not.toBe(pageTwo[0].card_id);
  });
});
