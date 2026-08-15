import type { ClientEval } from '@/tree/types';

import { api } from '../api';
import { povDiff } from './winningChances';

/* Shared by "learn from your mistakes" on the analyse page and by the
 * /mistakes deck. Both ask the same question of local ceval - is the move you
 * just tried good enough? - but they measure it from different ends. */

/* Walking an analysed game, the position before the mistake is already on the
 * board, so you are being compared to the *best* move and allowed to fall a
 * little short of it. */
export const bestMoveMargin = -0.04;

/* Dealt a position cold, the move that was played is on the board from the
 * first second and the question is whether you improved on *it*. Judged
 * against the best move a position with one saving move would refuse every
 * other answer. Same constant, other side of zero: against the mistake you
 * have to beat it by more than noise. */
export const playedMoveMargin = 0.04;

export const isCevalReady = (ceval?: ClientEval): boolean => {
  if (!ceval) return false;
  return (
    api.overrides.learnFromMistakesEvalReady?.(structuredClone(ceval)) ??
    Boolean(ceval.bestmove || ceval.nodes >= 1_000_000 || (ceval.millis ?? 0) > 3000)
  );
};

export const beatsBaseline = (
  color: Color,
  candidate: EvalScore,
  baseline: EvalScore,
  margin: number,
): boolean => povDiff(color, candidate, baseline) > margin;
