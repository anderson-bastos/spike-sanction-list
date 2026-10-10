# Agente Revisor de Arquitetura — Visão Geral e Guia

Documento de apresentação do **revisor de arquitetura automatizado** do projeto OFAC Sanctions Ingestion: o que é, como está montado (agente + workflow + script), a configuração necessária e o passo a passo de uso.

---

## 1. Para que serve

Um **revisor de arquitetura por IA** que roda no GitHub Actions, analisa o código-fonte do repositório sob a ótica das regras arquiteturais do projeto (Hexagonal / Ports & Adapters, invariantes de domínio, contrato spec-first, convenções de teste) e produz **findings acionáveis** — opcionalmente abrindo **issues** no repositório.

Ele **complementa**, não substitui, a guarda determinística que já existe: o teste **ArchUnit** (`HexagonalArchitectureTest`) falha o build em violação da regra de dependência, sem IA. O agente agrega uma camada de revisão qualitativa (impacto, recomendação, testes sugeridos) que uma asserção estática não captura.

---

## 2. As três peças

```
.github/agents/code-architecture-reviewer.agent.md   (1) a PERSONA / instruções do revisor
.github/workflows/architecture-review.yml            (2) o WORKFLOW que orquestra a execução
.github/scripts/architecture_review.py               (3) o SCRIPT que coleta código, chama a IA, publica
```

### (1) O agente (`.agent.md`)
Um documento Markdown com *frontmatter* (`name`, `description`) e o **checklist de revisão** calibrado para este projeto: regra de dependência (`adapter → application → domain`), onde código novo deve morar, contrato OpenAPI spec-first, invariantes (imutabilidade de versões, fail-closed, ativação atômica, escopo Individual/Entity), as 20 propriedades de correção e as regras de fidelidade de dados da OFAC. **É só texto** — serve como *system prompt* para o script; não roda sozinho.

### (2) O workflow (`architecture-review.yml`)
GitHub Actions, acionado **manualmente** (`workflow_dispatch`) com um input booleano **`publish_issues`** (abrir issues? sim/não). O job `review`:
- usa o GitHub **Environment `architecture-reviewer`** (de onde vêm as credenciais);
- faz checkout do repositório;
- gera um **token de GitHub App** via `actions/create-github-app-token@v3` (a partir do `client-id` + `private-key`), com permissão `issues: write`;
- instala Python 3.12 + `openai` e `requests`;
- executa o script passando `OPENAI_API_KEY`, o token da App e o flag `PUBLISH_ISSUES`.

### (3) O script (`architecture_review.py`)
O trabalho de fato:
1. **Coleta** arquivos-fonte do repositório (extensões `.kt/.java/.kts/.yml/.yaml/.properties/.sql`), ignorando `build/`, `.gradle/`, etc., com limites de tamanho/quantidade (até 75 arquivos / ~130 mil caracteres) para caber no prompt.
2. **Carrega** a persona do `.github/agents/code-architecture-reviewer.agent.md` como instrução do sistema (com fallback para um prompt genérico se o arquivo não for achado).
3. **Chama a OpenAI** (modelo configurável por `OPENAI_MODEL`, padrão `gpt-4.1-mini`), pedindo findings em **JSON estruturado** — cada finding com `title`, `severity` (high/medium/low), `file`, `line`, `evidence`, `impact`, `recommendation`, `tests`.
4. **Valida** cada finding: o arquivo/linha precisam existir de fato no input (evita "alucinação" de referências), a severidade precisa ser válida, e deduplica por um `finding_id` (hash de arquivo+título). Máximo de 12 findings.
5. **Grava** o relatório em `architecture-review-results.json`.
6. **Publica** (se `PUBLISH_ISSUES=true`): abre uma issue por finding via API do GitHub, com um marcador `ARCH-REVIEW-ID:<hash>` no corpo. Em execuções futuras, findings já publicados (mesmo hash) são **ignorados** — não gera issue duplicada.

> Observação de design: o script é **read-only** sobre o código (só lê e analisa). A única escrita que faz é abrir issues, e somente quando `publish_issues` é marcado.

---

## 3. Configuração (pré-requisitos, feitos na UI do GitHub)

