# OFAC — Discovery Técnico da Lista de Sanções

## Objetivo

Investigar as características técnicas, operacionais e de dados da lista de sanções da OFAC para compreender a complexidade real do processo de ingestão.

Esta etapa deve ser **agnóstica de tecnologia**. Não deve assumir previamente framework, mecanismo de processamento ou arquitetura.

Ao final, as evidências levantadas devem permitir identificar e comparar **duas alternativas técnicas adequadas ao processamento encontrado**.

---

## 1. Características da fonte

Identificar utilizando exclusivamente documentação e endpoints oficiais da OFAC:

* Quais listas precisam ser consumidas.
* Qual endpoint oficial disponibiliza cada lista.
* Quais formatos estão disponíveis.
* Se o conteúdo disponibilizado representa snapshot completo ou atualização incremental.
* Frequência com que os dados podem ser atualizados.
* Como identificar uma nova publicação.
* Existência de versão, timestamp, hash ou checksum oficial.
* Requisitos para consumo automatizado:

  * autenticação;
  * headers;
  * rate limits;
  * restrições de acesso;
  * disponibilidade.

Registrar as fontes oficiais utilizadas na investigação.

---

## 2. Características dos arquivos

Baixar arquivos reais e levantar:

| Característica                     | Resultado |
| ---------------------------------- | --------- |
| Formato                            |           |
| Tamanho                            |           |
| Quantidade de registros principais |           |
| Estrutura                          |           |
| Encoding                           |           |
| Compressão                         |           |
| Hash/checksum disponível           |           |
| Frequência de atualização          |           |
| Snapshot completo/incremental      |           |

Caso existam diferentes formatos, comparar as informações disponíveis em cada um e identificar possível perda de dados.

---

## 3. Estrutura dos registros

Mapear a estrutura real dos dados.

Identificar:

* identificador fornecido pela origem;
* tipo de entidade;
* nome principal;
* aliases;
* endereços;
* documentos;
* nacionalidades;
* cidadanias;
* datas e locais de nascimento;
* programas de sanção;
* observações;
* relacionamentos;
* estruturas aninhadas;
* campos opcionais;
* multiplicidades.

Responder:

> Existe um identificador estável que permita reconhecer a mesma entidade entre diferentes publicações?

E:

> Qual a complexidade necessária para transformar um registro da OFAC em uma representação interna?

---

## 4. Qualidade e consistência dos dados

Investigar arquivos reais procurando:

* registros sem campos esperados;
* duplicidades;
* múltiplos aliases;
* múltiplos documentos;
* múltiplos endereços;
* caracteres especiais;
* datas incompletas;
* campos com formatos diferentes;
* estruturas opcionais;
* registros excepcionalmente grandes;
* inconsistências entre registros.

Documentar exemplos encontrados.

Identificar quais situações representam:

```text
dado válido
dado incompleto
dado inconsistente
arquivo inválido
```

---

## 5. Volume real

Mensurar não apenas entidades principais, mas o volume completo que precisaria ser processado.

Levantar:

```text
Entries
Aliases
Addresses
Documents
Programs
Relationships
Outros elementos relevantes
```

Apresentar:

```text
Total de entidades
Total de elementos relacionados
Total aproximado de dados processados
```

Estimar também o crescimento esperado considerando novas versões da lista.

---

## 6. Frequência e comportamento das atualizações

Investigar:

* Com que frequência a OFAC publica alterações.
* Se podem ocorrer várias atualizações no mesmo dia.
* Se existe horário definido.
* Como identificar que houve alteração.
* Se o arquivo completo é republicado.
* Se existem arquivos incrementais.
* Se alterações podem incluir:

  * inclusão;
  * remoção;
  * modificação.

Responder:

> Precisamos processar novamente todo o dataset a cada atualização ou a fonte permite processamento incremental confiável?

---

## 7. Fluxo mínimo necessário

Com base exclusivamente nos requisitos encontrados, descrever o processamento mínimo.

Exemplo conceitual:

```text
Fonte
  ↓
Obtenção
  ↓
Validação
  ↓
Leitura
  ↓
Transformação
  ↓
Persistência
  ↓
Validação do resultado
  ↓
Disponibilização
```

Não associar essas etapas a frameworks ou produtos específicos.

---

## 8. Comportamento em caso de falha

Identificar os possíveis pontos de falha:

```text
obtenção
validação
leitura
transformação
persistência
disponibilização
```

Para cada um responder:

* O processamento pode ser repetido?
* Existem efeitos colaterais?
* Há risco de duplicação?
* Quanto trabalho seria perdido?
* A versão anteriormente válida pode continuar sendo utilizada?
* Existe algum requisito que impeça começar novamente?

Responder principalmente:

> Se o processo falhar próximo ao final, qual é o custo real de descartar o processamento e executá-lo novamente?

---

## 9. Medição do processamento

Criar uma implementação mínima apenas para mensuração.

Executar com arquivo real e coletar:

```text
tempo de obtenção
tempo de leitura
tempo de transformação
tempo de persistência
tempo total

CPU
memória

registros/segundo
```

Executar mais de uma vez para evitar conclusões baseadas em uma única medição.

Registrar ambiente e condições do teste.

---

## 10. Persistência e versionamento

Investigar os requisitos de dados necessários para permitir:

```text
VERSÃO ATUAL
VERSÃO ANTERIOR
VERSÃO N-2
```

