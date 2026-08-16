import type { DrawModifiers, DrawShape } from '@lichess-org/chessground/draw';
import { isDrop } from 'chessops/types';
import { parseUci, makeSquare } from 'chessops/util';

/* Turning a uci into board arrows. Shared so that the same move is drawn the
 * same way wherever it appears - notably the red "you played this" arrow, which
 * "learn from your mistakes" draws on the analyse board and the /mistakes deck
 * draws on its own. */

const pieceDrop = (key: Key, role: Role, color: Color): DrawShape => ({
  orig: key,
  piece: {
    color,
    role,
    scale: 0.8,
  },
  brush: 'green',
});

export function makeShapesFromUci(
  color: Color,
  uci: Uci | undefined,
  brush: string,
  modifiers?: DrawModifiers,
): DrawShape[] {
  if (!uci || uci === 'Current Position') return [];
  const move = parseUci(uci)!;
  const to = makeSquare(move.to);
  if (isDrop(move)) return [{ orig: to, brush }, pieceDrop(to, move.role, color)];

  const shapes: DrawShape[] = [{ orig: makeSquare(move.from), dest: to, brush, modifiers }];
  if (move.promotion) shapes.push(pieceDrop(to, move.promotion, color));
  return shapes;
}

/* How the move you actually played is drawn: pale red, thick. Retro's
 * ui/analyse/src/autoShape.ts uses it for the node being solved. */
export const playedMoveBrush = 'paleRed';
export const playedMoveModifiers: DrawModifiers = { lineWidth: 8 };
