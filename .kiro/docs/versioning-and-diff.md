# Versionamento, frequência de import e diff entre versões

Propósito: explicar (a) o **modelo de versões** e o que já é guardado, (b) **de quanto em quanto tempo o import roda**, e (c) uma **proposta de design** para o diff entre versões ("quem entrou / quem saiu / quem mudou"). As seções (a) e (b) descrevem o que **existe hoje**; a seção (c) é **projeto de trabalho futuro — ainda não implementado**.

---

## a) Modelo de versões (existe hoje)

Cada `Source_List` (SDN, CONSOLIDATED) tem sua **própria linha de versões**, independente das demais.

- **Identidade da versão** = (`Publish_Date`, `Digest` SHA-256 do snapshot bruto). Duas publicações no mesmo dia com conteúdo diferente são **versões distintas** (o digest desempata).
- **Janela HOT (3 versões):** `CURRENT` (ativa), `PREVIOUS`, `N_MINUS_2`. A ativação **repointa `CURRENT` atomicamente**; a janela rotaciona (`CURRENT→PREVIOUS→N_MINUS_2`) e o que é deslocado além de `N_MINUS_2` vira **`COLD`**.
- **Imutabilidade:** cada versão é insert-only; seus registros nunca são alterados. O **snapshot bruto** é gravado uma vez como arquivo no `Raw_Snapshot_Store`, nomeado por (`Publish_Date`, `Digest`).
- **Retenção do COLD:** configurável (`RetentionManager`). O período é uma **decisão de negócio em aberto**; quando habilitada, a versão COLD é mantida com seu arquivo bruto (base para reconstrução fiel).

**Consequência importante para o diff:** os dados de **duas ou mais versões coexistem** no `Data_Store` (tabela `records`, escopada por `version_id`) enquanto estiverem HOT (ou COLD retido). Ou seja, **a matéria-prima para computar o delta já está persistida** — só falta a lógica que a compara (seção c).

---

## b) Frequência do import (existe hoje)

O `Scheduler` dispara **um ciclo de ingestão por `Source_List`** a cada intervalo configurado. O gatilho é Spring `@Scheduled` com `fixedDelay` (o próximo tick só começa depois que o anterior termina — nunca há sobreposição).

- **Knob:** `ofac.scheduler.interval` — **padrão `6h`**, **limitado a `[1m .. 1d]`** (validado na subida; fora do intervalo, a aplicação não sobe).
- **Detecção de mudança barata:** cada ciclo faz um `HEAD` e compara o `Digest`/`Last-Modified` com a última versão ingerida. **Só baixa o snapshot completo se o conteúdo mudou** — do contrário o ciclo termina como `SKIPPED_NO_CHANGE`, sem download.
- **Import sob demanda:** o profile `bootstrap` dispara **um** ciclo no startup (`--spring.profiles.active=bootstrap`), útil para rodar o primeiro import ou um import manual. Ver `operations.md`.
- **Falhou?** Todo estágio antes da ativação é fail-closed: `CURRENT` fica intacto e o **próximo tick agendado é a retentativa** (não há checkpoint; reprocessar é barato).

Resumo: **por padrão, verifica a cada 6 horas**; ajustável entre 1 minuto e 1 dia; só reimporta quando a OFAC publica algo novo.

---

## c) Diff entre versões — "quem entrou / saiu / mudou" (PROPOSTA, NÃO IMPLEMENTADO)

> ⚠️ **Status: não implementado.** Não existe hoje nenhum endpoint, serviço ou consulta que compute o delta entre versões. Esta seção é um **design proposto** para virar tarefas no `tasks.md` quando priorizado. Os dados necessários já existem (seção a).

### Objetivo

Dado um `Source_List` e duas versões (`from` e `to`), responder:

- **added** — `FixedRef`s presentes em `to` e ausentes em `from` (entraram na lista).
- **removed** — `FixedRef`s presentes em `from` e ausentes em `to` (saíram da lista).
- **changed** — `FixedRef`s presentes em ambas cujo conteúdo do registro difere.
- **unchanged** (opcional / apenas contagem) — presentes em ambas e idênticas.

A chave de correlação natural é o **`FixedRef`** (id estável da OFAC), que é justamente a chave de dedup/cross-version do modelo.

### Contrato de domínio proposto (camada `domain`, lógica pura)

```
VersionDiff.compute(from: List<InternalModelEntry>, to: List<InternalModelEntry>) -> DiffResult

DiffResult = {
  added:     [InternalModelEntry],     // só em `to`
  removed:   [InternalModelEntry],     // só em `from`
  changed:   [EntryChange],            // em ambos, conteúdo != 
  unchangedCount: int
}
EntryChange = { fixedRef, before: InternalModelEntry, after: InternalModelEntry, changedFields: [string] }
```