Avaliar:

* identificação da versão;
* associação dos registros à versão;
* imutabilidade de uma versão publicada;
* ativação de uma nova versão;
* rollback;
* quantidade de versões operacionais necessárias;
* custo de manter múltiplas versões.

Não definir ainda tecnologia específica de armazenamento.

---

## 11. Histórico

Levantar os requisitos para versões que deixam de ser operacionais.

Responder:

* Por quanto tempo precisam ser preservadas?
* Precisamos consultar dados históricos?
* Com qual frequência?
* Precisamos reconstruir exatamente uma lista utilizada no passado?
* Precisamos preservar o arquivo original?
* Precisamos preservar também o modelo processado?
* Qual volume histórico será produzido?

Classificar o histórico como:

```text
HOT
versões utilizadas operacionalmente

COLD
versões preservadas para histórico/auditoria
```

---

## 12. Expansão para outras fontes

Embora a análise inicial seja da OFAC, identificar características que provavelmente serão diferentes em outras fontes, como ONU, União Europeia ou outras listas.

Separar:

### Características específicas da OFAC

```text
formato
estrutura
campos
endpoint
identificadores
```

### Características potencialmente comuns

```text
obtenção
validação
transformação
versionamento
persistência
histórico
publicação
```

O objetivo é identificar se existe potencial para uma capacidade reutilizável de ingestão sem antecipar sua arquitetura.

---

# Classificação da complexidade

Após responder às questões anteriores, classificar o processamento encontrado.

## Volume

```text
BAIXO
MÉDIO
ALTO
```

Justificar com números.

## Duração

```text
CURTA
MÉDIA
LONGA
```

Justificar com benchmark.

## Transformação

```text
SIMPLES
MODERADA
COMPLEXA
```

## Recuperação

```text
REPROCESSAMENTO TOTAL ACEITÁVEL

ou

PROGRESSO PRECISA SER PRESERVADO
```

## Frequência

```text
BAIXA
MÉDIA
ALTA
```

## Número esperado de fontes

```text
ATUAL
+
PROJEÇÃO
```

---

# Avaliação de alternativas

**Somente após concluir todo o discovery anterior**, identificar alternativas de implementação.

Não partir de uma lista fixa de tecnologias.

A partir das características encontradas, pesquisar abordagens adequadas para:

```text
volume encontrado
+
frequência encontrada
+
tempo de processamento
+
complexidade de transformação
+
requisitos de recuperação
+
necessidade de versionamento
+
crescimento para novas fontes
```

Selecionar as **duas alternativas que apresentem maior aderência ao problema encontrado**.

Para cada alternativa apresentar:

### Alternativa A

**Descrição**

Como funcionaria o processamento.

**Por que se encaixa**

Relacionar diretamente com as evidências obtidas durante o discovery.

**Benefícios**

Principais vantagens.

**Trade-offs**

Complexidade introduzida, infraestrutura, armazenamento, operação e manutenção.

**Comportamento em falha**

Como retry, recuperação e reprocessamento seriam realizados.

**Escalabilidade**

Como se comportaria com novas listas e crescimento de volume.

---

### Alternativa B

Utilizar a mesma estrutura da Alternativa A.

---

# Comparação

Comparar as duas alternativas utilizando pelo menos:

| Critério                      | Alternativa A | Alternativa B |
| ----------------------------- | ------------- | ------------- |
| Aderência ao volume           |               |               |
| Complexidade de implementação |               |               |
| Complexidade operacional      |               |               |
| Recuperação de falhas         |               |               |
| Reprocessamento               |               |               |
| Performance                   |               |               |
| Consumo de recursos           |               |               |
| Persistência de estado        |               |               |
| Observabilidade               |               |               |
| Escalabilidade                |               |               |
| Inclusão de novas fontes      |               |               |
| Custo de infraestrutura       |               |               |
| Custo de manutenção           |               |               |

As conclusões devem estar vinculadas às evidências coletadas anteriormente.

---

# Recomendação

Após comparar as duas alternativas, recomendar uma delas.

A recomendação deve responder:

> Qual é a solução mais simples que atende aos requisitos atuais sem limitar desnecessariamente a evolução para novas listas?

Apresentar também:

### Por que escolher

Evidências que sustentam a decisão.

### Por que não escolher a alternativa

Trade-offs que não se justificam para o cenário atual.

### Limites da decisão

Em quais condições a alternativa escolhida deixaria de ser adequada.

Exemplos:

```text
aumento significativo do volume

aumento da frequência

processamentos muito longos

necessidade de recuperação parcial

novos requisitos de consistência

crescimento expressivo do número de fontes
```

---

# Resultado esperado da Spike

A spike deve terminar com:

1. Caracterização real da OFAC.
2. Volume real dos dados.
3. Complexidade da estrutura.
4. Frequência e comportamento das atualizações.
5. Benchmark do processamento.
6. Requisitos de falha e recuperação.
7. Requisitos de versionamento.
8. Requisitos de retenção e histórico.
9. Impacto esperado da inclusão de novas fontes.
10. Classificação da complexidade do problema.
11. Duas alternativas técnicas aderentes às evidências encontradas.
12. Comparação objetiva entre as alternativas.
13. Recomendação técnica.
14. Condições que justificariam revisar essa decisão no futuro.

**Importante:** a tecnologia deve ser consequência das evidências encontradas durante a spike, e não uma premissa da investigação.
