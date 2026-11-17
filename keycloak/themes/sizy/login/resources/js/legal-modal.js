(() => {
  const scriptUrl = document.currentScript?.src;
  if (!scriptUrl) return;
  const resourceUrl = new URL('../legal/', scriptUrl);
  const imageUrl = new URL('../img/brand-mark.png', scriptUrl);
  const documents = {
    terms: { label: 'Соглашение', file: 'terms.html', path: '/legal/2026-11-16/terms.html' },
    privacy: { label: 'Обработка данных', file: 'privacy.html', path: '/legal/2026-11-16/privacy.html' },
  };

  document.addEventListener('DOMContentLoaded', () => {
    const dialog = document.createElement('dialog');
    dialog.className = 'sizy-legal-modal';
    dialog.setAttribute('aria-label', 'Документы Sizy');
    dialog.innerHTML = `
      <div class="sizy-legal-modal-top">
        <div class="sizy-legal-modal-brand"><img src="${imageUrl.href}" alt="" width="32" height="32"><span>Sizy</span><span class="sizy-legal-modal-divider"></span><span class="sizy-legal-modal-section">Документы</span></div>
        <button type="button" class="sizy-legal-modal-close" aria-label="Закрыть окно">×</button>
      </div>
      <nav class="sizy-legal-modal-tabs" aria-label="Юридические документы">
        <button type="button" data-kind="terms">Соглашение</button>
        <button type="button" data-kind="privacy">Обработка данных</button>
      </nav>
      <div class="sizy-legal-modal-scroll"><p class="sizy-legal-modal-status">Загружаем документ…</p></div>`;
    const scroll = dialog.querySelector('.sizy-legal-modal-scroll');
    const buttons = dialog.querySelectorAll('[data-kind]');
    let controller;
    let currentKind;

    async function open(kind) {
      const documentInfo = documents[kind];
      if (!documentInfo) return;
      currentKind = kind;
      controller?.abort();
      const request = new AbortController();
      controller = request;
      buttons.forEach((button) => {
        button.classList.toggle('active', button.dataset.kind === kind);
        if (button.dataset.kind === kind) button.setAttribute('aria-current', 'page');
        else button.removeAttribute('aria-current');
      });
      scroll.scrollTop = 0;
      scroll.innerHTML = '<p class="sizy-legal-modal-status">Загружаем документ…</p>';
      if (!dialog.open) dialog.showModal();
      try {
        const response = await fetch(new URL(documentInfo.file, resourceUrl), { signal: request.signal });
        if (!response.ok) throw new Error('Document unavailable');
        const html = await response.text();
        const main = new DOMParser().parseFromString(html, 'text/html').querySelector('main');
        if (!main) throw new Error('Document missing');
        const article = document.createElement('article');
        article.className = 'sizy-legal-modal-article';
        article.innerHTML = main.innerHTML;
        scroll.replaceChildren(article);
      } catch {
        if (!request.signal.aborted) {
          scroll.innerHTML = `<p class="sizy-legal-modal-status">Не удалось открыть документ. <a href="https://sizylab.ru${documentInfo.path}" target="_blank" rel="noopener noreferrer">Открыть отдельно</a></p>`;
        }
      }
    }

    dialog.querySelector('.sizy-legal-modal-close').addEventListener('click', () => dialog.close());
    dialog.addEventListener('close', () => controller?.abort());
    buttons.forEach((button) => button.addEventListener('click', () => open(button.dataset.kind)));
    document.body.append(dialog);

    document.addEventListener('click', (event) => {
      if (event.defaultPrevented || event.button !== 0 || event.ctrlKey || event.metaKey || event.shiftKey || event.altKey) return;
      const anchor = event.target.closest?.('a[href]');
      if (!anchor) return;
      if (anchor.closest('.sizy-legal-modal-status')) return;
      const url = new URL(anchor.href);
      const kind = Object.keys(documents).find((key) =>
        (url.origin === 'https://sizylab.ru' && url.pathname === documents[key].path) ||
        (url.origin === resourceUrl.origin && url.pathname === new URL(documents[key].file, resourceUrl).pathname));
      if (!kind) return;
      event.preventDefault();
      if (kind !== currentKind || !dialog.open) open(kind);
    });
  });
})();
