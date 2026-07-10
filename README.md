# yoro-supply — Material Sourcing & Logistics Tier-B Actor

**DID**: `did:web:etzhayyim.com:yoro-supply`
**Namespace**: `com.etzhayyim.supply.*`
**ADR**: ADR-2605250850 (R0 scaffold)
**Status**: R0 scaffold (2026-05-26)

## Overview

Phase 0–4 parallel actor: Material sourcing, order placement, manufacture tracking, shipment, delivery verification per BOM.

**Input**: `projectBOM` (material list with specifications, phases, quantities)  
**Output**: `deliveryVerifiedRecord` + `materialAttestation` (per batch, to tatekata)

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

## 14 Constitutional Gates (G1–G14)

- **G1**: All supplier firmware open-source (QC testing equipment)
- **G2**: Manufacturing progress photos IPFS-pinned
- **G3**: Supplier + delivery witness signature (≥2)
- **G5**: Charter Rider §2(g) — conflict minerals audit + rare-earth-free verification
- **G6**: Deterministic supply scheduling (replayable via Gantt)
- Others: transparency, KPI tracking, waste management

## Non-Goals

- N1: Supplier relationship management (CRM domain)
- N2: International trade law (customs/tariffs)
- N3: Insurance underwriting
- N4: Financing (capital domain)

## 4-Phase Roadmap

- **R0**: Scaffold, mock RFQ responses
- **R1**: Japan suppliers (concrete, steel, drywall) + US (lumber, paint)
- **R2**: 20+ suppliers, real API integrations
- **R3**: Full supply chain traceability (blockchain anchor, Maersk API)
