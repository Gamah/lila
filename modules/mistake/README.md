# mistake

Shows a player one of their own past mistakes at a time, at random, cold: a
board, the move they played drawn on it, "find something better".

`/mistakes` is the landing page and `/mistakes/play` is the board. The split is
not cosmetic: the board is not idempotent - arriving there hands out a position
and spends it - so it cannot also be the page you land on, get linked to, or
open in a background tab. Everything that spends something is a POST or lives
behind `/play`.

It is *not* puzzles. Nothing here reads or writes `puzzle2_puzzle`,
`PuzzleTier`, `PuzzleFinisher`, puzzle glicko or the puzzle dashboard. These
positions are personal, unrated, unvoted and shown to one person.

## How it differs from "learn from your mistakes"

Retro (`ui/analyse/src/retrospect/`) walks the mistakes of **one game, in
order, immediately after you've been looking at that game**. You often remember
the move.

This is the same exercise, shuffled: the only things that differ are *which
games* positions are drawn from, and the order. **Nothing about how a position
is found or judged differs from retro**, and anywhere the two would drift apart
is a bug in this module.

## Shape

- **Positions** are derived on every request, never stored. A candidate is the
  same eval swing retro looks for, `evalSwings` in
  `ui/analyse/src/nodeFinder.ts`, ported to `MistakeApi.swings`. Not
  `Analysis.advices`: that is a wider net, catching inaccuracies at half this
  swing and a broader set of mate sequences.
- **Winning chances** therefore need a Scala copy of
  `ui/lib/src/ceval/winningChances.ts` - see `WinningChances.scala` for why
  scalachess `WinPercent` is not that function.
- **Judging** is retro's: your move is measured against the eval of the
  position *before* the mistake, with the same 0.04 of slack. That test and the
  "is ceval ready" one live in `ui/lib/src/ceval/judge.ts`, and both features
  call it. The eval of the move that was played is never sent to the client.
- **Opening explorer**, as in retro: a move masters play counts as finding
  something better, and a position whose mistake is itself a book move is
  dropped rather than asked. The fetch is shared
  (`ui/lib/src/game/masterOpening.ts`); the page carries the explorer endpoint,
  and each deal carries the game's `divisionMiddle` so the client knows when it
  has left the opening.
- `MistakeApi.extract` builds the ordinary analyse tree with `TreeBuilder` and
  pulls one node out of it, rather than replaying the game by hand. The
  engine's line is already hanging off the pre-mistake node as a `comp` child,
  which is the same thing retro reads.
- **Window**: games created between 1 and 3 months ago by default
  (`MistakeWindow`). Recent games are skipped on purpose - they're still in
  your memory. The landing page can widen it, from a fixed list of choices: it
  travels in the URL as `?window=<since>-<until>` and there is no per-user
  setting behind it, so a window nobody picked is one we never spend fishnet
  on.
- **Supply**: `MistakeFishnet` hands unanalysed games in the window to fishnet
  with `Origin.autoMistake` (100k nodes/move, `slowOk`) so its metrics separate
  from tutor's. Unlike `TutorFishnet` it never awaits. It is asked by
  `POST /mistakes/analyse` and by nothing else - rendering a page has never had
  the right to spend engine time.
- **The one enqueue per user per hour lives in mongo**, in `mistake_request`,
  not in an `OnceEvery`. An in-memory limiter does not survive a deploy, and on
  production a rolling restart would let every active user re-enqueue. The same
  document holds the game ids of the batch, which is the only way the warming
  state knows how far along it is: fishnet tells us nothing when it finishes.
- **Memory**: one document per `(userId, gameId, ply)` in `mistake_solve`.
  Result and date, nothing else. Solving, failing then moving on, and skipping
  all retire a position, exactly as retro's `skip()` and `viewSolution()` both
  call `solveCurrent()`.
- **Withholding**: the client is never sent the game id. `MistakeCurrentStore`
  remembers which position each user is looking at, so answering says only
  *how it went*, and the server hands back the game link in reply.
- **Stats** on the landing come from `mistake_solve` and nothing else: how many
  you solved, gave up on and skipped, and the same over the last twenty. By
  phase, by piece and by opening are tutor's job and would be a worse copy of
  it, so the page links there instead. There is deliberately no "positions
  remaining": knowing it means deriving candidates for every game in the
  window, which is exactly the work the board does one game at a time.

## Renaming it

`/mistakes` is a working title. The name appears in exactly these places, all
of them the single token `mistake` (or the path `mistakes`):

| what | where |
| --- | --- |
| route path | `conf/routes` - the six `/mistakes*` lines |
| client's copy of the path | `ui/mistake/src/xhr.ts`, the `base` constant |
| module | `modules/mistake/`, package `lila.mistake`, `build.sbt` |
| controller | `app/controllers/Mistake.scala`, wired in `app/Lila.scala` |
| env | `app/Env.scala` |
| page view | `modules/mistake/src/main/ui/MistakeUi.scala`, `app/views/ui.scala` |
| js bundle | `ui/mistake/` (`package.json` name, `src/mistake.ts`, `src/mistake.landing.ts`) |
| css bundle | `ui/mistake/css/build/mistake.scss`, `mistake.home.scss` |
| collections | `mistake_solve`, `mistake_request` in `modules/mistake/src/main/Env.scala` |
| setting | `mistakeNbAnalysis` |
| fishnet origin | `Work.Origin.autoMistake` |
| i18n | `site.xml`: `shuffledMistakes`, `nothingLeftToShuffle`, `seeTheGameItCameFrom` |

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
- **Entry point.** The profile page links to it, beside the Chess Insights
  card. There is nothing in the nav yet, same as `/tutor`.
- **A "deck spent" state on the landing.** The landing cannot know the deck is
  empty without dealing a position to find out, which is the one thing it must
  not do, so that state lives on the board. Its two real actions - draw from
  further back, put the deck back - are on the landing, and the board links
  there.
- **Copy is hardcoded English**, as tutor's is. Only the three strings the
  board inherited from retro are in `site.xml`.
