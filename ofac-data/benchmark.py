#!/usr/bin/env python3
"""Benchmark minimo de ingestao do SDN_ADVANCED.XML.

Mede, por execucao, as etapas:
  - leitura (parse streaming do XML)
  - transformacao (resolver nome/tipo, filtrar Individual+Entity, normalizar)
  - persistencia (gravar o modelo em JSONL)
E coleta: tempo por etapa, tempo total, CPU (user+sys), memoria de pico, registros/s.

Usa apenas a stdlib (xml.etree, time, resource, json) — sem dependencias externas.
Roda N vezes (default 3). O tempo de OBTENCAO (download) e medido a parte, pois
o arquivo ja esta em disco; a referencia de download esta no proprio documento.
"""
import xml.etree.ElementTree as ET
import time, resource, json, sys, gc, platform, os

SRC = "sdn_advanced.xml"
OUT = "modelo_saida.jsonl"
RUNS = int(sys.argv[1]) if len(sys.argv) > 1 else 3

# ReferenceValueSets observados no SDN_ADVANCED (PartySubTypeID -> tipo)
PARTY_SUBTYPE = {"1": "Vessel", "2": "Aircraft", "3": "Entity", "4": "Individual"}
IN_SCOPE = {"Entity", "Individual"}

def localname(tag):
    return tag.split('}', 1)[1] if '}' in tag else tag

def peak_mem_mb():
    # ru_maxrss: bytes no macOS, KB no Linux
    rss = resource.getrusage(resource.RUSAGE_SELF).ru_maxrss
    if sys.platform == "darwin":
        return rss / (1024 * 1024)
    return rss / 1024

def cpu_times():
    r = resource.getrusage(resource.RUSAGE_SELF)
    return r.ru_utime, r.ru_stime

def run_once(run_idx):
    gc.collect()
    t0 = time.perf_counter()
    cu0, cs0 = cpu_times()

    # ---------- LEITURA (parse streaming) ----------
    t_read0 = time.perf_counter()
    parties = []  # cada item: (fixed_ref, subtype_id, [alias_values])
    # capturamos DistinctParty -> Profile (PartySubTypeID) e Aliases/Names dentro
    context = ET.iterparse(SRC, events=("end",))
    for _, elem in context:
        if localname(elem.tag) == "DistinctParty":
            fixed_ref = elem.get("FixedRef")
            subtype = None
            aliases = []
            for node in elem.iter():
                ln = localname(node.tag)
                if ln == "Profile" and node.get("PartySubTypeID"):
                    subtype = node.get("PartySubTypeID")
                elif ln == "DocumentedName":
                    # nome montado a partir dos NamePartValue dentro do alias
                    parts = [n.text.strip() for n in node.iter()
                             if localname(n.tag) == "NamePartValue" and n.text]
                    if parts:
                        aliases.append(" ".join(parts))
            parties.append((fixed_ref, subtype, aliases))
            elem.clear()
    t_read = time.perf_counter() - t_read0

    # ---------- TRANSFORMACAO ----------
    t_tx0 = time.perf_counter()
    model = []
    for fixed_ref, subtype, aliases in parties:
        tipo = PARTY_SUBTYPE.get(subtype, "Unknown")
        if tipo not in IN_SCOPE:
            continue  # filtro pessoas + entidades
        nome = aliases[0] if aliases else None
        outros = aliases[1:] if len(aliases) > 1 else []
        model.append({
            "uid": fixed_ref,
            "tipo": tipo,
            "nome": nome,
            "aliases": outros,
        })
    t_tx = time.perf_counter() - t_tx0

    # ---------- PERSISTENCIA ----------
    t_ps0 = time.perf_counter()
    with open(OUT, "w", encoding="utf-8") as f:
        for rec in model:
            f.write(json.dumps(rec, ensure_ascii=False))
            f.write("\n")
    t_ps = time.perf_counter() - t_ps0

    total = time.perf_counter() - t0
    cu1, cs1 = cpu_times()
    cpu = (cu1 - cu0) + (cs1 - cs0)
    mem = peak_mem_mb()
    n = len(parties)
    n_scope = len(model)
    rps = n / total if total else 0

    print(f"Run {run_idx}: total={total*1000:.0f}ms | leitura={t_read*1000:.0f}ms "
          f"| transform={t_tx*1000:.0f}ms | persist={t_ps*1000:.0f}ms "
          f"| CPU={cpu:.2f}s | memPico={mem:.0f}MB "
          f"| parseados={n} | escopo={n_scope} | {rps:.0f} reg/s")
    return {
        "total_ms": round(total*1000), "leitura_ms": round(t_read*1000),
        "transform_ms": round(t_tx*1000), "persist_ms": round(t_ps*1000),
        "cpu_s": round(cpu, 2), "mem_pico_mb": round(mem),
        "parseados": n, "escopo": n_scope, "reg_por_s": round(rps),
    }

def main():
    print(f"Ambiente: {platform.platform()} | Python {platform.python_version()} "
          f"| arquivo {SRC} ({os.path.getsize(SRC)/1024/1024:.1f} MB)")
    print(f"Executando {RUNS} rodadas...\n")
    results = [run_once(i+1) for i in range(RUNS)]
    keys = ["total_ms","leitura_ms","transform_ms","persist_ms","cpu_s","mem_pico_mb","reg_por_s"]
    print("\n=== MEDIAS ===")
    for k in keys:
        vals = [r[k] for r in results]
        print(f"{k:14s}: media={sum(vals)/len(vals):.1f}  min={min(vals)}  max={max(vals)}")

if __name__ == "__main__":
    main()