A execução depende de um **GitHub Environment** chamado `architecture-reviewer` com três credenciais. Em **Settings → Environments → New environment** (`architecture-reviewer`):

| Tipo | Nome | Conteúdo |
| ---- | ---- | -------- |
| Variable | `ARCH_REVIEWER_CLIENT_ID` | **Client ID** da GitHub App (formato `Iv23...`) |
| Secret | `ARCH_REVIEWER_PRIVATE_KEY` | conteúdo da **private key** (`.pem`) da GitHub App |
| Secret | `OPENAI_API_KEY` | chave da API da OpenAI |

> **Segurança — importante:** a private key (`.pem`) **nunca** deve ser commitada no repositório. O `.gitignore` já ignora `*.pem`/`*.key`. O conteúdo dela vai apenas no secret do Environment; o arquivo local deve ficar fora do repositório (ou ser removido após configurar o secret). Em caso de qualquer suspeita de exposição, regenere a chave na GitHub App.

A GitHub App em si precisa estar **instalada** no repositório/organização, com permissão de **Issues: Read and write** (para o token conseguir abrir issues).

---

## 4. Passo a passo de uso

### Rodar pela interface do GitHub
1. Abra a aba **Actions** do repositório.
2. Selecione o workflow **Architecture Review** na lista à esquerda.
3. Clique em **Run workflow** (canto direito).
4. Escolha a branch (ex.: `main`) e marque **`publish_issues`** se quiser que os findings virem **issues** (desmarcado = apenas registra no log e no relatório, sem criar issue — "dry run").
5. **Run workflow**.

### Rodar pela linha de comando (gh CLI autenticado)
```
gh workflow run "Architecture Review" --ref main -f publish_issues=true
```

### Ver o resultado
- **Log da execução:** Actions → a execução → job **review** → step **Run Architecture Reviewer** (mostra quantos arquivos foram revisados, quantos findings validados e os links das issues criadas).
- **Relatório estruturado:** `architecture-review-results.json` (findings em JSON) gerado na execução.
- **Issues:** se `publish_issues` estava marcado, cada finding vira uma issue com severidade, evidência (arquivo:linha + trecho), impacto, recomendação e testes sugeridos.

---

## 5. Fluxo end-to-end (resumo visual)

```
Você (Actions / gh)  --dispara-->  workflow architecture-review.yml
        |                                   |
        |                       Environment architecture-reviewer
        |                       (CLIENT_ID, PRIVATE_KEY, OPENAI_API_KEY)
        v                                   v
   publish_issues?            gera token da GitHub App (issues:write)
        |                                   |
        v                                   v
             python .github/scripts/architecture_review.py
                                   |
             coleta fontes --> OpenAI (persona do .agent.md) --> findings JSON
                                   |
                        valida (arquivo/linha reais, dedup)
                                   |
                 +-----------------+------------------+
                 | PUBLISH_ISSUES=false               | PUBLISH_ISSUES=true
                 v                                    v
          só log + report.json              abre 1 issue por finding
                                            (ignora duplicadas por hash)
```

---

## 6. Guarda determinística que acompanha (sem IA)

Independente do agente, o repositório já reforça a arquitetura de forma objetiva no `./gradlew check`:
- **ArchUnit** (`HexagonalArchitectureTest`): dependências só apontam para dentro; domínio livre de framework.
- **Teste de contrato OpenAPI**: o contrato curado é a fonte de verdade e o código não compila/valida se divergir.
- **Testes de mutação (PITest)**: medem a efetividade da suíte sobre o domínio (gate mínimo configurado).

O agente de IA é a camada **qualitativa** por cima dessas guardas **quantitativas**.

---

## 7. Notas e limitações honestas

- O agente **não roda sozinho**: alguém dispara o workflow (manual) — não há trigger automático em PR hoje (fácil de adicionar, se desejado).
- A qualidade dos findings depende do **modelo** e do **recorte** do código enviado (limites de tamanho): arquivos além do limite não entram no prompt daquela execução.
- A publicação de issues exige o Environment configurado e a App instalada com permissão de Issues; sem isso, o step do token falha.
- Custo: cada execução faz uma chamada paga à OpenAI.
