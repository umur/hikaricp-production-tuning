// k6 load test. Run with:
//   k6 run loadtest.js
//   k6 run -e ENDPOINT=anti-pattern loadtest.js   (hits the bad endpoint)
//
// The default endpoint POST /api/orders does the slow pricing call BEFORE the transaction
// opens. With pool size 10 and 50 virtual users, you will see hikaricp_connections_active
// climb but hikaricp_connections_pending stays at 0.
//
// Switch ENDPOINT=anti-pattern and re-run. Same pool size, same load, but the pricing call
// now happens INSIDE @Transactional. hikaricp_connections_pending climbs above 0 within
// seconds and the HikariPoolSaturated alert fires.

import http from 'k6/http';
import { check, sleep } from 'k6';

const ENDPOINT = __ENV.ENDPOINT || '';   // '' -> /api/orders, 'anti-pattern' -> /api/orders/anti-pattern
const BASE = __ENV.BASE || 'http://localhost:8080';

export const options = {
  scenarios: {
    spike: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '30s', target: 50 },
        { duration: '2m',  target: 50 },
        { duration: '10s', target: 0 },
      ],
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.05'],
    http_req_duration: ['p(95)<5000'],
  },
};

const PAYLOAD = JSON.stringify({
  customerEmail: 'load-test@example.com',
  items: ['widget-a', 'widget-b', 'widget-c'],
});

const HEADERS = { 'Content-Type': 'application/json' };

export default function () {
  const path = ENDPOINT ? `/api/orders/${ENDPOINT}` : '/api/orders';
  const res = http.post(`${BASE}${path}`, PAYLOAD, { headers: HEADERS });
  check(res, {
    'status is 2xx': (r) => r.status >= 200 && r.status < 300,
  });
  sleep(0.1);
}
