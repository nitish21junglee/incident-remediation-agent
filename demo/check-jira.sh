#!/usr/bin/env bash
#
# Pre-flight check for the Jira integration. Confirms the configured account can do the three
# things RestJiraClient needs: create an issue, comment on it, and edit that comment.
#
# Reads credentials from the environment; never echoes the token. Run:
#
#   export JIRA_BASE_URL=https://your-site.atlassian.net
#   export JIRA_EMAIL=you@example.com
#   read -rs "?Jira API token: " JIRA_API_TOKEN && export JIRA_API_TOKEN
#   export JIRA_PROJECT_KEY=HACK JIRA_ISSUE_TYPE=Task
#   ./demo/check-jira.sh
#
set -uo pipefail

: "${JIRA_BASE_URL:?set JIRA_BASE_URL}"
: "${JIRA_EMAIL:?set JIRA_EMAIL}"
: "${JIRA_API_TOKEN:?set JIRA_API_TOKEN}"
: "${JIRA_PROJECT_KEY:?set JIRA_PROJECT_KEY}"
ISSUE_TYPE="${JIRA_ISSUE_TYPE:-Task}"

# A trailing slash would produce '//rest/...' once RestClient joins paths.
BASE="${JIRA_BASE_URL%/}"

failures=0
pass() { printf '  \033[32mOK\033[0m    %s\n' "$1"; }
fail() { printf '  \033[31mFAIL\033[0m  %s\n' "$1"; failures=$((failures + 1)); }
warn() { printf '  \033[33mWARN\033[0m  %s\n' "$1"; }

# Passes credentials via stdin config so the token never appears in argv.
api() {
    printf 'user = "%s:%s"\n' "$JIRA_EMAIL" "$JIRA_API_TOKEN" \
        | curl -sS --config - --header 'Accept: application/json' \
            --write-out '\n%{http_code}' "$BASE/$1"
}

status() { printf '%s' "$1" | tail -n1; }
body() { printf '%s' "$1" | sed '$d'; }

printf '\nJira pre-flight: %s project %s issue type %s\n\n' "$BASE" "$JIRA_PROJECT_KEY" "$ISSUE_TYPE"

# ---------------------------------------------------------------- 1. authentication
printf 'Authentication\n'
response=$(api "rest/api/3/myself")
if [ "$(status "$response")" = "200" ]; then
    pass "authenticated as $(body "$response" | jq -r '.displayName + " <" + .emailAddress + ">"')"
    pass "accountId $(body "$response" | jq -r '.accountId')"
else
    fail "HTTP $(status "$response") from /myself - token or email rejected"
    printf '\n%s\n' "$(body "$response" | jq -r '.errorMessages[]? // .message? // empty')"
    printf '\nCannot continue without authentication.\n'
    exit 1
fi

# ---------------------------------------------------------------- 2. permissions
printf '\nProject permissions\n'
wanted="BROWSE_PROJECTS,CREATE_ISSUES,ADD_COMMENTS,EDIT_OWN_COMMENTS"
response=$(api "rest/api/3/mypermissions?projectKey=$JIRA_PROJECT_KEY&permissions=$wanted")
if [ "$(status "$response")" = "200" ]; then
    for permission in ${wanted//,/ }; do
        granted=$(body "$response" | jq -r --arg p "$permission" '.permissions[$p].havePermission')
        if [ "$granted" = "true" ]; then
            pass "$permission"
        else
            fail "$permission not granted"
        fi
    done
else
    fail "HTTP $(status "$response") from /mypermissions"
    body "$response" | jq -r '.errorMessages[]? // empty'
fi

# ---------------------------------------------------------------- 3. create screen
printf '\nCreate screen for %s / %s\n' "$JIRA_PROJECT_KEY" "$ISSUE_TYPE"
response=$(api "rest/api/3/issue/createmeta/$JIRA_PROJECT_KEY/issuetypes")
if [ "$(status "$response")" != "200" ]; then
    fail "HTTP $(status "$response") listing issue types - project key may not exist or is not visible"
    body "$response" | jq -r '.errorMessages[]? // empty'
else
    types=$(body "$response" | jq -r '.issueTypes[].name' | paste -sd, -)
    type_id=$(body "$response" | jq -r --arg n "$ISSUE_TYPE" '.issueTypes[] | select(.name == $n) | .id' | head -1)

    if [ -z "$type_id" ]; then
        fail "issue type '$ISSUE_TYPE' not available; project offers: $types"
    else
        pass "issue type '$ISSUE_TYPE' exists (id $type_id)"

        fields=$(api "rest/api/3/issue/createmeta/$JIRA_PROJECT_KEY/issuetypes/$type_id")
        if [ "$(status "$fields")" != "200" ]; then
            fail "HTTP $(status "$fields")) fetching create fields"
        else
            # The plan's create body sends only these five fields.
            sent='["project","issuetype","summary","description","labels"]'
            missing=$(body "$fields" | jq -r --argjson sent "$sent" '
                [.fields[] | select(.required == true) | .fieldId]
                | map(select(. as $f | $sent | index($f) | not))
                | .[]')

            if [ -z "$missing" ]; then
                pass "no required fields beyond those the agent already sends"
            else
                while IFS= read -r field; do
                    name=$(body "$fields" | jq -r --arg f "$field" '.fields[] | select(.fieldId == $f) | .name')
                    fail "required field not sent by the agent: $name ($field)"
                done <<< "$missing"
            fi

            if body "$fields" | jq -e '.fields[] | select(.fieldId == "labels")' >/dev/null; then
                pass "labels field is on the create screen"
            else
                warn "labels not on the create screen - drop agent labels or add the field"
            fi

            if body "$fields" | jq -e '.fields[] | select(.fieldId == "description")' >/dev/null; then
                pass "description field is on the create screen (ADF target)"
            else
                fail "description not on the create screen - the ADF body has nowhere to go"
            fi
        fi
    fi
fi

printf '\n'
if [ "$failures" -eq 0 ]; then
    printf 'All checks passed. The agent can create and update tickets in %s.\n\n' "$JIRA_PROJECT_KEY"
else
    printf '%d check(s) failed. Resolve these before running the workflow against live Jira.\n\n' "$failures"
    exit 1
fi
