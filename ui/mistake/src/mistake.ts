import { attributesModule, classModule, init } from 'snabbdom';

import menuHover from 'lib/menuHover';

import MistakeCtrl from './ctrl';
import type { MistakeOpts } from './interfaces';
import view from './view/main';

const patch = init([classModule, attributesModule]);

export async function initModule(opts: MistakeOpts): Promise<void> {
  await site.asset.loadPieces;
  const element = document.querySelector('main.mistake') as HTMLElement;
  const ctrl = new MistakeCtrl(opts, redraw);

  const blueprint = view(ctrl);
  element.innerHTML = '';
  let vnode = patch(element, blueprint);

  function redraw() {
    vnode = patch(vnode, view(ctrl));
  }

  menuHover();
}
