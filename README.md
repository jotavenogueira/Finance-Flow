# FinanceFlow — protótipo com usuários

API em Java 21/Spring Boot, PostgreSQL e uma tela web simples para testar cadastro,
login, categorias próprias e transações. A interface usa HTML, CSS e JavaScript sem
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

Se mudar o código Java ou a tela, execute novamente `docker compose up -d --build api`.
Para ver os logs: `docker compose logs -f api`.

Para executar sem Docker, use um JDK 21 e o Maven Wrapper incluído. A aplicação lê
o `.env` da pasta atual; as variáveis já definidas no ambiente têm prioridade:

```powershell
.\mvnw.cmd spring-boot:run
```

## Como as camadas se conectam

O código fica em `src/main/java/com/financeflow`, com um pacote por camada:

1. **Controller:** recebe HTTP/JSON, identifica o usuário da sessão e responde
   com o status adequado. `HttpProtectionFilter` aplica os cabeçalhos de
   segurança (inclusive a CSP), o limite de 1 MiB e o bloqueio de origem cruzada;
   `AuthInterceptor` protege as rotas que exigem sessão.
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

Na inicialização, `DatabaseMigrator` aplica as migrações SQL de
`src/main/resources/db/migrations`, embutidas no jar, antes de o servidor aceitar
requisições. Bancos criados pela versão em Go continuam compatíveis: a tabela,
as senhas bcrypt e as sessões existentes são lidas do mesmo jeito.
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
| POST | `/auth/login` | Entrar com e-mail e senha |
| GET | `/auth/providers` | Consultar se o login Google está configurado |
| POST | `/auth/google/start` | Iniciar login ou cadastro com Google |
| GET | `/auth/google/callback` | Receber o retorno do Google |
| POST | `/auth/google/link/start` | Conectar Google à conta autenticada |
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
.\mvnw.cmd test
docker compose config --quiet
```

Sem JDK instalado, os mesmos testes rodam em um container Maven:

```powershell
docker run --rm -v "${PWD}:/src" -w /src maven:3.9-eclipse-temurin-21 mvn -B test
```

Os testes de integração (`UserIsolationIntegrationTest` e
`GoogleRepositoryIntegrationTest`) exigem um banco **descartável** com nome
iniciado por `financeflow_test_` e a variável `FINANCEFLOW_TEST_DSN` com sua URL
de conexão (por exemplo `postgres://postgres:senha@localhost:5432/financeflow_test_local?sslmode=disable`).
Cada teste cria e remove um schema próprio. Sem essa variável, eles são pulados.
Verificam duas contas, isolamento, categoria de outra pessoa, senha com hash,
logout, expiração, proteção de origem, preservação de um registro legado de teste
e as regras de vínculo e de desafio do login Google.

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

## Hospedagem

A Vercel não tem runtime Java, então o preset Go e o `vercel.json` foram
removidos junto com o backend em Go. A API agora é publicada como container: o
`Dockerfile.yml` gera a imagem usada pelo Docker Compose e serve para qualquer
provedor que execute containers. Configure no provedor `PORT` (quando ele não
definir), `SESSION_COOKIE_SECURE=true`, `DATABASE_URL` e as três `GOOGLE_*`.
Mantenha a API na mesma região do PostgreSQL online.

`DATABASE_URL` (por exemplo, a fornecida pelo Neon) tem prioridade sobre as seis
variáveis `DB_*`. Quando não existe, a conexão local continua usando o `.env`
atual. Não coloque a URL real no Git nem em arquivos públicos.

O driver PostgreSQL JDBC aceita `channel_binding=require` fornecido pelo Neon.
A API mantém esse requisito e usa `sslmode=verify-full`, validando o certificado
(com as autoridades confiáveis da JVM) e o nome do servidor. O pool de cada
instância é limitado a cinco conexões, com duas ociosas, para uso junto ao
pooler do provedor.

No primeiro acesso, as migrações preparam o banco online vazio. Dados do Docker
local não são enviados automaticamente. Cada ambiente deve ter sua própria conexão.


## Login com Google

O botão **Continuar com Google** aparece quando as três variáveis `GOOGLE_*`
estão preenchidas e a URL de retorno é válida. O login por e-mail e senha
continua disponível. O navegador vai até o Google e retorna ao backend; não há
SDK do Google executando na página.

### Configurar o cliente OAuth

