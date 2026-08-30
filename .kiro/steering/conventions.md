# Convenções

Propósito: registrar as convenções de código e teste observadas no repositório.

## Testes de propriedade (jqwik)

- Cada uma das **20 propriedades de correção** do design é implementada como **um** teste de propriedade.
- **Mínimo de 100 iterações** por teste de propriedade.
- Cada teste é anotado com um comentário no formato: **Feature: ofac-sanctions-ingestion, Property {n}: {texto}**.
- As propriedades do state machine de ponteiros (10–14) usam o modo **stateful/model-based** do jqwik.
- Não faça mock de lógica pura; escreva geradores "inteligentes" que restringem ao espaço de entrada real (contagens de alias, datas parciais, nomes não-ASCII etc.).

## Tratamento de erros: fail-closed

- A regra soberana: a versão operacional `CURRENT` **nunca** fica em risco. Todo estágio antes da ativação atômica é **fail-closed** — em qualquer erro, descarta artefatos parciais, deixa o trio de ponteiros intacto e registra um resultado observável nomeando o estágio que falhou.
- Erros "soft" (referência não resolvida, tipo desconhecido) geram diagnóstico e a transformação continua; erros "hard" (registro não parseável, falha de mapeamento de campo obrigatório) falham o estágio inteiro.

## Imutabilidade

- Versões são **imutáveis**: linhas insert-only, sem update/delete dentro de uma versão persistida.
- O arquivo de snapshot bruto é write-once; nome derivado de (`Publish_Date`, `Digest`); associado à versão só depois que o SHA-256 do arquivo bate com o `Digest` registrado.

## Disciplina da regra de dependência

- `adapter → application → domain` apenas; `application` nunca depende de `adapter`; `domain` livre de framework.
- Garantida pelo `HexagonalArchitectureTest` (ArchUnit) no `check`. Não inverta a direção das dependências.

## Onde vai o teste

- **Nova lógica de correção** (transform, escopo, dedup, reconciliação, identidade/ponteiros de versão) deve ganhar um **teste de propriedade** (e, quando útil, exemplos de borda).
- **IO e wiring** (HTTP, PostgreSQL, filesystem, Spring) recebem **testes de integração/unitários** (Testcontainers, MockWebServer, MockK), não testes de propriedade.
- Unit tests co-localizados com sufixo `.test`/convenção do source set; sem mocks para "fingir" funcionalidade — testes validam comportamento real.
