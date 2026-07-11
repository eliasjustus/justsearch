# 713 — Dense-representation authority: is the parent single-pass vector redundant now that chunk vectors live? (post-F-032 missing cell)

- **status:** seed — takeover pending (chartered 2026-07-11 from the 711 close-out; no
  investigation performed yet)
- **created:** 2026-07-11

## Charter question

With chunk vectors alive (F-032), does the whole-doc parent VECTOR for chunked documents still
earn its cost — or can the dense representation consolidate on chunk vectors (+MaxP/chunk_merge)
as the single authority, retiring the long-doc single-pass machinery F-031 shipped?

## Evidence that motivates the charter (verified, citable)

- The measured cells on `mixed/legal-clerc-200` at shipped defaults (711 live verify, corpus
  sha256 630f5376…): chunks-dead + parent-single-pass = vector **0.3401**; chunks-alive +
  parent-single-pass = vector **0.6180**. The cell that decides this tempdoc — chunks-alive
  WITHOUT the parent single-pass (parent on legacy window-mean, or absent) — has **never been
  measured**. F-031's lever was evaluated strictly before anyone knew every chunk vector was
  being destroyed (F-032).
- 691 §Phase M offline: pure chunk-CLS exact-NN MaxP reaches nDCG@10 **0.64 / R@10 0.85** on
  this corpus — chunk granularity alone approaches the current live 0.618.
- Cost side: 711's live run shows **101 of 198** legal parents took the `longDocWindowed`
  deferral path (the 8192-token batch-1 passes + second-RMW complexity). If the parent vector
  is redundant for chunked docs, that machinery and its cost can go.
- The parent-vs-chunk representation fork is the ORIGINAL E-5 finding that chartered 710 —
  consolidating to one authority would resolve it structurally, not just manage it.

## Cheapest evidence

ONE pipeline A/B on legal-clerc (plus scifact/enron short-doc controls if the first arm moves):
current defaults vs parent-single-pass disabled for chunked docs (chunk vectors + chunk_merge
carry the leg; non-chunked docs keep their single whole-doc vector). Compare vector/hybrid
nDCG + enrichment wall. Parity or better → consolidation is justified; a real regression →
F-031's lever earns its keep and this closes with "keep both, document why."

## Constraints / relations

- Honest framing REQUIRED: this potentially reverses part of 691's shipped F-031 lever — that
  is not a criticism of 691; the landscape changed under it (F-032). Do not frame as cleanup.
- Do NOT touch 708's encoder-domain question; this tempdoc holds the encoder fixed.
- GPU/dev-stack is shared — verify free before any run.
- Register: `docs/reference/search-quality-register.md` (read before, update before close).
  Related: F-030/F-031/F-032; 691 §G/§M/§N; 710 S-B dataflow map.
