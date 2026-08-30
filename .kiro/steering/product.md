# Produto

Propósito: descrever, em uma visão geral, o que este produto é e o vocabulário de domínio usado no restante da documentação.

## Visão geral

Este é um **pipeline de ingestão de listas de sanções da OFAC**. Ele consulta periodicamente cada lista da OFAC (via `HEAD` + `Digest`), baixa o snapshot completo **apenas quando o conteúdo muda**, valida a integridade (SHA-256) e a boa-formação do XML, transforma o Advanced XML em um modelo interno normalizado com escopo em pessoas e entidades, persiste o resultado como uma **versão imutável** e — depois de reconciliar a contagem contra a contagem reportada pela fonte — a **ativa atomicamente** como `CURRENT`. Mantém as três versões operacionais mais recentes por lista, com rollback instantâneo baseado em ponteiro, e expõe uma **API de consulta somente leitura** (listagem paginada + busca por nome) sobre a versão `CURRENT`. O núcleo é independente de fonte: adicionar UN ou EU no futuro é escrever um novo adapter, não reescrever o pipeline.

## Termos de domínio

- **Source_List** — uma lista da OFAC (ex.: SDN, Consolidated). Cada lista tem sua própria linha de versões, independente das demais.
- **Version** — um import imutável de um snapshot; identidade = (`Publish_Date`, `Digest` SHA-256). Duas publicações no mesmo dia com conteúdo diferente são versões distintas.
- **CURRENT / PREVIOUS / N_MINUS_2** — as três versões operacionais (HOT) mais recentes de uma lista. A ativação repointa `CURRENT` atomicamente; versões deslocadas além de `N_MINUS_2` viram `COLD`.
- **Raw_Snapshot_Store** — armazenamento em arquivo (pasta local versionada) do snapshot bruto, com nome derivado de (`Publish_Date`, `Digest`); nunca gravado no banco. Base para reconstrução fiel de um estado publicado.
- **In_Scope_Records** — os registros dentro do escopo (apenas `Individual` e `Entity`); vessels e aircraft são excluídos. É o que a API de consulta serve a partir de `CURRENT`.
