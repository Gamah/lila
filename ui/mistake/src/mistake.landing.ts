/* The landing page is otherwise plain HTML. This exists for one thing: a first
 * visitor with nothing analysed should not have to press a button before we
 * start, so the request form submits itself.
 *
 * It is still the same POST the button submits, and the page it posts to is
 * still the only thing that spends fishnet time. The server renders this form
 * only when a request would actually be accepted, so there is no loop: once it
 * goes through, the page comes back in its warming state with no form on it. */
site.load.then(() => {
  const form = document.querySelector<HTMLFormElement>('form.mistake-home__request--auto');
  form?.requestSubmit();
});
