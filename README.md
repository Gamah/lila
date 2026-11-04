# lila-mistakes-showcase

Screenshots of every state of the `/mistakes` feature, so a pull request body
can reference them. Nothing here is code and nothing here is meant to be
merged: this branch is an orphan, shares no history with `master`, and never
appears in a diff. Delete it once the pull request that uses it is closed.

**The page is [index.html](index.html), which GitHub serves as source. Read it
through htmlpreview:**

<https://htmlpreview.github.io/?https://raw.githubusercontent.com/Gamah/lila/lila-mistakes-showcase/index.html>

Every shot on it links to the lichess broadcast game it came from, and where
the page named a position, to that position at the same ply.

## What the shots are of

The games are imports of the public [GCT Sinquefield Cup 2026][broadcast]
broadcast, claimed to ten local scratch accounts on a development machine and
analysed there by a local fishnet at 100k nodes/move. **None of them are the
players' lichess accounts**, and nothing in these pictures reflects anything any
of those players did on lichess.

[broadcast]: https://lichess.org/broadcast/gct-sinquefield-cup-2026

## How they are taken

The generator lives outside the lila working tree, on the machine that runs it,
beside the stack it shoots: `~/lila-testdata/tools/`.

| | |
| --- | --- |
| `shots.mjs` | the 18 states and the playwright run that takes them; writes `manifest.json` |
| `page.mjs` | `manifest.json` + `gamemap.json` -> `index.html` |
| `gamemap.py` | local game id -> the broadcast game it was imported from, checked by player name |
| `warming.sh` | the one state that needs fishnet stopped while it is photographed |
| `cold.js`, `signup.mjs` | the two fixtures the imported corpus does not provide |

Each state is built through the feature's own HTTP surface - `/mistakes/reset`,
`/mistakes/next`, `/mistakes/record/:result` - so every shot is of the page the
server actually serves. Nothing is written into `mistake_solve` by hand.

`manifest.json` here is what the run recorded: for each state, the account, the
paths, the image sizes, and the game and ply of every position the page showed.
Regenerating replaces `shots/` wholesale.

## A name that went

`landing-none-answered` was in the old set and is not in this one: with nothing
answered the record section does not exist, which is exactly what
`landing-ready` already shows. Two names for one page.
