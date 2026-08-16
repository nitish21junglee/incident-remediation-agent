#!/usr/bin/env bash
set -euo pipefail

# Posts a PagerDuty v3 incident.triggered envelope at the local agent.
# Usage: ./demo/trigger-incident.sh ["incident title"] [incident-id]

TITLE="${1:-demo-api error rate increased}"
INCIDENT_ID="${2:-PINCIDENT-$(date +%s)}"
OCCURRED_AT="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
SERVICE_ID="${PAGERDUTY_SERVICE_ID:-PDEMO}"
URL="${AGENT_URL:-http://localhost:8080}/webhooks/pagerduty"

curl --fail-with-body \
  --request POST \
  --header 'Content-Type: application/json' \
  --data @- \
  "$URL" <<JSON
{
  "event": {
    "id": "01JDEMOEVENT-$(date +%s)",
    "event_type": "incident.triggered",
    "occurred_at": "$OCCURRED_AT",
    "data": {
      "id": "$INCIDENT_ID",
      "title": "$TITLE",
      "html_url": "https://example.pagerduty.com/incidents/$INCIDENT_ID",
      "service": {
        "id": "$SERVICE_ID",
        "summary": "demo-api"
      }
    }
  }
}
JSON
echo
