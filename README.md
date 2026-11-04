# lila-mistakes-showcase

Screenshots of `/mistakes`, so a PR body can point at them. Orphan branch: no
code, no history with `master`, never in a diff. Delete it with the PR.

GitHub serves `.html` as source, so read the page through htmlpreview:

<https://htmlpreview.github.io/?https://raw.githubusercontent.com/Gamah/lila/lila-mistakes-showcase/index.html>

Dark theme throughout. Each shot that pictures a position links to that
position in the broadcast game, at the same ply.

## What the games are

The 2026 [Sinquefield Cup][broadcast] broadcast, imported to ten scratch
accounts on a dev box and analysed there at 100k nodes/move. None of it is
production data and none of the accounts are the players' lichess accounts.

[broadcast]: https://lichess.org/broadcast/gct-sinquefield-cup-2026

## Generator

Outside the lila tree, on the box that runs the stack: `~/lila-testdata/tools/`.

| | |
| --- | --- |
| `shots.mjs` | the 18 states, playwright, writes `manifest.json` |
| `page.mjs` | `manifest.json` + `gamemap.json` -> `index.html` |
| `gamemap.py` | local game id -> broadcast game, checked by player name |
| `warming.sh` | the one state that needs fishnet stopped to photograph |
| `cold.js`, `signup.mjs` | two fixtures the imported corpus does not give you |

States are built over HTTP: `/mistakes/reset`, `/mistakes/next`,
`/mistakes/record/:result`. Nothing is written into `mistake_solve` by hand.
`manifest.json` records what each run saw: account, paths, image sizes, and the
game and ply of every position shown. Regenerating replaces `shots/`.

## Dropped name

`landing-none-answered` is not in this set. With nothing answered there is no
record section, which is what `landing-ready` already shows.
