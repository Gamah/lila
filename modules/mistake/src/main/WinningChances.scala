package lila.mistake

import chess.Color

import lila.tree.Eval

/* Port of `ui/lib/src/ceval/winningChances.ts`.
 *
 * The deck offers exactly the positions retro offers, and retro selects them in
 * the browser (`ui/analyse/src/nodeFinder.ts`), so the same measure has to
 * exist here. scalachess `WinPercent` is a different function: it returns a
 * 0-100 percent, and it flattens every mate to the centipawn ceiling instead of
 * grading it by distance. Keep this in sync with the TypeScript.
 */
private object WinningChances:

  private val multiplier = -0.00368208

  private def raw(cp: Double): Double = 2 / (1 + Math.exp(multiplier * cp)) - 1

  private def ofCp(cp: Int): Double = raw(Math.max(-1000, Math.min(1000, cp)))

  private def ofMate(mate: Int): Double =
    raw((21 - Math.min(10, Math.abs(mate))) * 100 * (if mate > 0 then 1 else -1))

  private def of(eval: Eval): Option[Double] =
    eval.mate.map(m => ofMate(m.value)).orElse(eval.cp.map(cp => ofCp(cp.value)))

  private def pov(color: Color, eval: Eval): Option[Double] =
    of(eval).map(v => color.fold(v, -v))

  /* Difference in winning chances between two evals, from `color`'s point of
   * view. Halved, like the TypeScript: a 0.1 povDiff is a 0.2 raw swing. */
  def povDiff(color: Color, e1: Eval, e2: Eval): Option[Double] =
    for
      a <- pov(color, e1)
      b <- pov(color, e2)
    yield (a - b) / 2
