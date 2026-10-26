"""Проверка геометрии настоящих страниц Keycloak; запускать на локальном тестовом realm."""
import argparse
import os
from pathlib import Path
from urllib.parse import urlencode

from playwright.sync_api import sync_playwright

VIEWPORTS = (320, 390, 600, 768, 1024, 1440, 2048)
FORM_WIDTH = 440
PAGE_PADDING = 48

parser = argparse.ArgumentParser()
parser.add_argument('--base-url', default='http://localhost:18180')
parser.add_argument('--screenshots', type=Path)
options = parser.parse_args()
if options.screenshots:
    options.screenshots.mkdir(parents=True, exist_ok=True)

with sync_playwright() as playwright:
    browser = playwright.chromium.launch(executable_path=os.environ.get('CHROMIUM'))
    failures = []
    for width in VIEWPORTS:
        for locale in ('ru', 'en'):
            for endpoint, form in (('auth', '#kc-form-login'), ('registrations', '#kc-register-form')):
                page = browser.new_page(viewport={'width': width, 'height': 1000})
                page.on('pageerror', lambda error: failures.append(str(error)))
                query = urlencode({
                    'client_id': 'sizylift-web', 'redirect_uri': 'http://localhost:5173/auth/callback',
                    'response_type': 'code', 'scope': 'openid', 'ui_locales': locale,
                    'code_challenge_method': 'S256',
                    'code_challenge': 'abcdefghijklmnopqrstuvwxyz0123456789ABCDEFG',
                })
                response = page.goto(f'{options.base_url}/realms/sizylift/protocol/openid-connect/{endpoint}?{query}')
                assert response.status == 200
                page.locator(form).wait_for()
                page.evaluate('document.fonts.ready')
                actual = page.locator(form).bounding_box()
                expected = min(FORM_WIDTH, width - PAGE_PADDING)
                assert abs(actual['width'] - expected) < 2, (width, locale, endpoint, actual)
                assert abs(actual['x'] + actual['width'] / 2 - width / 2) < 2, (width, locale, endpoint, actual)
                assert page.evaluate('document.documentElement.scrollWidth <= innerWidth')
                title = page.locator('#kc-page-title').bounding_box()
                language = page.locator('.pf-v5-c-login__main-header-utilities').bounding_box()
                brand = page.locator('#kc-header-wrapper').bounding_box()
                if width <= 600:
                    assert brand['y'] <= 40, (width, 'unexpected empty space above form')
                assert language['y'] + language['height'] <= title['y'], (width, 'language overlaps title')
                assert brand['x'] + brand['width'] <= language['x'], (width, 'language overlaps brand')
                if width >= 768:
                    line_height = page.locator('#kc-page-title').evaluate('e => parseFloat(getComputedStyle(e).lineHeight)')
                    assert title['height'] <= line_height + 2, (width, locale, 'wrapped title')
                if options.screenshots and locale == 'ru' and width in (390, 1440):
                    page.screenshot(path=str(options.screenshots / f'{endpoint}-{width}.png'), full_page=True)
                page.close()
    browser.close()
    assert not failures, failures
    print(f'{len(VIEWPORTS) * 4} checks passed: form width, centering, title, language selector and JavaScript')
