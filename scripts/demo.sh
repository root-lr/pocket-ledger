#!/usr/bin/env bash
# Scénario de démo de bout en bout : inscription → comptes XOF et EUR → dépenses → budget → alerte → analytics.
#   ./scripts/demo.sh [http://localhost:8080]
# Prérequis : la stack tourne (docker compose --profile full) ; curl et jq installés.
set -euo pipefail

BASE="${1:-http://localhost:8080}"
EMAIL="demo-$(date +%s)@pocketledger.dev"
PASSWORD="demo-password-123"
TODAY="$(date -u +%F)"
uuid() { cat /proc/sys/kernel/random/uuid 2>/dev/null || uuidgen; }
step() { printf '\n\033[1m▶ %s\033[0m\n' "$1"; }

post() { # chemin, corps JSON
  curl -fsS -X POST "$BASE$1" -H "Content-Type: application/json" -H "Authorization: Bearer $TOKEN" \
    -H "Idempotency-Key: $(uuid)" -d "$2"
}
get() { curl -fsS "$BASE$1" -H "Authorization: Bearer $TOKEN"; }

step "Inscription et connexion ($EMAIL), devise de référence EUR"
curl -fsS -X POST "$BASE/api/v1/auth/register" -H "Content-Type: application/json" \
  -d "{\"email\":\"$EMAIL\",\"password\":\"$PASSWORD\",\"referenceCurrency\":\"EUR\"}" | jq .
TOKEN="$(curl -fsS -X POST "$BASE/api/v1/auth/login" -H "Content-Type: application/json" \
  -d "{\"email\":\"$EMAIL\",\"password\":\"$PASSWORD\",\"deviceName\":\"démo\"}" | jq -r .accessToken)"

step "Ouverture d'un compte en XOF et d'un compte en EUR"
XOF="$(post /api/v1/accounts '{"name":"Compte Cotonou","type":"CHECKING","currency":"XOF","openingBalance":"750000"}' | jq -r .id)"
EUR="$(post /api/v1/accounts '{"name":"Compte Paris","type":"CHECKING","currency":"EUR","openingBalance":"1200"}' | jq -r .id)"
echo "XOF=$XOF EUR=$EUR"

step "Budget Alimentation de 150 € (devise de référence)"
post /api/v1/budgets '{"categoryId":"food","limit":"150"}' | jq .

step "Dépenses : 45 000 XOF au marché, 68,40 € de courses"
post /api/v1/transactions "{\"accountId\":\"$XOF\",\"amount\":\"-45000\",\"valueDate\":\"$TODAY\",\"label\":\"Marché Dantokpa\",\"merchant\":\"Marché Dantokpa\",\"categoryId\":\"food.groceries\"}" | jq .
post /api/v1/transactions "{\"accountId\":\"$EUR\",\"amount\":\"-68.40\",\"valueDate\":\"$TODAY\",\"label\":\"Supermarché\",\"categoryId\":\"food.groceries\"}" | jq .

step "Virement de 100 € vers le compte XOF (65 500 XOF crédités)"
post /api/v1/transfers "{\"fromAccountId\":\"$EUR\",\"toAccountId\":\"$XOF\",\"amount\":\"100\",\"creditAmount\":\"65500\",\"valueDate\":\"$TODAY\"}" | jq .

step "Attente de la propagation (Debezium → Kafka → projections)"
sleep 4

step "Soldes"
get /api/v1/accounts | jq '[.[] | {name, balance: .balance.amount, currency}]'

step "Budgets du mois (68,60 € + 68,40 € = 137,00 € sur 150 €)"
get /api/v1/budgets/status | jq .

step "Alertes (seuil de 80 % franchi)"
get /api/v1/alerts | jq '.items[] | {type, details}'

step "Dépenses par catégorie, consolidées en EUR"
get /api/v1/analytics/spending | jq .

step "Idempotence : le même envoi rejoué ne crée rien"
KEY="$(uuid)"
for i in 1 2; do
  curl -sS -o /dev/null -D - -X POST "$BASE/api/v1/transactions" -H "Content-Type: application/json" \
    -H "Authorization: Bearer $TOKEN" -H "Idempotency-Key: $KEY" \
    -d "{\"accountId\":\"$EUR\",\"amount\":\"-3.20\",\"valueDate\":\"$TODAY\",\"label\":\"Café\"}" | grep -Ei "^(HTTP|idempotent-replayed)"
done
