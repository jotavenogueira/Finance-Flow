'use strict';
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { JSDOM } = require('jsdom');
const root = path.join(__dirname, '..');
const html = fs.readFileSync(path.join(root, 'web/index.html'), 'utf8');
const dashboard = fs.readFileSync(path.join(root, 'web/dashboard.js'), 'utf8');
const app = fs.readFileSync(path.join(root, 'web/app.js'), 'utf8');
const user = { id: 1, name: 'Teste', email: 'teste@example.com' };
const response = (data, status = 200) => ({ ok: status < 400, status, text: async () => JSON.stringify(data) });
const tick = () => new Promise(resolve => setImmediate(resolve));
async function settle() { for (let i = 0; i < 5; i++) await tick(); }
function setup({ url = 'http://localhost:8081/', loggedIn = false, provider = async () => response({ google: true }) } = {}) {
  const dom = new JSDOM(html, { url, runScripts: 'outside-only' });
  const win = dom.window;
  const calls = [];
  win.TextEncoder = TextEncoder;
  win.fetch = async (url, options = {}) => {
    calls.push({ url, options });
    if (url === '/auth/providers') return provider();
    if (url === '/auth/me') return loggedIn ? response({ user }) : response({}, 401);
    if (url === '/auth/login') return response({ user });
    if (url === '/categories' || url === '/transactions') return response([]);
    throw Error(`Unexpected request: ${url}`);
  };
  win.eval(dashboard);
  win.eval(app);
  return { dom, win, calls, $: id => win.document.getElementById(id) };
}
(async () => {
  let resolveProvider;
  let scenario = setup({ provider: () => new Promise(resolve => { resolveProvider = resolve; }) });
  try {
    await settle();
    assert.equal(scenario.$('auth-panel').hidden, false, 'password login loads without waiting for Google provider');
    assert.equal(scenario.$('google-auth').hidden, true, 'Google starts hidden until configuration is known');
    resolveProvider(response({ google: true }));
    await settle();
    assert.equal(scenario.$('google-auth').hidden, false);
    assert.equal(scenario.$('google-auth-form').method, 'post');
    assert.equal(scenario.$('google-auth-form').action, 'http://localhost:8081/auth/google/start');
    scenario.$('register-tab').click();
    assert.equal(scenario.$('google-auth').hidden, false, 'Google sign-in is also available during registration');
    assert.equal(scenario.$('auth-name').required, true);
  } finally { scenario.dom.window.close(); }

  for (const provider of [async () => response({ google: false }), async () => { throw Error('offline'); }]) {
    scenario = setup({ provider });
    try {
      await settle();
      assert.equal(scenario.$('google-auth').hidden, true);
      assert.equal(scenario.$('google-link-form').hidden, true);
      assert.equal(scenario.$('notice').hidden, true, 'optional provider failure does not show a password-login error');
      scenario.$('auth-email').value = user.email;
      scenario.$('auth-password').value = 'SenhaTeste123';
      scenario.$('auth-form').dispatchEvent(new scenario.win.Event('submit', { bubbles: true, cancelable: true }));
      await settle();
      assert.equal(scenario.$('app-panel').hidden, false, 'password sign-in remains functional');
      const login = scenario.calls.find(call => call.url === '/auth/login');
      assert.equal(login.options.method, 'POST');
    } finally { scenario.dom.window.close(); }
  }

  const cases = [
    ['google_denied', 'cancelado'], ['google_failed', 'Não foi possível'], ['google_expired', 'expirou'],
    ['google_account_exists', 'Entre com sua senha'], ['google_account_conflict', 'outra conta'],
    ['google_email_mismatch', 'mesmo e-mail'], ['__proto__', 'Não foi possível'],
    ['<img src=x onerror=alert(1)>', 'Não foi possível'],
  ];
  for (const [code, expected] of cases) {
    scenario = setup({ url: `http://localhost:8081/?auth_error=${encodeURIComponent(code)}&keep=1#access` });
    try {
      await settle();
      assert.ok(scenario.$('notice').textContent.includes(expected), code);
      assert.equal(scenario.$('notice').getAttribute('role'), 'alert');
      assert.equal(scenario.$('notice').querySelectorAll('*').length, 0, 'callback error is plain text');
      assert.equal(scenario.win.location.search, '?keep=1', 'callback query is removed without losing other parameters');
      assert.equal(scenario.win.location.hash, '#access');
    } finally { scenario.dom.window.close(); }
  }

  scenario = setup({ loggedIn: true, url: 'http://localhost:8081/?auth_success=google_linked' });
  try {
    await settle();
    assert.equal(scenario.$('app-panel').hidden, false);
    assert.equal(scenario.$('google-link-form').hidden, false);
    assert.equal(scenario.$('google-link-form').method, 'post');
    assert.equal(scenario.$('google-link-form').action, 'http://localhost:8081/auth/google/link/start');
    assert.ok(scenario.$('notice').textContent.includes('foi conectada'));
    assert.equal(scenario.$('notice').getAttribute('role'), 'status');
    assert.equal(scenario.win.location.search, '');
  } finally { scenario.dom.window.close(); }

  scenario = setup({ url: 'http://localhost:8081/?auth_success=google_linked' });
  try {
    await settle();
    assert.equal(scenario.$('notice').hidden, true, 'success query alone never authenticates a user');
    assert.equal(scenario.$('app-panel').hidden, true);
  } finally { scenario.dom.window.close(); }
  console.log('Google UI: disponibilidade, cadastro/login, vínculo e mensagens seguras passaram.');
})().catch(error => { console.error(error); process.exitCode = 1; });