Sendo lógica pura sobre dois conjuntos, isto é **candidato natural a property test** (ver `conventions.md`), com propriedades como:
- `added` e `removed` são disjuntos e nenhum aparece em `changed`;
- `|added| - |removed| == |to| - |from|` em termos de `FixedRef` distintos;
- diff de uma versão com ela mesma ⇒ tudo vazio e `unchangedCount == |versão|`;
- todo `FixedRef` de `from ∪ to` cai em exatamente um bucket.

### Decisão em aberto: o que conta como "changed"

Precisa de decisão de negócio (afeta a semântica e o teste):
- **(A) Qualquer campo** do `InternalModelEntry` difere (nome, aliases, endereços, documentos, programas, ...). Mais sensível; muitos "changed".
- **(B) Só campos relevantes para compliance** (ex.: `sanctionPrograms`, `primaryName`, `aliases`). Menos ruído; ignora mudanças cosméticas.
- Recomenda-se começar por **(A)** com `changedFields` explicitando quais campos mudaram, deixando o consumidor filtrar — e evoluir para (B) se necessário.

> Nota: comparar por conteúdo **não** pode usar o `versionId` do registro (ele sempre difere entre versões, por definição). O comparador deve ignorar `versionId` ao decidir igualdade.

### Superfícies de exposição propostas

**Porta de entrada (`application.port.in`)** + endpoint na Query API (somente leitura):

```
GET /api/{sourceList}/diff?from={versionRef}&to={versionRef}&type=added|removed|changed&offset=&limit=
```

- `versionRef` poderia ser um rótulo de ponteiro (`CURRENT`, `PREVIOUS`, `N_MINUS_2`) — o caso de uso mais comum é **`from=PREVIOUS&to=CURRENT`** ("o que mudou no último import?") — ou uma identidade `(publish_date,digest)` explícita.
- Reaproveita a paginação/ordenação por `FixedRef` já usada na listagem.
- Restrição: `from`/`to` precisam estar **disponíveis** (HOT, ou COLD retido); um ref inexistente/expirado é erro de cliente.

Esboço de SQL (conceitual), aproveitando que ambas as versões estão na tabela `records`:

```sql
-- added: FixedRefs em `to` que não estão em `from`
SELECT t.* FROM records t
WHERE t.publish_date = :toPd AND t.digest = :toDg
  AND NOT EXISTS (
    SELECT 1 FROM records f
    WHERE f.publish_date = :fromPd AND f.digest = :fromDg
      AND f.fixed_ref = t.fixed_ref)
ORDER BY t.fixed_ref;
-- removed: espelhado (troca `to`/`from`)
-- changed: JOIN por fixed_ref onde o conteúdo normalizado difere
```

### Alternativa: diff a partir do snapshot bruto (COLD)

Para versões já fora do HOT porém **retidas** (COLD), o diff pode ser reconstruído a partir dos arquivos do `Raw_Snapshot_Store` (re-transformando), já que o `RetentionManager` garante a fidelidade (SHA-256 do arquivo == `Digest`). Mais caro; útil para janelas históricas além das 3 HOT.

### Onde cada peça moraria (disciplina Hexagonal)

- `VersionDiff` (lógica pura) → **`domain`** (ex.: `domain.diff`).
- Caso de uso "diff entre dois refs de uma lista" → **`application`**, com uma porta `in` nova.
- Endpoint REST → **`adapter.in.web`** (novo controller ou rota no existente).
- Leitura das duas versões → reusar `VersionStore`/`Data_Store` (porta `out`), possivelmente uma consulta nova no `PgQueryApi`/um novo adapter de leitura.

### Sugestão de tarefas (quando priorizado)

1. `domain.diff.VersionDiff.compute` + **property test** (buckets disjuntos, completude, diff-consigo-mesmo).
2. Decisão de negócio: semântica de "changed" (A vs B).
3. Porta `in` + caso de uso em `application` (resolver `from`/`to`, inclusive rótulos de ponteiro).
4. Leitura das duas versões no `Data_Store` (consulta escopada por `version_id`).
5. Endpoint `GET /api/{sourceList}/diff` em `adapter.in.web` + testes de integração (Testcontainers).
6. (Opcional) diff a partir de COLD via `Raw_Snapshot_Store`.

---

## Perguntas frequentes (mapeamento rápido)

| Pergunta | Hoje | Onde |
| -------- | ---- | ---- |
| Como consulto um sancionado? | ✅ `GET /api/{list}/records/search?q=` | `api-reference.md` |
| De quanto em quanto tempo importa? | ✅ padrão 6h, ajustável 1m–1d | seção (b) |
| Guardamos versões anteriores? | ✅ CURRENT/PREVIOUS/N_MINUS_2 (+ COLD retido) | seção (a) |
| Diff entre versões (quem entrou/saiu)? | ❌ não implementado (dados existem) | seção (c) — proposta |
| Contrato OpenAPI / API-first? | ❌ code-first hoje | `api-reference.md` (API-first) |
