'use strict';

(() => {
  const $ = (id) => document.getElementById(id);
  const state = { user: null, categories: [], transactions: [], authMode: 'login', pendingDelete: null, detailId: null, generation: 0 };
  const currency = new Intl.NumberFormat('pt-BR', { style: 'currency', currency: 'BRL' });
  let activeRequests = 0;

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
  }

  function requestFinished() {
    activeRequests = Math.max(0, activeRequests - 1);
    $('logout-button').disabled = activeRequests > 0;
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
    const count = state.transactions.length;
    $('transaction-count').textContent = count === 0 ? 'Nenhuma transação cadastrada.' : `${count} ${count === 1 ? 'transação cadastrada' : 'transações cadastradas'}.`;
    $('transactions-empty').hidden = count > 0;
    for (const transaction of state.transactions) {
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
      if (generation === state.generation) notify('Transação cadastrada.');
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
    $('transaction-date').value = localToday();
    try {
      const data = await api('/auth/me', { silentUnauthorized: true });
      await enterWorkspace(data.user);
    } catch (error) {
      if (!state.user) showLoggedOut();
      if (error.status !== 401) showRequestError(error);
    } finally { $('initial-loading').hidden = true; }
  }
  restoreSession();
})();
