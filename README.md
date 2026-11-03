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
go test -tags=nomsgpack ./...
docker compose config --quiet
```

O teste de integração `TestUserIsolationFlow` exige um banco **descartável e vazio**
com nome iniciado por `financeflow_test_` e a variável `FINANCEFLOW_TEST_DSN` com
sua URL de conexão. Sem essa variável, o teste é pulado. Ele verifica duas contas,
isolamento, categoria de outra pessoa, senha com hash, logout, expiração, proteção
de origem e preservação de um registro legado de teste.

## Dashboard financeiro

A visão geral inclui receitas, despesas e saldo das movimentações filtradas.
É um saldo do período: não representa um saldo bancário importado ou uma
projeção de caixa. Os totais são calculados em centavos.

- Filtros combinados por mês, seis meses até o mês escolhido, todo o histórico,
  tipo, categoria e busca por descrição/categoria (sem diferenciar acentos).
- Gráfico diário para um mês; mensal para seis meses ou todo o histórico.
- Distribuição das despesas por categoria e tabela acessível dos valores.
- Resumo, gráficos e lista usam o mesmo conjunto de movimentações.
- Limpar filtros retorna ao mês atual. Todo o histórico inclui lançamentos
  futuros, caso existam. Nenhum dado de demonstração é incluído.
- Cálculos no navegador com as transações já fornecidas pela API; sem migração
  de banco, bibliotecas de gráficos ou chamadas externas adicionais.

Testes do dashboard (Node.js 24+):

```sh
node tests/dashboard.test.cjs
cd tests
npm install
npm test
```

O teste de interface usa jsdom com API simulada; não substitui a verificação
visual em um navegador nem o teste de integração com PostgreSQL.

### Referência visual

Arquivo Figma `NcpgwmCyjuciAT6qpvRNjh`, quadro Dashboard 1 / Dark mode
`3899:125939`, localizado na página Start Here. O link original apontava
para a capa do kit. A referência foi consultada pelo navegador autenticado.

Especificações confirmadas na interface do Figma:
- Fonte Neue Montreal (o próprio Figma a sinaliza como ausente).
- Header 1: 32/36; Header 3: 20/130%; Body: 15/24; Caption: 12/20.
- Fundo principal escuro Background/Surface/primary: #010101.
- Espaçamento do quadro: 24 px; cantos externos: 20 px.

A interface é uma adaptação para finanças pessoais, não uma reprodução
exata do quadro. A fonte é a primeira opção da família CSS, mas seus arquivos
não estão incluídos: sem Neue Montreal instalada, o navegador usa a fonte
alternativa. Para garantir a mesma fonte em todos os dispositivos, ainda
é necessário fornecer os arquivos webfont apropriados.

O build Docker e os testes Go usam `nomsgpack`, pois os endpoints usam JSON.
Essa opção é documentada pelo Gin:
https://github.com/gin-gonic/gin/blob/master/docs/doc.md#build-tags

Validação: testes de cálculos e interface simulada, testes Go com nomsgpack
e prévia visual com dados fictícios. O teste com PostgreSQL não foi executado.
## Hospedagem na Vercel

O preset Go executa este mesmo servidor e os arquivos da interface continuam
embutidos no binario. O arquivo `vercel.json` configura o preset, a compilacao
JSON (`nomsgpack`), cookies HTTPS e a regiao de Sao Paulo (`gru1`). O PostgreSQL
online deve ficar na mesma regiao. O Docker Compose continua sendo o ambiente local.

A integracao Neon fornece `DATABASE_URL` ao projeto como segredo. Essa URL tem
prioridade sobre as seis variaveis `DB_*`. Quando nao existe, a conexao local
continua usando o `.env` atual. Nao coloque a URL real no Git nem em arquivos publicos.

O driver pgx aceita a autenticacao `channel_binding=require` fornecida pelo Neon.
A API mantem esse requisito e usa `sslmode=verify-full`, validando o certificado
e o nome do servidor. O pool de cada instancia e limitado a cinco conexoes, com
duas ociosas, para uso junto ao pooler do provedor.

No primeiro acesso, as migracoes preparam o banco online vazio. Dados do Docker
local nao sao enviados automaticamente. Cada ambiente deve ter sua propria
conexao; o banco configurado nesta publicacao foi ligado apenas a Production.

Confira na Vercel a branch de producao antes de publicar novos commits. O deploy
deste projeto acompanha a branch main.
