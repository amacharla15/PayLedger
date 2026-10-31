// k6 run -e URL=http://localhost:8080/graphql -e VUS=50 -e USERS=2000 loadtest/transfers.js
//
// Each user may make 20 mutations a minute, so the test spreads transfers round-robin over a pool of
// users. USERS=2000 lets the limiter pass about 670 transfers/s; beyond that, "rate limited" responses
// are expected and counted separately from real errors.
import http from 'k6/http';
import exec from 'k6/execution';
import { Counter, Rate, Trend } from 'k6/metrics';

const URL = __ENV.URL || 'http://localhost:8080/graphql';
const VUS = Number(__ENV.VUS || 50);
const USERS = Number(__ENV.USERS || 2000);

const transferDuration = new Trend('transfer_duration', true);
const transferErrors = new Rate('transfer_errors');
const transfersOk = new Counter('transfers_ok');
const transfersRateLimited = new Counter('transfers_rate_limited');

export const options = {
  setupTimeout: '10m',
  summaryTrendStats: ['avg', 'p(50)', 'p(95)', 'p(99)', 'max'],
  scenarios: {
    transfers: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '30s', target: VUS },
        { duration: '2m', target: VUS },
        { duration: '30s', target: 0 },
      ],
    },
  },
};

function body(query) {
  return JSON.stringify({ query });
}

function headers(token) {
  const h = { 'Content-Type': 'application/json' };
  if (token) {
    h.Authorization = `Bearer ${token}`;
  }
  return { headers: h, tags: { name: 'setup' } };
}

// Sends one mutation per item, 50 at a time, and returns the parsed "data" of each response.
function batch(items, query, token) {
  const out = [];
  for (let i = 0; i < items.length; i += 50) {
    const chunk = items.slice(i, i + 50);
    const responses = http.batch(chunk.map((item) => ['POST', URL, body(query(item)), headers(token(item))]));
    responses.forEach((r) => out.push(r.json('data')));
  }
  return out;
}

export function setup() {
  const run = Date.now();
  const names = Array.from({ length: USERS }, (_, i) => `load-${run}-${i}`);
  const tokens = batch(names, (n) => `mutation { register(username: "${n}", password: "password123") { accessToken } }`,
    () => null).map((d) => d.register.accessToken);
  const accounts = batch(tokens, () => 'mutation { openAccount { id } }', (t) => t).map((d) => d.openAccount.id);
  const users = tokens.map((token, i) => ({ token, account: accounts[i] }));
  batch(users, (u) => `mutation { deposit(accountId: ${u.account}, amountCents: 1000000) { id } }`, (u) => u.token);
  return { run, users };
}

export default function ({ run, users }) {
  const n = exec.scenario.iterationInTest;
  const from = users[n % users.length];
  const to = users[(n + 1) % users.length];
  const query = `mutation { transfer(input: {fromAccountId: ${from.account}, toAccountId: ${to.account},
    amountCents: 1, idempotencyKey: "${run}-${n}"}) { id } }`;

  const res = http.post(URL, body(query), {
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${from.token}` },
    tags: { name: 'transfer' },
  });

  // GraphQL reports errors with HTTP 200, so success means data with no errors.
  const errors = res.status === 200 ? res.json('errors') : null;
  const limited = errors && errors.some((e) => e.message === 'rate limited');
  if (limited) {
    transfersRateLimited.add(1);
    return;
  }
  const ok = res.status === 200 && !errors;
  transferDuration.add(res.timings.duration);
  transferErrors.add(!ok);
  if (ok) {
    transfersOk.add(1);
  }
}
