#!/usr/bin/env bash
#
# Pre-flight check for the AI provider. Confirms the configured key authenticates, lists the
# models it can reach, and proves a JSON-schema-constrained completion works - the three things
# the fix client depends on.
#
# Assumes an OpenAI-compatible endpoint (OpenAI, Gemini's compat layer, Groq, OpenRouter, Ollama).
# Reads credentials from the environment; never echoes the key. Run:
#
#   set -a; . ./.env; set +a
#   ./demo/check-ai.sh
#
set -uo pipefail

: "${OPENAI_BASE_URL:?set OPENAI_BASE_URL}"
: "${OPENAI_API_KEY:?set OPENAI_API_KEY}"

BASE="${OPENAI_BASE_URL%/}"

failures=0
pass() { printf '  \033[32mOK\033[0m    %s\n' "$1"; }
fail() { printf '  \033[31mFAIL\033[0m  %s\n' "$1"; failures=$((failures + 1)); }

# Credentials go in via stdin config so the key never appears in argv or shell history.
api() {
    local method="$1" path="$2" data="${3-}"
    {
        printf 'header = "Authorization: Bearer %s"\n' "$OPENAI_API_KEY"
        printf 'request = "%s"\n' "$method"
        printf 'header = "Content-Type: application/json"\n'
        [ -n "$data" ] && printf 'data = "%s"\n' "$(printf '%s' "$data" | sed 's/\\/\\\\/g; s/"/\\"/g')"
        printf 'url = "%s"\n' "$BASE/$path"
    } | curl -sS --config - --header 'Accept: application/json' --write-out '\n%{http_code}'
}

status() { printf '%s' "$1" | tail -n1; }
body() { printf '%s' "$1" | sed '$d'; }

printf '\nAI pre-flight: %s\n\n' "$BASE"

# ---------------------------------------------------------------- 1. authentication + catalogue
printf 'Authentication and model catalogue\n'
response=$(api GET "models")
code=$(status "$response")

if [ "$code" != "200" ]; then
    fail "HTTP $code from /models"
    body "$response" | jq -r '.error.message? // .error? // .' 2>/dev/null || body "$response"
    printf '\nCannot continue without a working key.\n\n'
    exit 1
fi

pass "key accepted"
count=$(body "$response" | jq '[.data[]?] | length')
pass "$count models reachable"

printf '\nModels\n'
body "$response" | jq -r '.data[]?.id' | sed 's|^models/||' | sort | sed 's/^/  /'

# ---------------------------------------------------------------- 2. schema-constrained call
# The fix client needs structured output, not just any completion, so exercise that directly.
MODEL="${OPENAI_MODEL:-$(body "$response" | jq -r '[.data[]?.id | sub("^models/";"")]
    | map(select(test("flash") and (test("image|tts|embedding|live|native-audio") | not)))
    | sort | .[0] // empty')}"

if [ -z "$MODEL" ]; then
    fail "could not pick a default model; set OPENAI_MODEL and re-run"
    exit 1
fi

printf '\nStructured output using %s\n' "$MODEL"

request=$(jq -nc --arg model "$MODEL" '{
    model: $model,
    messages: [{role: "user", content: "Reply with probableFix true and summary exactly: ping"}],
    response_format: {
        type: "json_schema",
        json_schema: {
            name: "incident_fix_proposal",
            strict: true,
            schema: {
                type: "object",
                additionalProperties: false,
                properties: {
                    probableFix: {type: "boolean"},
                    summary: {type: "string"}
                },
                required: ["probableFix", "summary"]
            }
        }
    }
}')

response=$(api POST "chat/completions" "$request")
code=$(status "$response")

if [ "$code" != "200" ]; then
    fail "HTTP $code from /chat/completions"
    body "$response" | jq -r '.error.message? // .' 2>/dev/null || body "$response"
else
    content=$(body "$response" | jq -r '.choices[0].message.content // empty')
    if printf '%s' "$content" | jq -e '.probableFix != null and .summary != null' >/dev/null 2>&1; then
        pass "schema-constrained JSON returned: $(printf '%s' "$content" | jq -c .)"
    else
        fail "response was not schema-conforming JSON: $content"
    fi
    usage=$(body "$response" | jq -r '.usage | "\(.prompt_tokens // 0) in / \(.completion_tokens // 0) out"')
    pass "token usage $usage"
fi

printf '\n'
if [ "$failures" -eq 0 ]; then
    printf 'AI provider is usable. Set OPENAI_MODEL=%s in .env.\n\n' "$MODEL"
else
    printf '%d check(s) failed.\n\n' "$failures"
    exit 1
fi