1. Abra o [Google Cloud Console](https://console.cloud.google.com/) e selecione
   ou crie o projeto que identificará o FinanceFlow.
2. Em **Google Auth Platform**, configure a apresentação do aplicativo em
   **Branding** e seu público em **Audience**. Use seus dados reais de suporte.
   Para um aplicativo externo em teste, configure os usuários de teste quando
   exigido pelo console. A publicação para outras pessoas depende do estado e
   das exigências de verificação apresentados pelo Google.
3. Em **Clients**, crie um cliente do tipo **Web application**.
4. Adicione as seguintes **Authorized redirect URIs**, exatamente como escritas:

   ```text
   http://localhost:8081/auth/google/callback
   https://finance-flow-nogzz.vercel.app/auth/google/callback
   ```

   A porta, o protocolo e o caminho precisam coincidir. Se mudar o domínio ou a
   porta da aplicação, atualize o cadastro do cliente e a variável do ambiente.
   Este fluxo usa redirecionamento pelo servidor; não precisa configurar origens
   JavaScript para um SDK no navegador.
5. Guarde o **Client ID** e o **Client secret** em local privado. Use os valores
   apenas no ambiente em que a API executa.

O procedimento e as regras de retorno estão na
[documentação OAuth para aplicações web do Google](https://developers.google.com/identity/protocols/oauth2/web-server).
O FinanceFlow pede somente `openid`, `email` e `profile` para identificar a pessoa;
não solicita acesso ao Gmail, Drive ou acesso offline.

### Ambiente local e produção

No `.env` local, acrescente seus valores reais sem sobrescrever as configurações
do banco. Os valores abaixo são apenas indicações do que preencher:

```dotenv
GOOGLE_CLIENT_ID=seu_client_id
GOOGLE_CLIENT_SECRET=seu_client_secret
GOOGLE_REDIRECT_URL=http://localhost:8081/auth/google/callback
```

No provedor de hospedagem, cadastre as mesmas três variáveis no ambiente de
produção, usando o retorno do domínio público. Enquanto o domínio for o atual:

```text
GOOGLE_REDIRECT_URL=https://finance-flow-nogzz.vercel.app/auth/google/callback
```

Se a API passar a outro domínio, atualize essa variável e as **Authorized
redirect URIs** do cliente OAuth. Marque o segredo como sensível quando essa
opção estiver disponível e publique de novo depois de salvar as variáveis. O
segredo fica no ambiente da API; não o coloque no HTML, no JavaScript, no
Dockerfile ou no Git.
O `.env` já é ignorado pelo Git e pelo contexto da imagem Docker. O arquivo
`.env.example` deve continuar apenas com campos vazios ou exemplos públicos.

Com Google habilitado, a página inicial acessada por outro alias redireciona
para a origem configurada em `GOOGLE_REDIRECT_URL`. Isso mantém o início do login
e o retorno no mesmo domínio, onde o cookie pode ser validado. Na produção,
compartilhe o endereço configurado em `GOOGLE_REDIRECT_URL`.

Após alterar o código ou a configuração local, recrie a API:

```powershell
docker compose up -d --build api
```

Esse comando preserva o volume do PostgreSQL. A migração adiciona os vínculos
Google e permite usuários sem senha local; as contas e transações existentes
permanecem no banco.

### Contas novas e contas existentes

- **Conta nova:** Continuar com Google cria o usuário e inicia uma sessão do
  FinanceFlow. A conta criada por esse caminho não recebe uma senha local.
- **Conta já cadastrada com senha:** entre primeiro com e-mail e senha e clique
  em **Conectar Google**. Escolha a conta Google com o mesmo e-mail. Depois do
  vínculo, os dois métodos acessam o mesmo usuário e suas transações.
- Um e-mail igual não provoca vínculo automático entre contas. A identidade
  Google é associada ao identificador estável da conta (`sub`).
- **Sair da conta** encerra a sessão do FinanceFlow; não desconecta sua conta
  Google do navegador.

O backend valida o token de identidade, incluindo assinatura, emissor,
destinatário, validade, nonce e e-mail verificado. A tentativa de login usa
`state`, PKCE e um registro temporário no banco vinculado ao navegador, com
validade de dez minutos e consumo único. O callback cria a sessão local já usada
pela aplicação; tokens do Google não são entregues ao JavaScript nem persistidos
como credenciais para outras APIs.

### Conferir a integração

```powershell
.\mvnw.cmd test
cd tests
npm install
npm test
```

Os testes Java cobrem regras de vínculo e validação do fluxo OAuth; a validação de
tokens utiliza um provedor local com tokens assinados, sem entrar em uma conta
Google real. O teste `google-auth-ui.test.cjs` verifica o botão, o retorno,
mensagens seguras e a continuidade do login por senha quando Google está
indisponível. Os testes de integração com PostgreSQL continuam exigindo
`FINANCEFLOW_TEST_DSN` apontando para um banco descartável `financeflow_test_*`.

Depois de configurar o cliente e as variáveis, teste também no navegador com
contas Google reais: criar uma conta, sair, entrar novamente, conectar uma conta
existente e cancelar o consentimento. Passar nos testes locais não confirma a
configuração do cliente no console nem a publicação das variáveis no provedor.
