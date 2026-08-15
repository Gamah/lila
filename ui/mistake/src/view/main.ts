import { hl, spinnerVdom as spinner, type VNode } from 'lib/view';

import type MistakeCtrl from '../ctrl';
import chessground from './chessground';
import feedback from './feedback';

/* No ceval box here on purpose. Local ceval runs to judge the move you tried,
 * exactly as retro uses it, but rendering the engine's output would hand over
 * both the evaluation and the best move - the two things the exercise is
 * asking you for. */
export default function (ctrl: MistakeCtrl): VNode {
  return hl('main.mistake.mistake-app', { class: { 'mistake--empty': !ctrl.position } }, [
    ctrl.position && hl('div.mistake__board.main-board', [chessground(ctrl), ctrl.promotion.view()]),
    hl('div.mistake__side', [ctrl.loading ? hl('div.mistake__loading', spinner()) : feedback(ctrl)]),
  ]);
}
