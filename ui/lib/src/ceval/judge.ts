import type { ClientEval } from '@/tree/types';

import { api } from '../api';
import { povDiff } from './winningChances';

/* Shared by "learn from your mistakes" on the analyse page and by the
 * /mistakes deck. Both ask local ceval the same question: is the move you just
 * tried good enough? */

/* Your move is compared to the *best* move - the eval of the position before
 * the mistake - and allowed to fall a little short of it. */
export const bestMoveMargin = -0.04;

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
