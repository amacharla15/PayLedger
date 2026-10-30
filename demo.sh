#!/usr/bin/env bash
# Walks through the main flows against a running stack.
#   ./demo.sh                                         Docker Compose on http://localhost:8080/graphql
#   RUNTIME=kind ./demo.sh http://localhost/graphql   kind cluster behind ingress-nginx
set -euo pipefail

URL=${1:-http://localhost:8080/graphql}
RUNTIME=${RUNTIME:-compose}
RUN=$(date +%s)
ALICE=alice-$RUN
BOB=bob-$RUN

gql() {
  local query
  query=$(printf '%s' "$2" | sed 's/"/\\"/g')
  if [ "$1" = - ]; then
    curl -s "$URL" -H 'Content-Type: application/json' -d "{\"query\":\"$query\"}"
  else
    curl -s "$URL" -H 'Content-Type: application/json' -H "Authorization: Bearer $1" -d "{\"query\":\"$query\"}"
  fi
  echo
}

field() {
  sed -n "s/.*\"$1\":\"\([^\"]*\)\".*/\1/p"
}

step() {
  printf '\n== %s\n' "$*"
}

transfer() {
  echo "mutation { transfer(input: {fromAccountId: $1, toAccountId: $2, amountCents: $3, idempotencyKey: \"$4\"}) { id amountCents } }"
}

step "register $ALICE and $BOB, log in as $ALICE"
gql - "mutation { register(username: \"$ALICE\", password: \"password123\") { accessToken } }" > /dev/null
B=$(gql - "mutation { register(username: \"$BOB\", password: \"password123\") { accessToken } }" | field accessToken)
LOGIN=$(gql - "mutation { login(username: \"$ALICE\", password: \"password123\") { accessToken refreshToken } }")
A=$(echo "$LOGIN" | field accessToken)
REFRESH=$(echo "$LOGIN" | field refreshToken)
gql "$A" '{ me }'

step "open one account each"
A_ACC=$(gql "$A" 'mutation { openAccount { id } }' | field id)
B_ACC=$(gql "$B" 'mutation { openAccount { id } }' | field id)
echo "$ALICE has account $A_ACC, $BOB has account $B_ACC"

step "deposit 10000 cents into $ALICE's account"
gql "$A" "mutation { deposit(accountId: $A_ACC, amountCents: 10000) { id balanceCents } }"

step "transfer 2500 cents to $BOB"
gql "$A" "$(transfer "$A_ACC" "$B_ACC" 2500 "demo-$RUN")"

step "same request again with the same idempotency key: same transfer id, balance unchanged"
gql "$A" "$(transfer "$A_ACC" "$B_ACC" 2500 "demo-$RUN")"
gql "$A" "{ account(id: $A_ACC) { id balanceCents } }"

step "overdraft"
gql "$A" "$(transfer "$A_ACC" "$B_ACC" 1000000 "demo-$RUN-overdraft")"

step "transfer out of $BOB's account while logged in as $ALICE"
gql "$A" "$(transfer "$B_ACC" "$A_ACC" 100 "demo-$RUN-not-mine")"

step "transfer 13 cents: the notifier rejects this amount, retries 3 times, then dead-letters it"
gql "$A" "$(transfer "$A_ACC" "$B_ACC" 13 "demo-$RUN-poison")"

step "transfer history, newest first"
gql "$A" "{ transfers(accountId: $A_ACC, first: 5) { items { id amountCents from { ownerId } to { ownerId } } nextCursor } }"

step "refresh token rotation: the first refresh works, reusing the old token fails"
gql - "mutation { refresh(refreshToken: \"$REFRESH\") { refreshToken } }" | sed 's/"refreshToken":"[^"]*"/"refreshToken":"<new token>"/'
gql - "mutation { refresh(refreshToken: \"$REFRESH\") { refreshToken } }"

step "burst of 1 cent deposits until the rate limiter answers"
for i in $(seq 1 25); do
  if gql "$A" "mutation { deposit(accountId: $A_ACC, amountCents: 1) { id } }" | grep -q 'rate limited'; then
    echo "request $i: rate limited"
    break
  fi
done

echo
echo "waiting for the poison transfer to finish its retries..."
sleep 8

case $RUNTIME in
  compose)
    step "transfers.DLT"
    docker compose exec -T kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 \
      --topic transfers.DLT --from-beginning --timeout-ms 5000 2>/dev/null | grep "$ALICE" || true
    step "notifier log"
    docker compose logs notifier | grep -E "notify $BOB|dead-lettering" | sed 's/.* : //' | sort -u | tail -5
    step "transfers handled by each ledger replica"
    for i in 1 2; do
      echo "ledger-service-$i: $(docker compose exec -T --index "$i" ledger-service curl -s localhost:8081/actuator/prometheus \
        | grep '^transfers_total' | tr '\n' ' ')"
    done
    JAEGER=http://localhost:16686
    ;;
  kind)
    step "transfers.DLT"
    kubectl -n payledger exec kafka-0 -- /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 \
      --topic transfers.DLT --from-beginning --timeout-ms 5000 2>/dev/null | grep "$ALICE" || true
    step "notifier log"
    kubectl -n payledger logs deploy/notifier | grep -E "notify $BOB|dead-lettering" | sed 's/.* : //' | sort -u | tail -5
    step "transfers handled by each ledger pod"
    for pod in $(kubectl -n payledger get pods -l app=ledger -o name); do
      echo "$pod: $(kubectl -n payledger exec "$pod" -- curl -s localhost:8081/actuator/prometheus \
        | grep '^transfers_total' | tr '\n' ' ')"
    done
    JAEGER="http://localhost:16686 (after: kubectl -n payledger port-forward svc/jaeger 16686)"
    ;;
esac

step "traces"
echo "$JAEGER/search?service=gateway&operation=POST%20%2Fgraphql"
