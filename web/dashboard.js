'use strict';

// Pure calculations shared by the browser and the regression tests.
(function (root) {
  const normalize = (value) => String(value || '').normalize('NFD').replace(/[\u0300-\u036f]/g, '').toLocaleLowerCase('pt-BR');
  const cents = (value) => Math.round((Number(value) + Number.EPSILON) * 100);
  const monthKey = (date) => String(date).slice(0, 7);
  function shiftMonth(month, delta) {
    const [year, index] = month.split('-').map(Number);
    const date = new Date(Date.UTC(year, index - 1 + delta, 1));
    return `${date.getUTCFullYear()}-${String(date.getUTCMonth() + 1).padStart(2, '0')}`;
  }
  function filterTransactions(transactions, filters) {
    const start = filters.period === 'six' ? shiftMonth(filters.month, -5) : filters.month;
    const query = normalize(filters.search).trim();
    return transactions.filter((item) => {
      const month = monthKey(item.date);
      return (filters.period === 'all' || (month >= start && month <= filters.month)) &&
        (!filters.type || item.type === filters.type) &&
        (!filters.category || String(item.category_id) === String(filters.category)) &&
        (!query || normalize(`${item.description} ${item.category}`).includes(query));
    }).sort((a, b) => String(b.date).localeCompare(String(a.date)) || b.id - a.id);
  }
  function summarize(transactions, filters) {
    let income = 0, expense = 0;
    const categories = new Map();
    const timeline = new Map();
    const daily = filters.period === 'month';
    if (daily) {
      const [year, month] = filters.month.split('-').map(Number);
      const days = new Date(Date.UTC(year, month, 0)).getUTCDate();
      for (let day = 1; day <= days; day++) timeline.set(`${filters.month}-${String(day).padStart(2, '0')}`, { income: 0, expense: 0 });
    } else if (filters.period === 'six') {
      for (let i = -5; i <= 0; i++) timeline.set(shiftMonth(filters.month, i), { income: 0, expense: 0 });
    }
    for (const item of transactions) {
      const value = cents(item.amount);
      const isIncome = item.type === 'income';
      if (isIncome) income += value;
      else {
        expense += value;
        const id = String(item.category_id);
        const category = categories.get(id) || { name: item.category || 'Sem categoria', amount: 0, count: 0 };
        category.amount += value; category.count++;
        categories.set(id, category);
      }
      const key = daily ? String(item.date).slice(0, 10) : monthKey(item.date);
      const point = timeline.get(key) || { income: 0, expense: 0 };
      point[isIncome ? 'income' : 'expense'] += value;
      timeline.set(key, point);
    }
    return { income, expense, balance: income - expense, count: transactions.length,
      categories: [...categories.values()].sort((a, b) => b.amount - a.amount || a.name.localeCompare(b.name, 'pt-BR')),
      timeline: [...timeline].sort(([a], [b]) => a.localeCompare(b)).map(([key, value]) => ({ key, ...value })) };
  }
  root.FinanceDashboard = { filterTransactions, summarize, shiftMonth };
  if (typeof module !== 'undefined' && module.exports) module.exports = root.FinanceDashboard;
})(typeof globalThis !== 'undefined' ? globalThis : this);
