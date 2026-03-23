#!/usr/bin/env bash
# Populate test Confluence with ~2000 pages across 30 spaces, with labels.
set -uo pipefail

BASE="${CONFLUENCE_BASE:-http://localhost:1990/confluence}"
AUTH="admin:admin"
SPACES=30
PAGES_PER_SPACE=24  # 30 * 24 = 720 pages

LABELS=("release-notes" "draft" "archived" "important" "review-needed"
        "api-docs" "internal" "public" "migration" "deprecated"
        "howto" "faq" "onboarding" "security" "performance"
        "bug" "feature" "design" "meeting-notes" "decision")

# Retry a curl command up to 3 times with backoff
retry_curl() {
  for attempt in 1 2 3; do
    local result
    result=$("$@" 2>/dev/null) && { echo "$result"; return 0; }
    echo "  (retry $attempt...)" >&2
    sleep $((attempt * 5))
  done
  return 1
}

create_space() {
  local key="$1" name="$2"
  local status
  status=$(curl -s -o /dev/null -w "%{http_code}" --max-time 30 -u "$AUTH" \
    -H "Content-Type: application/json" \
    -X POST "$BASE/rest/api/space" \
    -d "{\"key\":\"$key\",\"name\":\"$name\",\"description\":{\"plain\":{\"value\":\"Test space $key\",\"representation\":\"plain\"}}}")
  if [ "$status" = "200" ]; then
    echo "  Created space $key"
  elif [ "$status" = "409" ]; then
    echo "  Space $key already exists, skipping"
  else
    echo "  Space $key returned HTTP $status"
  fi
}

create_page() {
  local space_key="$1" title="$2" body="$3"
  local resp
  resp=$(curl -s -w "\n%{http_code}" --max-time 30 -u "$AUTH" \
    -H "Content-Type: application/json" \
    -X POST "$BASE/rest/api/content" \
    -d "{
      \"type\":\"page\",
      \"title\":\"$title\",
      \"space\":{\"key\":\"$space_key\"},
      \"body\":{\"storage\":{\"value\":\"$body\",\"representation\":\"storage\"}}
    }") || { echo ""; return; }
  local http_code
  http_code=$(echo "$resp" | tail -1)
  local body_resp
  body_resp=$(echo "$resp" | sed '$d')
  if [ "$http_code" = "200" ]; then
    echo "$body_resp" | python3 -c "import sys,json; print(json.load(sys.stdin)['id'])" 2>/dev/null || echo ""
  elif [ "$http_code" = "409" ]; then
    # Page already exists, skip
    echo "SKIP"
  else
    echo ""
  fi
}

add_label() {
  local page_id="$1" label="$2"
  curl -s -o /dev/null --max-time 15 -u "$AUTH" \
    -H "Content-Type: application/json" \
    -X POST "$BASE/rest/api/content/$page_id/label" \
    -d "[{\"prefix\":\"global\",\"name\":\"$label\"}]"
}

# Wait for Confluence to be ready
echo "Checking Confluence is available..."
for i in $(seq 1 10); do
  code=$(curl -s -o /dev/null -w "%{http_code}" --max-time 10 "$BASE/status" 2>/dev/null || echo "000")
  [ "$code" = "200" ] && break
  echo "  Waiting... (HTTP $code)"
  sleep 10
done

echo "=== Creating $SPACES spaces ==="
for i in $(seq 1 $SPACES); do
  key=$(printf "TEST%02d" "$i")
  name=$(printf "Test Space %02d" "$i")
  create_space "$key" "$name"
  sleep 0.3
done

echo ""
echo "=== Creating ~$((SPACES * PAGES_PER_SPACE)) pages with labels ==="
total=0
skipped=0
failed=0
for i in $(seq 1 $SPACES); do
  space_key=$(printf "TEST%02d" "$i")
  echo "Space $space_key ($PAGES_PER_SPACE pages)..."
  for j in $(seq 1 $PAGES_PER_SPACE); do
    total=$((total + 1))
    title="Page ${j} in ${space_key}"
    body="<p>Auto-generated test page #${total}. Space: ${space_key}, page index: ${j}.</p><p>Lorem ipsum dolor sit amet, consectetur adipiscing elit.</p>"

    page_id=$(create_page "$space_key" "$title" "$body")
    if [ "$page_id" = "SKIP" ]; then
      skipped=$((skipped + 1))
    elif [ -n "$page_id" ]; then
      # Assign 1-3 random labels per page
      num_labels=$(( (RANDOM % 3) + 1 ))
      for _ in $(seq 1 $num_labels); do
        label="${LABELS[$((RANDOM % ${#LABELS[@]}))]}"
        add_label "$page_id" "$label"
      done
    else
      failed=$((failed + 1))
      # If we get 3 consecutive failures, wait and retry
      if (( failed % 3 == 0 )); then
        echo "  Multiple failures, pausing 10s..."
        sleep 10
      fi
    fi

    if (( total % 50 == 0 )); then
      echo "  ... $total pages processed (skipped: $skipped, failed: $failed)"
    fi

    # Delay to avoid overwhelming the server
    sleep 1
  done
done

echo ""
echo "=== Done! Processed $total pages across $SPACES spaces (skipped: $skipped, failed: $failed) ==="
