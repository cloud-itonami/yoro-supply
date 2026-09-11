# yoro-supply — Material Sourcing & Logistics Tier-B Actor

**DID**: `did:web:etzhayyim.com:yoro-supply`
**Namespace**: `com.etzhayyim.supply.*`
**ADR**: ADR-2605250850 (R0 scaffold); repo-local decisions under `docs/adr/`
**Status**: R0 — computes and returns plans; dispatches nothing. Sources are
`.kotoba` since 2026-09-10 and are executed on nbb; they are not yet claimed to
compile as Kotoba (see `docs/operator-quickstart.md`, *Known gaps*).

## Running it

```
nbb run_tests.kotoba        # from the repository root
```

33 tests / 52 assertions at the time of writing; exit 0 green, 1 red,
**2 = refused (zero tests collected — never a pass)**. The step-by-step walk,
with the outputs actually observed, is `docs/operator-quickstart.md`. It also
shows how to break the suite on purpose so you know it still measures.

## Overview

Phase 0–4 parallel actor: Material sourcing, order placement, manufacture tracking, shipment, delivery verification per BOM.

**Input**: `projectBOM` (material list with specifications, phases, quantities)  
**Output**: `deliveryVerifiedRecord` + `materialAttestation` (per batch, to tatekata)

## Five faces that must agree

| face | file(s) | what it declares |
|---|---|---|
| manifest | `manifest.edn` (canonical), `manifest.jsonld` (gen-1) | gates, cells, lexicons, DID |
| cells | `cells/*.edn` | state graph, handler name, LLM endpoint, gates per cell |
| lexicons | `lex/*.edn` | the six `com.etzhayyim.supply.*` record shapes |
| deployment | `deploy/app-aozora.edn` | capability allowlist, approval requirement |
| agent | `py/agent.kotoba` | the handlers the cells name, plus settlement |

`src/etzhayyim/yoro_supply/contract.kotoba` states the agreement rules as pure
functions; `test/` shows them failing on fixtures, then passing on the
committed files, then loads the agent and pins its decision core.

## 5 Pregel Cells (Material supply chain)

### supplier_selection
- **Input**: `projectBOM` (material list)
- **Output**: `selectedSuppliers` (RFQ responses, Charter Rider §2(g) checked)
- **Node**: Murakumo zebulun

### order_placement
- **Input**: `selectedSuppliers`, `deliverySchedule`
- **Output**: `purchaseOrderConfirmation` + `manufacturingSchedule`
- **Node**: Murakumo zebulun

### manufacture_track
- **Input**: `purchaseOrderConfirmation`
- **Output**: `manufacturingProgressRecord` (factory telemetry via IPFS)
- **Node**: Murakumo zebulun

### shipment
- **Input**: `manufacturingProgressRecord` (goods ready → factory gate)
- **Output**: `shipmentTrackingRecord` (carrier, ETA, customs docs)
- **Node**: Murakumo zebulun

### delivery_verify
- **Input**: `shipmentTrackingRecord` (goods arrive at site)
- **Output**: `materialAttestation` (weights, certifications, QC results) → Feeds into tatekata

## Constitutional gates

The gate vocabulary is `manifest.edn`'s `:actor/gates`; this table is a copy
of it, not a second source (an earlier README listed a different set of
names — G1 "supplier firmware open-source" and so on — that no cell, test, or
line of the agent ever referred to). Each cell lists which of these it
requires.

| id | name | rule | pinned by the suite |
|---|---|---|---|
| G1 | consent-bound | member DID-signed consent before dispatch | `create-purchase-order` refuses a DID without an active Adherent SBT |
| G2 | charter-rider-screening | Charter Rider §2(g) screening | one failing supplier fails `charter_passed` |
| G5 | murakumo-only | KotobaLLM 127.0.0.1:4000 only | every `:cell/llm` endpoint stays on localhost |
| G6 | kotoba-eavt-native | orders via kotoba Datom | — |
| G7 | tithe-non-fiat | USDC Base L2 + TitheRouter 10 % | tithe is exactly 10 % of gross; parts sum to gross |
| G8 | pii-encrypted-envelope | DID-bound encryption | — |
| G9 | sourcing-legality | public/API-ToS sourcing | — |
| G10 | witness-quorum | 2+ independent witnesses | — |
| G11 | no-server-key | member self-custody | settlement without a member signature stops at `intent` |
| G12 | ipfs-pinned-manifests | manufacturing via IPFS | — |
| G13 | supply-transparency | transparent end-to-end | — |

"—" means the gate is declared and not yet exercised by any test here.

## Non-Goals

- N1: Supplier relationship management (CRM domain)
- N2: International trade law (customs/tariffs)
- N3: Insurance underwriting
- N4: Financing (capital domain)

## 4-Phase Roadmap

- **R0**: Scaffold, mock RFQ responses ← here
- **R1**: Japan suppliers (concrete, steel, drywall) + US (lumber, paint)
- **R2**: 20+ suppliers, real API integrations
- **R3**: Full supply chain traceability (blockchain anchor, Maersk API)

## What does not run

- `kotoba/deploy.sh` — stops at the node health check, and past it names
  `kotoba/ingest_mcp.cljc`, which is not in this repository.
- `py/test_agent.kotoba` — bb-hosted; bb is a retired script host. Its
  decision-core cases are covered by `test/`; its handler-shape cases are not.
