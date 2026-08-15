import { Chessground as makeChessground } from '@lichess-org/chessground';
import { h, type VNode } from 'snabbdom';

import resizeHandle from 'lib/chessgroundResize';
import { isSafari } from 'lib/device';
import { Coords, ShowResizeHandle } from 'lib/prefs';
import { onInsert } from 'lib/view';

import type MistakeCtrl from '../ctrl';

export default function (ctrl: MistakeCtrl): VNode {
  return h('div.cg-wrap', {
    hook: {
      ...onInsert(el => ctrl.setChessground(makeChessground(el, makeConfig(ctrl)))),
      destroy: () => ctrl.ground().destroy(),
    },
  });
}

function makeConfig(ctrl: MistakeCtrl): CgConfig {
  const pref = ctrl.data.pref;
  return {
    ...ctrl.cgConfig(),
    coordinates: pref.coords !== Coords.Hidden,
    coordinatesOnSquares: pref.coords === Coords.All,
    addPieceZIndex: pref.is3d,
    addDimensionsCssVarsTo: document.body,
    jsHover: isSafari(),
    animation: { duration: pref.animation },
    movable: {
      ...ctrl.cgConfig().movable,
      free: false,
      showDests: pref.destination,
      rookCastle: pref.rookCastle,
    },
    draggable: { enabled: pref.moveEvent > 0, showGhost: pref.highlight },
    selectable: { enabled: pref.moveEvent !== 1 },
    highlight: { lastMove: pref.highlight, check: pref.highlight },
    events: {
      move: ctrl.userMove,
      insert(elements) {
        resizeHandle(elements, ShowResizeHandle.Always, 0);
      },
    },
  };
}
