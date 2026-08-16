# mistake

**Mistakes** on the page, `mistake` in the code, `/mistakes` in the URL: one
name all the way down. Everything below uses it.

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
  and each position carries the game's `divisionMiddle` so the client knows
  when it has left the opening.
- `MistakeApi.extract` builds the ordinary analyse tree with `TreeBuilder` and
  pulls one node out of it, rather than replaying the game by hand. The
  engine's line is already hanging off the pre-mistake node as a `comp` child,
  which is the same thing retro reads.
- **Window**: games created between 1 and 3 months ago by default
  (`MistakeWindow`). Recent games are skipped by default - they're still in
  your memory. It travels in the URL as `?window=<since>-<until>` in months and
  there is no per-user setting behind it, so a window nobody picked is one we
  never spend fishnet on.

  The landing sets it with two selects, a from and a to. Each half is its own
  whitelist - `sinceChoices` and `untilChoices` - rather than a free number,
  and `MistakeWindow.valid` checks both halves and that the range is not
  inverted. That check is what the old flat list of four pairs got for free.

  Widening `since` is the half that costs fishnet, which is why it has the
  shorter list; narrowing `until` is free, being a subset of a window we
  already pay for.

  **`until` can be 0, and the default is not.** This README used to state the
  opposite as an invariant. It was reversed in Gamah/lila#5: coldness is what
  the feature is for, so it is what you get without asking, but the board's
  empty state tells you to go and play some more games, and that is only an
  answer if the games you then play can reach the queue. With a floor of one
  month they never could. So "to today" is offered, at the far end of the
  `to` select, and picking it is the user saying they would rather have warm
  positions than none.
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
  Result, date, and the board and colour it was played on. Solving, failing
  then moving on, and skipping all retire a position, exactly as retro's
  `skip()` and `viewSolution()` both call `solveCurrent()`.

  The board on the document is a denormalisation, and it is the one place this
  module departs from how the rest of the site draws a position. Everywhere
  else - puzzle history, `Puzzle.ofPlayer`, every game list - the board comes
  from the entity the row points at, and no history row anywhere stores a fen.
  There is no such entity here: a position is derived and never kept, so the
  answer is the only record of it there will ever be. Rows written before
  Gamah/lila#6 have neither field and render as a date and a result.
- **The position arrives in one piece**: game, ply, colour and the move that
  led into it, all in `MistakeJson.position`. The board starts a ply back and
  plays that move in, the way a puzzle plays its first move, and the red arrow
  for the mistake is drawn only once it lands. `MistakeCurrentStore` still
  remembers which position each user has in flight, not to keep it secret but
  so `record` resolves what is being answered server side and a stale tab
  cannot record against whatever it still has on screen.

  The link into the game is offered only once the position has been answered.
  Not withholding: `/{gameId}/{colour}#{ply}` is `Round.watcher`, which hands
  any finished game to `analyseC.replay` (`app/controllers/Round.scala:153`),
  and that page annotates the very move being asked about. There is no
  ply-linked view of a game on lila without the engine's verdict on it, so the
  link is the solution with extra steps.
- **Stats** on the landing come from `mistake_solve` and nothing else: how many
  you solved, gave up on and skipped, and the same over the last twenty. By
  phase, by piece and by opening belong elsewhere and would be a worse copy of
  what already exists. There is deliberately no "positions remaining": knowing
  it means deriving candidates for every game in the window, which is exactly
  the work the board does one game at a time.

  What the landing does say about the queue is **how many positions you have
  answered out of the window you are looking at** - `MistakeHome.nbAnswered`,
  one `countSel` on `mistake_solve` over the game ids `next` would draw on. It
  is the honest half of the "how many are left" that #2 promised and #4 built a
  page around: it reports what has come out of the queue and makes no claim
  about what is still in it. Bounded by `maxGamesToConsider` for the same
  reason `next` is, so on a user with more games than that in the window it is
  a count over the pool the board can actually reach, not over the whole window.

  The list of what you *have* answered is `/mistakes/answered`, its own route
  as `/storm/dashboard` and `/training/history` are their own routes. It is the
  whole list, newest first, server paginated with `infinite-scroll`, filtered
  by result in the URL, and every position is drawn rather than described -
  which is what the first attempt at it got wrong and was cut for.

  It is **not** scoped to the window. The window governs what the queue draws
  from; what you have already answered is a different question, and the two
  being the same control would be an accident rather than a decision. The
  window is still carried in that page's URL, so the tab back to the overview
  returns to the window you set there.
- **Putting positions back is on the landing**, the last box before the
  glossary. It was at the end of the answered list until Gamah/lila#9: that
  list is an `infinite-scroll`, so the only undo the feature has got further
  away with every position answered, and scrolling towards it loaded another
  page of them. It belongs to the queue rather than to the record, and the
  landing is the page about the queue - the record bar it changes is directly
  above it, and the window it can narrow to is set at the top of the same page.

  Never a bare wipe: three named buttons (the ones you gave up on, the ones you
  skipped, everything you have answered) over a scope of the window or all
  time. The filter-driven fourth form of it - "put back these N", whatever cut
  of the list was showing - went with the move; the three that are left are the
  cuts anyone asked for.

