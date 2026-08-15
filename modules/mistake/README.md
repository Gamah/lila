# mistake

Deals a player one of their own past mistakes at a time, at random, cold: a
board, the move they played drawn on it, "find something better". Served at
`/mistakes`.

It is *not* puzzles. Nothing here reads or writes `puzzle2_puzzle`,
`PuzzleTier`, `PuzzleFinisher`, puzzle glicko or the puzzle dashboard. These
positions are personal, unrated, unvoted and shown to one person.

## How it differs from "learn from your mistakes"

Retro (`ui/analyse/src/retrospect/`) walks the mistakes of **one game, in
order, immediately after you've been looking at that game**. You often remember
the move.

Shuffled, you meet the position cold. That changes one thing in the judging:
retro measures your move against the position *before* the mistake, ie against
the best move, and lets you fall 0.04 short of it. Here the move that was
played is on the board from the first second, so the baseline is the eval
*after* that move, and the tolerance flips to +0.04. Both constants and the
"is ceval ready" test live in one place, `ui/lib/src/ceval/judge.ts`, and both
features call it.

## Shape

- **Positions** are derived on every request, never stored. A candidate is a
  `(gameId, ply)` where `Analysis.advices` has an `Advice` for your colour -
  the existing thresholds in `modules/tree/src/main/Advice.scala`.
- `MistakeApi.extract` builds the ordinary analyse tree with `TreeBuilder` and
  pulls one node out of it, rather than replaying the game by hand. The
  engine's line is already hanging off the pre-mistake node as a `comp` child,
  which is the same thing retro reads.
- **Window**: games created between 1 and 3 months ago (`MistakeWindow`).
  Recent games are skipped on purpose - they're still in your memory.
- **Supply**: `MistakeFishnet` hands unanalysed games in that window to
  fishnet, once per user per hour, with `Origin.autoMistake` (100k nodes/move,
  `slowOk`) so its metrics separate from tutor's. Unlike `TutorFishnet` it
  never awaits.
- **Memory**: one document per `(userId, gameId, ply)` in `mistake_solve`.
  Result and date, nothing else. Solving, failing then moving on, and skipping
  all retire a position, exactly as retro's `skip()` and `viewSolution()` both
  call `solveCurrent()`.
- **Withholding**: the client is never sent the game id. `MistakeDealStore`
  remembers which position each user is looking at, so answering says only
  *how it went*, and the server hands back the game link in reply.

## Renaming it

`/mistakes` is a working title. The name appears in exactly these places, all
of them the single token `mistake` (or the path `mistakes`):

| what | where |
| --- | --- |
| route path | `conf/routes` - the four `/mistakes*` lines |
| client's copy of the path | `ui/mistake/src/xhr.ts`, the `base` constant |
| module | `modules/mistake/`, package `lila.mistake`, `build.sbt` |
| controller | `app/controllers/Mistake.scala`, wired in `app/Lila.scala` |
| env | `app/Env.scala` |
| page view | `modules/mistake/src/main/ui/MistakeUi.scala`, `app/views/ui.scala` |
| js bundle | `ui/mistake/` (`package.json` name, `src/mistake.ts`) |
| css bundle | `ui/mistake/css/build/mistake.scss` |
| collection | `mistake_solve` in `modules/mistake/src/main/Env.scala` |
| setting | `mistakeNbAnalysis` |
| fishnet origin | `Work.Origin.autoMistake` |
| i18n | `site.xml`: `shuffledMistakes`, `oneOfYourOwnMistakes`, `nothingLeftToShuffle`, `seeTheGameItCameFrom`, `dealAnother`, `nbGamesQueuedForAnalysis`, `nbMistakesSolved` |

Nothing else derives the name from anything, and no user visible string builds
it out of the token, so a rename is a rename of that list.

## Not done here

- **Access for someone else's deck.** `/mistakes` is your own only. The issue
  suggested `Tutor.WithUser` (you, a `SeeInsight` mod, or your class teacher),
  but solving is interactive and records against the solver, so what a teacher
  would be looking at needs deciding before it's built.
- **Rank gate** ("your move must be in the engine's top 5"). Out by default and
  not implemented.
- **Variants.** Candidates are standard games only (`Query.variantStandard`).
- **Entry point.** There is no link to `/mistakes` in the nav yet, same as
  `/tutor`.
