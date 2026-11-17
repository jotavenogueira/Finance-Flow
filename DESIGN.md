# FinanceFlow — Design system

Referência visual: kit Figma **CRM UI Charts for SaaS Dashboards** (modo escuro, frame "04").
Canvas com o dashboard e os tokens: https://claude.ai/artifact/TA783RrQrwfjF9x5GcsfFr

Toda alteração no front-end (`web/`) segue este documento. Os tokens existem como variáveis CSS no topo de `web/style.css`: use `var(--…)` e não crie novos valores hex soltos. Se precisar de uma cor nova, adicione-a como token em `:root` e registre aqui.

## Cores

| Token | Valor | Uso |
|---|---|---|
| `--bg` | `#0A0A0B` | Fundo da página, fundo de campos e de trilhos de abas |
| `--surface` | `#141416` | Cards, header |
| `--surface-2` | `#1C1C1F` | Painéis dentro de cards, hover de botões secundários |
| `--surface-3` | `#26262A` | Botão discreto, trilho de progresso |
| `--border` | `#26262A` | Borda de cards |
| `--border-strong` | `#333338` | Borda de campos, chips e botões secundários |
| `--text` | `#F4F4F5` | Texto principal |
| `--text-2` | `#D4D4D8` | Rótulos, legendas |
| `--muted` | `#A1A1AA` | Texto auxiliar |
| `--subtle` | `#8A8A93` | Eixos de gráfico, placeholders |
| `--violet` | `#7C3AED` | Cor primária: botões, foco de marca |
| `--violet-hover` / `--violet-400` | `#8B5CF6` / `#A78BFA` | Hover, despesas, séries secundárias |
| `--lilac` | `#C4B5FD` | Início dos gradientes, anel de foco |
| `--violet-tint` | `#2E1F4D` | Fundos de ícones violeta |
| `--lime` | `#C6F432` | Receitas, indicadores positivos (texto escuro por cima) |
| `--cyan` | `#12A8E0` | Terceira série de gráficos |
| `--danger` | `#FFB4AB` (+ `--danger-bg`, `--danger-border`) | Erros, exclusão, saldo negativo |

**Receitas = lima, despesas = violeta.** As duas diferem em luminosidade, não só em matiz. Não use vermelho/verde para separar séries.

## Gradientes

- `--grad-hero`: `linear-gradient(140deg, #8B5CF6, #6D28D9 55%, #4C1D95)`, card de saldo/destaque (um por tela).
- `--grad-bar`: `linear-gradient(180deg, #C4B5FD, #7C3AED)`, barras e progresso de despesas.
- `--grad-mark`: `linear-gradient(135deg, #A78BFA, #7C3AED)`, logo e avatar.

Gradientes só nesses papéis; cards comuns são superfícies sólidas.

## Tipografia

Fonte: **Neue Montreal** (fallback `Inter Tight`, `Inter`, system-ui) via `--font`. A CSP (`style-src 'self'`) bloqueia Google Fonts: para usar a fonte real, coloque os `.woff2` em `web/` e declare `@font-face` em `style.css`.

| Estilo | Tamanho/linha | Peso |
|---|---|---|
| Display (valores grandes) | 44/48, letter-spacing −0.04em | 500 |
| H1 | 32/36, −0.025em | 500 |
| H2 (título de card) | 20/26 | 500 |
| Body | 15/24 | 400 |
| Caption / rótulos | 13/18 | 400 |

Números sempre com `font-variant-numeric: tabular-nums`. Pesos acima de 500 só na marca.

## Forma e espaçamento

- Raio: cards `--radius-card` (24px), campos `--radius-field` (14px), botões, abas, chips e busca `--radius-pill` (999px).
- Espaçamento em múltiplos de 4; gap entre cards 16px; padding de card 24px (20px em telas menores).
- Sem sombras: a profundidade vem das superfícies (`--bg` → `--surface` → `--surface-2`).

## Componentes

- **Botões** (`.button` + variante): `.primary` violeta com texto branco; `.secondary` contorno `--border-strong`; `.quiet` `--surface-3`; `.danger` coral. Altura mínima 44px.
- **Abas / segmentados** (`.tabs`): trilho `--bg` em pílula; aba ativa clara (`--text` com texto `--bg`).
- **Chips** (`.category-list li`): pílula com borda e um ponto de cor à esquerda.
- **Header** (`.site-header`): barra em pílula sobre `--surface`.
- **Cards de resumo**: o primeiro usa `--grad-hero`; os outros são `--surface` com ponto de cor da série.
- **Gráficos**: SVG nativo, grade tracejada `#2A2A2F`, barras com `rx: 3px`, legenda com pontos circulares.

## Acessibilidade

Contraste mínimo 4.5:1 (texto pequeno incluído); foco visível com `--lilac`; alvos de toque ≥ 44px; ícones com `aria-hidden` ou rótulo; layouts funcionam a 360px de largura.
