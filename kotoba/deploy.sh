#!/usr/bin/env bash
# yoro-supply 綾取供給 — kotoba deploy
# ADR-2605250850 · migration plan Phase 3
#
# Ingests schema-shaped seed datoms (suppliers + worked BOM) into a running kotoba
# node and (optionally) builds the langgraph WASM actor (5-handler graph). Writes
# to the canonical Datom journal require an authorized operator session token
# (no-server-key posture, G11). Without KOTOBA_TOKEN the ingest is a dry-run.
# Replaces the legacy SQL / G() builder path (G6).
#
# Usage:
#   KOTOBA_URL=http://127.0.0.1:8077 KOTOBA_TOKEN=<at-session-jwt> ./deploy.sh
set -euo pipefail

KOTOBA_URL="${KOTOBA_URL:-http://127.0.0.1:8077}"
GRAPH="${YOROSUPPLY_GRAPH:-com.etzhayyim.yorosupply}"
ACTOR_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

echo "==> yoro-supply kotoba deploy -> ${KOTOBA_URL} (graph ${GRAPH})"

if ! curl -fsS -m 5 "${KOTOBA_URL}/health" >/dev/null 2>&1; then
  echo "!! kotoba node not reachable at ${KOTOBA_URL} -- start it with: kotoba serve" >&2
  exit 1
fi

# ingest seed datoms (supplier registry + example BOM)
echo "--> supplier registry + BOM ingest (:supplier/* + :bom/* datoms)"
python3 "${ACTOR_DIR}/kotoba/ingest_mcp.cljc" --url "${KOTOBA_URL}" --graph "${GRAPH}" \
  $([[ -z "${KOTOBA_TOKEN:-}" ]] && echo --dry-run)

if [[ -z "${KOTOBA_TOKEN:-}" ]]; then
  echo "--> KOTOBA_TOKEN unset -> DRY RUN (no writes). Set an operator AT-session-JWT to ingest."
else
  echo "--> ingesting seed datoms via MCP (operator token present)"
  KOTOBA_TOKEN="${KOTOBA_TOKEN}" python3 "${ACTOR_DIR}/kotoba/ingest_mcp.cljc" \
    --url "${KOTOBA_URL}" --graph "${GRAPH}" --via mcp
  echo "--> sealing hot arrangement (kotoba commit)"
  kotoba --url "${KOTOBA_URL}" --token "${KOTOBA_TOKEN}" commit
fi

echo "--> langgraph actor build (componentize-py)"
if command -v componentize-py >/dev/null 2>&1; then
  ( cd "${ACTOR_DIR}/py" && componentize-py -w kotoba-actor componentize agent -o agent.wasm )
  echo "    built py/agent.wasm -- deploy via the node's invoke.run with an operator token"
else
  echo "    (componentize-py absent -- skipping wasm build)"
fi

echo "==> done"
