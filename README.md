# FinanceFlow — protótipo com usuários

API em Go/Gin, PostgreSQL e uma tela web simples para testar cadastro, login,
categorias próprias e transações. A interface usa HTML, CSS e JavaScript sem
dependências; pode ser substituída depois pelo frontend em TypeScript.

## Executar

Com Docker Desktop aberto, configure o `.env` na raiz do projeto. Se ele ainda
não existir, copie `.env.example` sem sobrescrever um arquivo existente:

```powershell
if (-not (Test-Path -LiteralPath .env)) {
    Copy-Item -LiteralPath .env.example -Destination .env
}
docker compose up -d --build
```

Edite a senha no `.env` antes de iniciar um banco novo. Para um volume existente,
use a senha com que ele foi criado. O `.env` fica fora do Git e da imagem Docker.

Abra **http://localhost:8081**. Crie uma conta, cadastre uma categoria e registre
uma transação. Use Sair para testar outra conta. O cadastro já inicia a sessão.
Não existem contas ou senhas prontas no protótipo.

Se mudar o código Go ou a tela, execute novamente `docker compose up -d --build api`.
Para ver os logs: `docker compose logs -f api`.

## Como as camadas se conectam

1. **Controller:** recebe HTTP/JSON, identifica o usuário da sessão e responde
   com o status adequado.
2. **Use case:** valida regras, como tipo de transação, valor, data e campos do
   cadastro. É onde a senha é transformada em hash.
3. **Repository:** executa SQL. Toda consulta de categorias e transações recebe
   o ID do usuário autenticado.
4. **Model:** define usuário, categoria, transação e erros reconhecíveis.

O navegador recebe uma sessão em cookie `HttpOnly` e `SameSite=Strict`. O banco
guarda apenas o hash do token; logout revoga a sessão, que expira em 24 horas.
As senhas usam bcrypt. O servidor bloqueia escritas originadas por outros sites.
Em uma publicação com HTTPS, configure `SESSION_COOKIE_SECURE=true`.

## Banco e dados anteriores

Na inicialização, `db.Migrate` aplica as migrações SQL embutidas no programa.
A tabela `schema_migrations` impede que uma versão seja aplicada duas vezes.
Uma transação de banco garante que falhas não deixem a migração pela metade.

As transações antigas permanecem na tabela `transaction`, com os campos originais
e sem proprietário. Não aparecem para usuários novos e não são atribuídas ao
primeiro cadastro. Uma atribuição futura exige identificar explicitamente o dono.
O texto antigo de categoria é preservado; as novas transações também guardam a
referência `category_id`. Uma chave estrangeira composta impede associar uma
transação à categoria de outro usuário.

## API

| Método | Caminho | Função |
| --- | --- | --- |
| POST | `/auth/register` | Cadastrar e iniciar sessão |
| POST | `/auth/login` | Entrar |
| GET | `/auth/me` | Consultar usuário autenticado |
| POST | `/auth/logout` | Sair |
| GET / POST | `/categories` | Listar / criar categorias |
| GET / POST | `/transactions` | Listar / criar transações |
| GET / DELETE | `/transactions/:transactionId` | Consultar / excluir transação |

Cadastro: `name`, `email`, `password`. Categoria: `name`.
Transação: `description`, `amount`, `type`, `category_id`, `date`.
`type` aceita `income` ou `expense`; a data usa `YYYY-MM-DD`.
O proprietário vem da sessão, nunca do JSON enviado pelo cliente.
Para testar em um cliente HTTP, mantenha os cookies recebidos no login.

Sem sessão: 401. Dados inválidos: 400. Registro duplicado: 409.
Transação inexistente ou de outra pessoa: 404. Exclusão concluída: 204.

## Verificar

```powershell
go test ./...
docker compose config --quiet
```

O teste de integração `TestUserIsolationFlow` exige um banco **descartável e vazio**
com nome iniciado por `financeflow_test_` e a variável `FINANCEFLOW_TEST_DSN` com
sua URL de conexão. Sem essa variável, o teste é pulado. Ele verifica duas contas,
isolamento, categoria de outra pessoa, senha com hash, logout, expiração, proteção
de origem e preservação de um registro legado de teste.
