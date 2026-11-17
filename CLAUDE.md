# FinanceFlow

- Vocabulário do domínio: veja `CONTEXT.md`.
- **Front-end (`web/`)**: toda alteração visual segue `DESIGN.md`. Use os tokens CSS de `web/style.css` (`var(--…)`), botões/abas/chips em pílula, cards `--surface` com raio 24px, receitas em lima e despesas em violeta. Não introduza cores, fontes, sombras ou raios fora do sistema sem atualizar `DESIGN.md` junto.
- A CSP em `app.go` só permite recursos de `'self'`: nada de fontes, scripts ou estilos de CDN.
- Testes do front-end: `cd tests && npm test`.
