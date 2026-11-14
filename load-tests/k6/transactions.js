// Test de charge (PRD §8) : 200 requêtes/s soutenues, p95 commande < 150 ms, p95 lecture < 80 ms.
//   k6 run -e BASE_URL=http://localhost:8080 load-tests/k6/transactions.js
import http from 'k6/http';
import { check } from 'k6';
import { uuidv4 } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';

const BASE = __ENV.BASE_URL || 'http://localhost:8080';
const USERS = 20;

export const options = {
  scenarios: {
    writes: { executor: 'constant-arrival-rate', rate: 80, timeUnit: '1s', duration: '3m', preAllocatedVUs: 50, exec: 'write' },
    reads: { executor: 'constant-arrival-rate', rate: 120, timeUnit: '1s', duration: '3m', preAllocatedVUs: 50, exec: 'read' },
  },
  thresholds: {
    'http_req_duration{kind:write}': ['p(95)<150'],
    'http_req_duration{kind:read}': ['p(95)<80'],
    http_req_failed: ['rate<0.01'],
  },
};

function json(token, extra = {}) {
  return { headers: Object.assign({ 'Content-Type': 'application/json', Authorization: `Bearer ${token}` }, extra) };
}

export function setup() {
  const sessions = [];
  for (let i = 0; i < USERS; i++) {
    const email = `load-${uuidv4()}@pocketledger.test`;
    const password = 'load-test-password';
    http.post(`${BASE}/api/v1/auth/register`, JSON.stringify({ email, password, referenceCurrency: 'EUR' }), { headers: { 'Content-Type': 'application/json' } });
    const login = http.post(`${BASE}/api/v1/auth/login`, JSON.stringify({ email, password }), { headers: { 'Content-Type': 'application/json' } });
    const token = login.json('accessToken');
    const account = http.post(
      `${BASE}/api/v1/accounts`,
      JSON.stringify({ name: 'Courant', type: 'CHECKING', currency: 'EUR', openingBalance: '100000' }),
      json(token, { 'Idempotency-Key': uuidv4() }),
    );
    sessions.push({ token, accountId: account.json('id') });
  }
  return { sessions };
}

export function write(data) {
  const s = data.sessions[Math.floor(Math.random() * data.sessions.length)];
  const res = http.post(
    `${BASE}/api/v1/transactions`,
    JSON.stringify({ accountId: s.accountId, amount: `-${(Math.random() * 50 + 1).toFixed(2)}`, valueDate: new Date().toISOString().slice(0, 10), label: 'Achat test de charge', categoryId: 'food.groceries' }),
    Object.assign(json(s.token, { 'Idempotency-Key': uuidv4() }), { tags: { kind: 'write' } }),
  );
  check(res, { '201': (r) => r.status === 201 });
}

export function read(data) {
  const s = data.sessions[Math.floor(Math.random() * data.sessions.length)];
  const res = http.get(`${BASE}/api/v1/transactions?limit=50`, Object.assign(json(s.token), { tags: { kind: 'read' } }));
  check(res, { '200': (r) => r.status === 200 });
}
