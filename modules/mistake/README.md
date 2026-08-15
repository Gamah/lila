# mistake

**Blindspot** on the page. `mistake` in the code, `/mistakes` in the URL - the
two never have to agree, and keeping them apart is what makes the name cheap to
change. Everything below uses the code token.

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
- **Sound** is the site's board sound, from the same call analyse makes on a
  forward step in `jump()`: `site.sound.move(node)`, plus `saySan`, plus
  `preloadBoardSounds()` on boot. It fires when the tried move lands and when
  the solution is played, and not when a move is retracted or a new position
  dealt - analyse is silent for jumps that aren't a forward step, and so is
  retro. There is no win or fail jingle: retro has none either, and puzzles are
  the wrong thing to copy here.
- `MistakeApi.extract` builds the ordinary analyse tree with `TreeBuilder` and
  pulls one node out of it, rather than replaying the game by hand. The
  engine's line is already hanging off the pre-mistake node as a `comp` child,
  which is the same thing retro reads.
- **Window**: games created between 1 and 3 months ago by default
  (`MistakeWindow`). Recent games are skipped on purpose - they're still in
  your memory. It travels in the URL as `?window=<since>-<until>` in months and
  there is no per-user setting behind it, so a window nobody picked is one we
  never spend fishnet on.

  The landing sets it with two selects, a from and a to, the way tutor's date
  range is two fields (`TutorConfig.form.dates`). Each half is its own
  whitelist - `sinceChoices` and `untilChoices` - rather than a free number,
  and `MistakeWindow.valid` checks both halves and that the range is not
  inverted. That check is what the old flat list of four pairs got for free.

  **`until` is never 0.** It is not decoration: it is the whole of what makes a
  position cold, and a control that can switch it off has broken the feature.
  Widening `since` is the half that costs fishnet, which is why it has the
  shorter list; narrowing `until` is free, being a subset of a window we
  already pay for.
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

  There is no list of what you *have* answered either. One was built and cut:
  `mistake_solve` makes it cheap, but a table of "Solved / 45 minutes ago / see
  the game" told you nothing you wanted at a glance, and reading your own
  history is not why anyone opens this. Reconsider it as a real page - grouped,
  or with the positions drawn - or not at all.

## The two names

The **displayed** name is Blindspot, and it is hardcoded English in exactly
three places - `MistakeUi.name`, the profile card in
`app/views/user/show/header.scala`, and the `.blindspot` block in
`ui/user/css/_profile.scss`. It is not in `site.xml`, because "Lichess Tutor"
and "Chess Insights" aren't either: a product name is not a translatable
string. Renaming the feature is those three edits.

The **code** token is `mistake` and appears in these places, all of them the
single word (or the path `mistakes`). Renaming *that* is a bigger job and buys
nothing user visible:

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
| i18n | `site.xml`: `nothingLeftToShuffle`, `seeTheGameItCameFrom` - the two strings the board inherited from retro |

No user visible string is built out of the token, which is why the two lists
above are independent.

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
  not do, so that state lives on the board.
- **A reset button on the landing.** `POST /mistakes/reset` is still there and
  the board still calls it, because on the board it follows "Nothing left to
  shuffle" and "Do it again" is then self explanatory. Sat on the landing under
  a stats table it was a bare red button that explained nothing, and it is a
  thing you want on a dev instance rather than in production. If it comes back
  it needs to say what it destroys.
- **Landing copy is hardcoded English**, as tutor's and insights' empty page
  both are. Only the two strings the board inherited from retro are in
  `site.xml`.
- **Any kind of warming progress indicator.** There is no live progress bar
  anywhere on lila - tutor reloads its waiting page once a minute, insights
  spins and reloads - and `bar-glider`, the site's one waiting notice, means
  "any second now" (tournament pairing, round clock expiry). This does not. So
  the warming state is a sentence and a count, and the count has moved when you
  come back.
