(() => {
  const scriptUrl = document.currentScript?.src;
  if (!scriptUrl) return;
  const resourceUrl = new URL('../legal/', scriptUrl);
  const paths = new Map([
    ['/legal/2026-11-16/terms.html', 'terms.html'],
    ['/legal/2026-11-16/privacy.html', 'privacy.html'],
  ]);

  document.addEventListener('DOMContentLoaded', () => {
    const dialog = document.createElement('dialog');
    dialog.className = 'sizy-legal-modal';
    dialog.innerHTML = '<div class="sizy-legal-modal-bar"><strong></strong><button type="button" aria-label="Закрыть">×</button></div><iframe title="Документ"></iframe>';
    const title = dialog.querySelector('strong');
    const frame = dialog.querySelector('iframe');
    dialog.querySelector('button').addEventListener('click', () => dialog.close());
    document.body.append(dialog);

    document.addEventListener('click', (event) => {
      if (event.defaultPrevented || event.button !== 0 || event.ctrlKey || event.metaKey || event.shiftKey || event.altKey) return;
      const anchor = event.target.closest?.('a[href]');
      if (!anchor) return;
      const url = new URL(anchor.href);
      if (url.origin !== 'https://sizylab.ru') return;
      const documentName = paths.get(url.pathname);
      if (!documentName) return;
      event.preventDefault();
      title.textContent = anchor.textContent.trim();
      frame.title = title.textContent;
      frame.src = new URL(documentName, resourceUrl).href;
      if (!dialog.open) dialog.showModal();
    });
    dialog.addEventListener('close', () => { frame.removeAttribute('src'); });
  });
})();
