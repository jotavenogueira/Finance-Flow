'use strict';
const assert = require('node:assert/strict');
const { filterTransactions, summarize, shiftMonth } = require('../web/dashboard.js');
const rows = [
  { id: 1, description: 'Salário', amount: 3000.10, type: 'income', category_id: 1, category: 'Trabalho', date: '2026-01-10' },
  { id: 2, description: 'Ônibus', amount: 0.10, type: 'expense', category_id: 2, category: 'Transporte', date: '2026-01-12' },
  { id: 3, description: 'Metrô', amount: 0.20, type: 'expense', category_id: 2, category: 'Transporte', date: '2026-01-12' },
  { id: 4, description: 'Aluguel', amount: 1000, type: 'expense', category_id: 3, category: 'Casa', date: '2025-12-01' },
  { id: 5, description: 'Futuro', amount: 500, type: 'income', category_id: 1, category: 'Trabalho', date: '2026-02-01' },
  { id: 6, description: 'Antigo', amount: 20, type: 'expense', category_id: 3, category: 'Casa', date: '2025-07-31' },
];
const filters = { period: 'month', month: '2026-01', type: '', category: '', search: '' };
let filtered = filterTransactions(rows, filters);
assert.deepEqual(filtered.map(t => t.id), [3, 2, 1], 'date and ID ordering, selected month only');
let summary = summarize(filtered, filters);
assert.equal(summary.income, 300010);
assert.equal(summary.expense, 30, 'currency uses integer cents');
assert.equal(summary.balance, 299980);
assert.equal(summary.categories[0].amount, 30);
assert.equal(summary.timeline.length, 31);
assert.equal(summary.timeline.find(p => p.key === '2026-01-12').expense, 30);
assert.equal(summary.timeline.reduce((sum,p) => sum+p.income-p.expense,0),summary.balance,'chart reconciles with balance');
assert.deepEqual(filterTransactions(rows, {...filters, search:'onibus'}).map(t=>t.id),[2], 'accent insensitive description');
assert.deepEqual(filterTransactions(rows, {...filters, search:'  TRANSPÓRTE  ', type:'expense', category:'2'}).map(t=>t.id),[3,2]);
assert.equal(filterTransactions(rows, {...filters, type:'income', category:'2'}).length,0,'filters intersect');
const six = {...filters,period:'six'};
filtered = filterTransactions(rows,six);
assert.deepEqual(filtered.map(t=>t.id),[3,2,1,4], 'six-month window crosses year and excludes future');
summary = summarize(filtered,six);
assert.deepEqual(summary.timeline.map(t=>t.key),['2025-08','2025-09','2025-10','2025-11','2025-12','2026-01']);
assert.equal(summary.timeline.reduce((sum,p)=>sum+p.expense,0),summary.expense);
assert.equal(shiftMonth('2026-01',-5),'2025-08');
const leap = summarize([],{...filters,month:'2024-02'});
assert.equal(leap.timeline.length,29,'leap year February');
assert.equal(leap.balance,0);
assert.deepEqual(leap.categories,[]);
assert.equal(filterTransactions(rows,{...filters,period:'all'}).length,6,'all history includes future entries');
const negative = summarize(filterTransactions(rows,{...filters,type:'expense'}),filters);
assert.equal(negative.balance,-30,'expense-only balance is negative');
const removed = summarize(filterTransactions(rows.filter(t=>t.id!==3),filters),filters);
assert.equal(removed.expense,10,'deletion updates all totals');
assert.equal(rows[0].id,1,'source data is not sorted in place');
console.log('Dashboard: cálculos, filtros, centavos, períodos, gráficos e exclusão verificados.');