- **Nothing on the landing spends fishnet on its own.** The cold state used to
  submit the request form on load; that is gone (Gamah/lila#5). The condition
  it was accepted under was that Tutor does the same, and Tutor does not -
  `/tutor` renders and waits for "Compute my tutor report" posting to
  `POST /tutor/:user/compute`. Everything that spends is a press.

## The name

**Mistakes**, one word, everywhere: on the page, in `/mistakes`, in the
`mistake` package, collection prefix and translation module. This README used
to have a section arguing for keeping a display name apart from the code token
so the former stayed cheap to change. The display name changed, to the code
token, and the argument went with it. Do not reintroduce a third word.

The name itself is hardcoded English in three places - `MistakeUi.name`, the
profile card in `app/views/user/show/header.scala`, and the `.mistake` block in
`ui/user/css/_profile.scss` - because "Chess Insights" is too: a product name
is not a translatable string.

Everything *else* on these pages is translated, through
`translation/source/mistake.xml`. That is the site's ordinary mechanism - 43
features have their own file, `bin/i18n-file-gen.ts` finds it by listing the
directory and `ui/.build/src/i18n.ts` creates its dest folder - but a new
module has to be named by hand in **two** places, and missing either fails
quietly:

- the `I18nModule` enum in `modules/coreI18n/src/main/modules.scala`, without
  which `.i18n(_.mistake)` does not compile;
- the `dbs` string in `build.sbt`'s `i18n` module, which is what
  `project/I18n.scala` serialises into the `i18n.<lang>.ser` the server reads.
  Miss this one and every page renders the raw key - `mistake:tagline` - with
  no error anywhere.

Note that this is deliberately *not*
what Tutor does, whose layout the landing otherwise copies: Tutor hardcodes
about forty English literals and has no module at all. That is Tutor's bug, not
a pattern to follow, and it is filed as Gamah/lila#7.

`key.scala` is generated. Changing `mistake.xml` means running
`pnpm i18n-file-gen` *and* `./ui/build`: the build regenerates `i18n.d.ts`
only, and CI checks both are in sync.

The token appears in these places, all of them the single word (or the path
`mistakes`). Renaming the feature is this whole table:

| what | where |
| --- | --- |
| route path | `conf/routes` - the six `/mistakes*` lines |
| client's copy of the path | `ui/mistake/src/xhr.ts`, the `base` constant |
| module | `modules/mistake/`, package `lila.mistake`, `build.sbt` |
| controller | `app/controllers/Mistake.scala`, wired in `app/Lila.scala` |
| env | `app/Env.scala` |
| page view | `modules/mistake/src/main/ui/MistakeUi.scala`, `app/views/ui.scala` |
| js bundle | `ui/mistake/` (`package.json` name, `src/mistake.ts`) |
| css bundle | `ui/mistake/css/build/mistake.scss`, `mistake.home.scss` |
| collections | `mistake_solve`, `mistake_request` in `modules/mistake/src/main/Env.scala` |
| setting | `mistakeNbAnalysis` |
| fishnet origin | `Work.Origin.autoMistake` |
| i18n | `translation/source/mistake.xml`, and the `mistake` case in `I18nModule` |

No user visible string is built out of the token, which is why the two lists
above are independent.

## Not done here

- **Access for someone else's queue.** `/mistakes` is your own only. The issue
  suggested opening it to a `SeeInsight` mod or your class teacher, but solving
  is interactive and records against the solver, so what a teacher would be
  looking at needs deciding before it's built.
- **Rank gate** ("your move must be in the engine's top 5"). Out by default and
  not implemented.
- **Variants.** Candidates are standard games only (`Query.variantStandard`).
- **Entry point.** The profile page links to it, beside the Chess Insights
  card. There is nothing in the nav yet - Tutor isn't in the nav either.
- **A subnav.** Two pages get the profile's `number-menu--tabs` strip. If a
  third ever turns up, `bits.pageMenu` is where this goes, which is what Tutor
  and Puzzle both use.
- **An "empty queue" state on the landing.** The landing cannot know the queue
  is empty without handing out a position to find out, which is the one thing it
  must not do, so that state lives on the board. Having nothing to play is a
  valid state and not a problem to engineer around: the board says so and tells
  you to go and play some games - which is true only because `until` can now be
  0, so the games you go and play can reach the queue.
- **Archiving a position** so a put-back never restores it. Considered in
  Gamah/lila#6 and dropped: skipping already retires a position, and a second
  kind of retirement is a concept the feature has not earned.
- **Any kind of warming progress indicator.** There is no live progress bar
  anywhere on lila - insights spins and reloads - and `bar-glider`, the site's
  one waiting notice, means
  "any second now" (tournament pairing, round clock expiry). This does not. So
  the warming state is a sentence and a count, and the count has moved when you
  come back.
