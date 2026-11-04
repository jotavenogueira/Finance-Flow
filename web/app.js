'use strict';

(() => {
  const $ = (id) => document.getElementById(id);
  const state = { user: null, categories: [], transactions: [], authMode: 'login', pendingDelete: null, detailId: null, generation: 0 };
  const currency = new Intl.NumberFormat('pt-BR', { style: 'currency', currency: 'BRL' });
  let activeRequests = 0;

  function readAuthResult() {
    const url = new URL(window.location.href);
    const errorCode = url.searchParams.get('auth_error');
    const successCode = url.searchParams.get('auth_success');
    const errors = {
      google_denied: 'O acesso com Google foi cancelado. Você pode tentar novamente ou entrar com e-mail e senha.',
      google_failed: 'Não foi possível entrar com Google. Tente novamente.',
      google_expired: 'A tentativa de acesso com Google expirou. Tente novamente.',
      google_account_exists: 'Já existe uma conta com esse e-mail. Entre com sua senha e use Conectar Google para vincular sua conta.',
      google_account_conflict: 'Esta conta Google já está conectada a outra conta FinanceFlow.',
      google_email_mismatch: 'Escolha a conta Google com o mesmo e-mail da sua conta FinanceFlow.',
    };
    const hasError = url.searchParams.has('auth_error');
    if (hasError || url.searchParams.has('auth_success')) {
      url.searchParams.delete('auth_error');
      url.searchParams.delete('auth_success');
      window.history.replaceState(window.history.state, '', `${url.pathname}${url.search}${url.hash}`);
    }
    if (hasError) {
      const message = Object.prototype.hasOwnProperty.call(errors, errorCode) ? errors[errorCode] : errors.google_failed;
      return { message, error: true };
    }
    return successCode === 'google_linked' ? { message: 'Sua conta Google foi conectada. Você já pode usá-la para entrar.', error: false } : null;
  }

  const authResult = readAuthResult();

  async function loadAuthProviders() {
    try {
      const providers = await api('/auth/providers', { silentUnauthorized: true });
      const enabled = providers?.google === true;
      $('google-auth').hidden = !enabled;
      $('google-link-form').hidden = !enabled;
    } catch {
      // A indisponibilidade do provedor não impede o acesso por e-mail e senha.
    }
  }

  class ApiError extends Error {
    constructor(message, status) { super(message); this.status = status; }
  }

  function notify(message, error = false) {
    $('notice').textContent = message;
    $('notice').classList.toggle('error', error);
    $('notice').hidden = !message;
    $('notice').setAttribute('role', error ? 'alert' : 'status');
  }

  function localToday() {
    const date = new Date();
    return [date.getFullYear(), String(date.getMonth() + 1).padStart(2, '0'), String(date.getDate()).padStart(2, '0')].join('-');
  }

  function formatDate(value) {
    const parts = String(value).slice(0, 10).split('-');
    return parts.length === 3 ? `${parts[2]}/${parts[1]}/${parts[0]}` : String(value);
  }

  function showLoggedOut() {
    state.generation++;
    state.user = null;
    state.categories = [];
    state.transactions = [];
    state.pendingDelete = null;
    state.detailId = null;
    $('app-panel').hidden = true;
    $('auth-panel').hidden = false;
    $('initial-loading').hidden = true;
    $('user-name').textContent = '';
    $('user-email').textContent = '';
    $('transaction-list').replaceChildren();
    $('category-list').replaceChildren();
    $('detail-fields').replaceChildren();
    $('transaction-detail').hidden = true;
    $('auth-password').value = '';
    $('category-form').reset();
    $('transaction-form').reset();
    $('transaction-date').value = localToday();
    renderCategories();
    resetFilters();
  }

  async function api(path, options = {}) {
    let response;
    try {
      response = await fetch(path, {
        method: options.method || 'GET',
        credentials: 'same-origin',
        cache: 'no-store',
        headers: { Accept: 'application/json', ...(options.body ? { 'Content-Type': 'application/json' } : {}) },
        body: options.body ? JSON.stringify(options.body) : undefined,
      });
    } catch {
      throw new ApiError('Não foi possível conectar ao servidor. Verifique se a API está funcionando.', 0);
    }
    const raw = await response.text();
    let data = null;
    if (raw) {
      try { data = JSON.parse(raw); } catch { /* A resposta de erro pode não conter JSON. */ }
    }
    if (!response.ok) {
      if (response.status === 401 && !options.authRequest && !options.silentUnauthorized) {
        showLoggedOut();
        setAuthMode('login');
        notify('Sua sessão expirou. Entre novamente para continuar.', true);
      }
      const fallback = response.status === 401 ? 'E-mail ou senha incorretos.' : 'Não foi possível concluir a operação. Tente novamente.';
      throw new ApiError(data?.message || fallback, response.status);
    }
    return data;
  }

  function requestStarted() {
    activeRequests++;
    $('logout-button').disabled = true;
    $('google-link-button').disabled = true;
  }

  function requestFinished() {
    activeRequests = Math.max(0, activeRequests - 1);
    $('logout-button').disabled = activeRequests > 0;
    $('google-link-button').disabled = activeRequests > 0;
  }

  function setFormBusy(form, busy) {
    form.setAttribute('aria-busy', String(busy));
    for (const control of form.elements) {
      if (busy) {
        control.dataset.wasDisabled = String(control.disabled);
        control.disabled = true;
      } else {
        control.disabled = control.dataset.wasDisabled === 'true';
        delete control.dataset.wasDisabled;
      }
    }
  }

  function setAuthMode(mode) {
    state.authMode = mode;
    const register = mode === 'register';
    $('login-tab').setAttribute('aria-selected', String(!register));
    $('register-tab').setAttribute('aria-selected', String(register));
    $('login-tab').tabIndex = register ? -1 : 0;
    $('register-tab').tabIndex = register ? 0 : -1;
    $('auth-fields').setAttribute('aria-labelledby', register ? 'register-tab' : 'login-tab');
    $('name-field').hidden = !register;
    $('auth-name').required = register;
    $('auth-name').disabled = !register;
    $('auth-heading').textContent = register ? 'Crie sua conta' : 'Bem-vindo de volta';
    $('auth-help').textContent = register ? 'Seu espaço começa com um cadastro simples.' : 'Use seu e-mail e sua senha para entrar.';
    $('auth-submit').textContent = register ? 'Criar conta' : 'Entrar';
    $('auth-password').autocomplete = register ? 'new-password' : 'current-password';
    $('auth-password').value = '';
    $('password-help').textContent = register ? 'Use uma senha com pelo menos 8 caracteres.' : 'A senha da sua conta FinanceFlow.';
  }

  function renderCategories() {
    const filter = $('filter-category');
    const selected = filter.value;
    filter.replaceChildren(new Option('Todas as categorias', ''));
    for (const category of state.categories) filter.append(new Option(category.name, String(category.id)));
    if (state.categories.some((category) => String(category.id) === selected)) filter.value = selected;
    const list = $('category-list');
    list.replaceChildren();
    for (const category of state.categories) {
      const item = document.createElement('li');
      item.textContent = category.name;
      list.append(item);
    }
    $('categories-empty').hidden = state.categories.length > 0;
    const select = $('transaction-category');
    const previousValue = select.value;
    select.replaceChildren();
    const placeholder = document.createElement('option');
    placeholder.value = '';
    placeholder.textContent = state.categories.length ? 'Selecione uma categoria' : 'Crie uma categoria primeiro';
    select.append(placeholder);
    for (const category of state.categories) {
      const option = document.createElement('option');
      option.value = String(category.id);
      option.textContent = category.name;
      select.append(option);
    }
    if (state.categories.some((category) => String(category.id) === previousValue)) select.value = previousValue;
    const formBusy = $('transaction-form').getAttribute('aria-busy') === 'true';
    select.disabled = !state.categories.length || formBusy;
    $('transaction-submit').disabled = !state.categories.length || formBusy;
    $('category-hint').textContent = state.categories.length ? 'Somente as categorias da sua conta aparecem aqui.' : 'Use o formulário acima para criar sua primeira categoria.';
  }

  function filters() {
    return { period: $('filter-period').value, month: $('filter-month').value || localToday().slice(0, 7),
      type: $('filter-type').value, category: $('filter-category').value, search: $('filter-search').value };
  }

  function resetFilters() {
    $('filter-period').value = 'month';
    $('filter-month').value = localToday().slice(0, 7);
    $('filter-type').value = '';
    $('filter-category').value = '';
    $('filter-search').value = '';
    state.pendingDelete = null;
    $('transaction-detail').hidden = true;
    state.detailId = null;
    renderTransactions();
  }

  function periodLabel(key) {
    if (key.length === 10) return formatDate(key);
    return new Intl.DateTimeFormat('pt-BR', { month: 'short', year: '2-digit', timeZone: 'UTC' }).format(new Date(`${key}-01T12:00:00Z`));
  }

  function renderDashboard(transactions) {
    const selected = filters();
    const result = FinanceDashboard.summarize(transactions, selected);
    $('summary-income').textContent = currency.format(result.income / 100);
    $('summary-expense').textContent = currency.format(result.expense / 100);
    $('summary-balance').textContent = currency.format(result.balance / 100);
    $('summary-balance').classList.toggle('negative-balance', result.balance < 0);
    $('filter-month').disabled = selected.period === 'all';
    $('month-help').textContent = selected.period === 'six' ? 'Inclui o mês de referência e os cinco anteriores.' : selected.period === 'all' ? 'Todos os meses cadastrados estão incluídos.' : 'Selecione o mês que deseja analisar.';
    const period = selected.period === 'all' ? 'Todo o histórico' : selected.period === 'six'
      ? `${periodLabel(FinanceDashboard.shiftMonth(selected.month, -5))} a ${periodLabel(selected.month)}` : periodLabel(selected.month);
    $('filter-context').textContent = `${period} · ${result.count} de ${state.transactions.length} movimentações. Resumo, gráficos e lista usam os mesmos filtros.`;
    $('evolution-heading').textContent = selected.period === 'month' ? 'Evolução diária' : 'Evolução mensal';
    $('evolution-empty').hidden = result.count > 0;
    $('evolution-chart').hidden = result.count === 0;
    $('evolution-details').hidden = result.count === 0;
    renderEvolution(result.timeline, selected.period === 'month');
    $('breakdown-total').textContent = result.expense ? `${currency.format(result.expense / 100)} em despesas filtradas.` : 'Distribuição das despesas filtradas.';
    $('breakdown-empty').hidden = result.expense > 0;
    const breakdown = $('category-breakdown');
    breakdown.replaceChildren();
    for (const category of result.categories) {
      const item = document.createElement('li');
      const heading = document.createElement('div');
      const name = document.createElement('span');
      const amount = document.createElement('strong');
      name.textContent = category.name;
      amount.textContent = currency.format(category.amount / 100);
      heading.append(name, amount);
      const bar = document.createElement('progress');
      bar.max = result.expense; bar.value = category.amount;
      bar.setAttribute('aria-label', `${category.name}: ${(100 * category.amount / result.expense).toFixed(1)}% das despesas`);
      const caption = document.createElement('small');
      caption.textContent = `${(100 * category.amount / result.expense).toLocaleString('pt-BR', { maximumFractionDigits: 1 })}% · ${category.count} ${category.count === 1 ? 'despesa' : 'despesas'}`;
      item.append(heading, bar, caption); breakdown.append(item);
    }
    $('history-empty-heading').textContent = state.transactions.length ? 'Nenhuma movimentação encontrada' : 'Seu histórico começa aqui';
    $('history-empty-text').textContent = state.transactions.length ? 'Tente outro período ou limpe os filtros para encontrar suas movimentações.' : 'Crie uma categoria e registre sua primeira receita ou despesa.';
  }

  function renderEvolution(points, daily) {
    const container = $('evolution-chart'); container.replaceChildren();
    const tbody = $('evolution-table'); tbody.replaceChildren();
    const svgElement = (tag, attrs = {}, content) => {
      const element = document.createElementNS('http://www.w3.org/2000/svg', tag);
      for (const [key, value] of Object.entries(attrs)) element.setAttribute(key, value);
      if (content !== undefined) element.textContent = content;
      return element;
    };
    const width = Math.max(620, points.length * (daily ? 17 : 76) + 100);
    const height = 238, left = 92, right = 16, top = 18, bottom = 198;
    const step = (width - left - right) / Math.max(points.length, 1);
    const maximum = Math.max(100, ...points.map((point) => Math.max(point.income, point.expense)));
    const svg = svgElement('svg', { viewBox: `0 0 ${width} ${height}`, width, height, role: 'img', 'aria-labelledby': 'evolution-svg-title evolution-svg-desc' });
    svg.append(svgElement('title', { id: 'evolution-svg-title' }, daily ? 'Receitas e despesas por dia' : 'Receitas e despesas por mês'));
    svg.append(svgElement('desc', { id: 'evolution-svg-desc' }, 'Barras em pares representam receitas e despesas. Os valores exatos estão na tabela abaixo.'));
    for (const fraction of [0, 0.5, 1]) {
      const y = bottom - (bottom - top) * fraction;
      svg.append(svgElement('line', { x1: left, x2: width - right, y1: y, y2: y, class: 'chart-grid-line' }));
      svg.append(svgElement('text', { x: left - 8, y: y + 4, 'text-anchor': 'end', class: 'chart-axis-label' }, new Intl.NumberFormat('pt-BR', { notation: 'compact', maximumFractionDigits: 1 }).format(maximum * fraction / 100)));
    }
    svg.append(svgElement('text', { x: 10, y: 12, class: 'chart-axis-label' }, 'R$'));
    points.forEach((point, index) => {
      const center = left + step * (index + 0.5);
      const barWidth = Math.min(18, step * 0.32);
      for (const [type, offset] of [['income', -barWidth - 1], ['expense', 1]]) {
        const barHeight = (point[type] / maximum) * (bottom - top);
        const bar = svgElement('rect', { x: center + offset, y: bottom - barHeight, width: barWidth, height: barHeight, rx: 2, class: `chart-bar-${type}` });
        bar.append(svgElement('title', {}, `${periodLabel(point.key)} · ${type === 'income' ? 'Receitas' : 'Despesas'}: ${currency.format(point[type] / 100)}`));
        svg.append(bar);
      }
      if (!daily || index % 3 === 0 || index === points.length - 1) svg.append(svgElement('text', { x: center, y: 220, 'text-anchor': 'middle', class: 'chart-axis-label' }, daily ? point.key.slice(8) : periodLabel(point.key)));
      const row = document.createElement('tr');
      for (const [i, value] of [periodLabel(point.key), currency.format(point.income / 100), currency.format(point.expense / 100), currency.format((point.income - point.expense) / 100)].entries()) {
        const cell = document.createElement(i ? 'td' : 'th');
        if (!i) cell.scope = 'row';
        cell.textContent = value; row.append(cell);
      }
      tbody.append(row);
    });
    container.append(svg);
  }

  for (const id of ['filter-period', 'filter-month', 'filter-type', 'filter-category', 'filter-search']) {
    $(id).addEventListener(id === 'filter-search' ? 'input' : 'change', () => {
      if (id === 'filter-month' && !/^\d{4}-\d{2}$/.test($(id).value)) $(id).value = localToday().slice(0, 7);
      state.pendingDelete = null;
      state.detailId = null;
      $('transaction-detail').hidden = true;
      renderTransactions();
    });
  }
  $('reset-filters').addEventListener('click', resetFilters);

  function actionButton(label, className, action, id, description) {
    const button = document.createElement('button');
    button.type = 'button';
    button.className = `button ${className}`;
    button.textContent = label;
    button.dataset.action = action;
    button.dataset.id = String(id);
    button.setAttribute('aria-label', `${label}: ${description}`);
    return button;
  }

  function renderTransactions() {
    const list = $('transaction-list');
    list.replaceChildren();
    const visible = FinanceDashboard.filterTransactions(state.transactions, filters());
    renderDashboard(visible);
    const count = visible.length;
    $('transaction-count').textContent = state.transactions.length ? `${count} de ${state.transactions.length} movimentações nos filtros atuais.` : 'Nenhuma transação cadastrada.';
    $('transactions-empty').hidden = count > 0;
    for (const transaction of visible) {
      const item = document.createElement('li');
      item.className = 'transaction-item';
      item.dataset.transactionId = String(transaction.id);
      const top = document.createElement('div');
      top.className = 'transaction-top';
      const text = document.createElement('div');
      const description = document.createElement('p');
      description.className = 'transaction-description';
      description.textContent = transaction.description;
      const meta = document.createElement('p');
      meta.className = 'transaction-meta';
      meta.textContent = `${transaction.category} · ${formatDate(transaction.date)}`;
      text.append(description, meta);
      const amount = document.createElement('span');
      const income = transaction.type === 'income';
      amount.className = `transaction-value ${income ? 'income' : 'expense'}`;
      amount.textContent = `${income ? '+' : '−'} ${currency.format(Number(transaction.amount))}`;
      amount.setAttribute('aria-label', `${income ? 'Receita' : 'Despesa'} de ${currency.format(Number(transaction.amount))}`);
      top.append(text, amount);
      const actions = document.createElement('div');
      actions.className = 'transaction-actions';
      if (state.pendingDelete === transaction.id) {
        const prompt = document.createElement('p');
        prompt.className = 'delete-prompt';
        prompt.textContent = 'Excluir esta transação? Essa ação não pode ser desfeita.';
        actions.append(prompt, actionButton('Confirmar exclusão', 'danger', 'confirm-delete', transaction.id, transaction.description), actionButton('Cancelar', 'quiet', 'cancel-delete', transaction.id, transaction.description));
      } else {
        actions.append(actionButton('Ver detalhes', 'quiet', 'detail', transaction.id, transaction.description), actionButton('Excluir', 'danger', 'ask-delete', transaction.id, transaction.description));
      }
      item.append(top, actions);
      list.append(item);
    }
  }

  async function loadWorkspace() {
    const generation = state.generation;
    $('workspace-loading').hidden = false;
    $('refresh-button').disabled = true;
    requestStarted();
    try {
      const [categories, transactions] = await Promise.all([api('/categories'), api('/transactions')]);
      if (generation !== state.generation || !state.user) return;
      state.categories = categories || [];
      state.transactions = transactions || [];
      state.pendingDelete = null;
      renderCategories();
      renderTransactions();
    } finally {
      $('workspace-loading').hidden = true;
      $('refresh-button').disabled = false;
      requestFinished();
    }
  }

  async function enterWorkspace(user) {
    state.generation++;
    state.user = user;
    $('auth-password').value = '';
    $('initial-loading').hidden = true;
    $('auth-panel').hidden = true;
    $('app-panel').hidden = false;
    resetFilters();
    for (const id of ['summary-balance', 'summary-income', 'summary-expense']) $(id).textContent = '—';
    $('user-name').textContent = user.name;
    $('user-email').textContent = user.email;
    $('transaction-date').value = localToday();
    await loadWorkspace();
  }

  function showRequestError(error) {
    if (error.status === 401 && !state.user && !$('auth-panel').hidden) {
      // A mensagem de sessão expirada já foi exibida pelo cliente da API.
      if ($('notice').textContent === 'Sua sessão expirou. Entre novamente para continuar.') return;
    }
    notify(error.message || 'Não foi possível concluir a operação.', true);
  }

  for (const mode of ['login', 'register']) {
    $(`${mode}-tab`).addEventListener('click', () => { setAuthMode(mode); notify(''); });
    $(`${mode}-tab`).addEventListener('keydown', (event) => {
      if (event.key === 'ArrowLeft' || event.key === 'ArrowRight' || event.key === 'Home' || event.key === 'End') {
        event.preventDefault();
        const next = event.key === 'Home' ? 'login' : event.key === 'End' ? 'register' : mode === 'login' ? 'register' : 'login';
        setAuthMode(next);
        $(`${next}-tab`).focus();
      }
    });
  }

  $('auth-form').addEventListener('submit', async (event) => {
    event.preventDefault();
    const form = event.currentTarget;
    const register = state.authMode === 'register';
    const password = $('auth-password').value;
    const bytes = new TextEncoder().encode(password).length;
    if (register && (password.length < 8 || bytes > 72)) {
      notify('Use uma senha com pelo menos 8 caracteres. Se ela for muito longa, escolha uma menor.', true);
      $('auth-password').focus();
      return;
    }
    const body = { email: $('auth-email').value.trim(), password };
    if (register) body.name = $('auth-name').value.trim();
    notify('');
    setFormBusy(form, true);
    $('login-tab').disabled = true;
    $('register-tab').disabled = true;
    $('google-auth-button').disabled = true;
    $('auth-submit').textContent = register ? 'Criando conta…' : 'Entrando…';
    try {
      const data = await api(register ? '/auth/register' : '/auth/login', { method: 'POST', body, authRequest: true });
      await enterWorkspace(data.user);
      notify(register ? 'Conta criada! Comece cadastrando sua primeira categoria.' : 'Você entrou na sua conta.');
    } catch (error) {
      showRequestError(error);
    } finally {
      $('auth-password').value = '';
      setFormBusy(form, false);
      $('login-tab').disabled = false;
      $('register-tab').disabled = false;
      $('google-auth-button').disabled = false;
      $('auth-submit').textContent = state.authMode === 'register' ? 'Criar conta' : 'Entrar';
    }
  });

  $('logout-button').addEventListener('click', async () => {
    requestStarted();
    notify('');
    try {
      await api('/auth/logout', { method: 'POST' });
      showLoggedOut();
      $('auth-form').reset();
      setAuthMode('login');
      notify('Você saiu da sua conta.');
      $('auth-email').focus();
    } catch (error) {
      showRequestError(error);
    } finally { requestFinished(); }
  });

  $('category-form').addEventListener('submit', async (event) => {
    event.preventDefault();
    const form = event.currentTarget;
    const name = $('category-name').value.trim();
    if (!name) { notify('Informe o nome da categoria.', true); return; }
    const generation = state.generation;
    notify('');
    setFormBusy(form, true);
    requestStarted();
    $('category-submit').textContent = 'Criando…';
    try {
      const category = await api('/categories', { method: 'POST', body: { name } });
      if (generation !== state.generation) return;
      state.categories.push(category);
      renderCategories();
      $('transaction-category').value = String(category.id);
      form.reset();
      notify('Categoria criada. Ela já está disponível para suas transações.');
    } catch (error) { showRequestError(error); }
    finally {
      setFormBusy(form, false);
      $('category-submit').textContent = 'Criar categoria';
      requestFinished();
    }
  });

  $('transaction-form').addEventListener('submit', async (event) => {
    event.preventDefault();
    const form = event.currentTarget;
    const body = {
      description: $('transaction-description').value.trim(),
      amount: Number($('transaction-amount').value),
      type: $('transaction-type').value,
      category_id: Number($('transaction-category').value),
      date: $('transaction-date').value,
    };
    if (!body.description || !Number.isFinite(body.amount) || body.amount <= 0 || !body.category_id) {
      notify('Preencha a descrição, o valor positivo e a categoria da transação.', true);
      return;
    }
    const generation = state.generation;
    notify('');
    setFormBusy(form, true);
    requestStarted();
    $('transaction-submit').textContent = 'Salvando…';
    try {
      await api('/transactions', { method: 'POST', body });
      if (generation !== state.generation) return;
      form.reset();
      $('transaction-date').value = localToday();
      await loadWorkspace();
      if (generation === state.generation) {
        const shown = FinanceDashboard.filterTransactions(state.transactions, filters()).some((item) => item.description === body.description && String(item.date).slice(0, 10) === body.date && Number(item.amount) === body.amount);
        notify(shown ? 'Transação cadastrada.' : 'Transação cadastrada. Ela está fora dos filtros atuais; ajuste o período ou limpe os filtros para vê-la.');
      }
    } catch (error) { showRequestError(error); }
    finally {
      setFormBusy(form, false);
      renderCategories();
      $('transaction-submit').textContent = 'Salvar transação';
      requestFinished();
    }
  });

  $('refresh-button').addEventListener('click', async () => {
    notify('');
    try { await loadWorkspace(); } catch (error) { showRequestError(error); }
  });

  $('transaction-list').addEventListener('click', async (event) => {
    const button = event.target.closest('button[data-action]');
    if (!button || button.disabled) return;
    const id = Number(button.dataset.id);
    const action = button.dataset.action;
    if (action === 'ask-delete' || action === 'cancel-delete') {
      state.pendingDelete = action === 'ask-delete' ? id : null;
      renderTransactions();
      const selector = action === 'ask-delete' ? 'confirm-delete' : 'ask-delete';
      $(`transaction-list`).querySelector(`button[data-id="${id}"][data-action="${selector}"]`)?.focus();
      return;
    }
    button.disabled = true;
    const generation = state.generation;
    requestStarted();
    notify('');
    try {
      if (action === 'confirm-delete') {
        button.textContent = 'Excluindo…';
        await api(`/transactions/${id}`, { method: 'DELETE' });
        if (generation !== state.generation) return;
        state.transactions = state.transactions.filter((transaction) => transaction.id !== id);
        state.pendingDelete = null;
        if (state.detailId === id) { $('transaction-detail').hidden = true; state.detailId = null; }
        renderTransactions();
        notify('Transação excluída.');
      } else if (action === 'detail') {
        button.textContent = 'Carregando…';
        const transaction = await api(`/transactions/${id}`);
        if (generation !== state.generation) return;
        const fields = [
          ['Descrição', transaction.description], ['Valor', currency.format(Number(transaction.amount))],
          ['Tipo', transaction.type === 'income' ? 'Receita' : 'Despesa'], ['Categoria', transaction.category],
          ['Data', formatDate(transaction.date)],
        ];
        $('detail-fields').replaceChildren();
        for (const [label, value] of fields) {
          const term = document.createElement('dt');
          const detail = document.createElement('dd');
          term.textContent = label;
          detail.textContent = value;
          $('detail-fields').append(term, detail);
        }
        state.detailId = id;
        $('transaction-detail').hidden = false;
        $('transaction-detail').focus();
      }
    } catch (error) { showRequestError(error); }
    finally {
      button.disabled = false;
      if (action === 'detail') button.textContent = 'Ver detalhes';
      if (action === 'confirm-delete') button.textContent = 'Confirmar exclusão';
      requestFinished();
    }
  });

  $('close-detail').addEventListener('click', () => {
    const id = state.detailId;
    $('transaction-detail').hidden = true;
    state.detailId = null;
    $('transaction-list').querySelector(`button[data-id="${id}"][data-action="detail"]`)?.focus();
  });

  async function restoreSession() {
    setAuthMode('login');
    resetFilters();
    $('transaction-date').value = localToday();
    try {
      const data = await api('/auth/me', { silentUnauthorized: true });
      await enterWorkspace(data.user);
    } catch (error) {
      if (!state.user) showLoggedOut();
      if (error.status !== 401) showRequestError(error);
    } finally {
      $('initial-loading').hidden = true;
      if (authResult && (authResult.error || state.user)) notify(authResult.message, authResult.error);
    }
  }
  loadAuthProviders();
  restoreSession();
})();
