import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend, Rate } from 'k6/metrics';
import exec from 'k6/execution';

const BASE_URL = (__ENV.BASE_URL || 'http://gateway').replace(/\/$/, '');
const PASSWORD = __ENV.UAT_PASSWORD;

const authMe = new Trend('uat_auth_me_duration', true);
const dashboard = new Trend('uat_dashboard_duration', true);
const customerPage = new Trend('uat_customer_page_duration', true);
const customerSearch = new Trend('uat_customer_search_duration', true);
const writeFlow = new Trend('uat_write_flow_duration', true);
const teamData = new Trend('uat_team_data_duration', true);
const content = new Trend('uat_content_duration', true);
const circle = new Trend('uat_circle_duration', true);
const businessErrors = new Rate('uat_business_errors');

export const options = {
  scenarios: {
    formal_mixed: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: __ENV.RAMP_UP || '2m', target: Number(__ENV.VUS || 100) },
        { duration: __ENV.STEADY || '26m', target: Number(__ENV.VUS || 100) },
        { duration: __ENV.RAMP_DOWN || '2m', target: 0 },
      ],
      gracefulRampDown: '30s',
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    uat_business_errors: ['rate<0.01'],
    uat_auth_me_duration: ['p(95)<1500'],
    uat_dashboard_duration: ['p(95)<2000'],
    uat_customer_page_duration: ['p(95)<2000'],
    uat_customer_search_duration: ['p(95)<2000'],
    uat_write_flow_duration: ['p(95)<3000'],
    uat_team_data_duration: ['p(95)<3000'],
    uat_content_duration: ['p(95)<3000'],
    uat_circle_duration: ['p(95)<3000'],
  },
  summaryTrendStats: ['min', 'med', 'avg', 'p(90)', 'p(95)', 'p(99)', 'max'],
  noConnectionReuse: false,
  // Each VU represents one independent 30-day browser session across many
  // iterations; k6 otherwise clears its cookie jar after every iteration.
  noCookiesReset: true,
  userAgent: 'yingge-formal-uat-k6/20260720',
};

function timed(metric, request) {
  const response = request();
  metric.add(response.timings.duration);
  const ok = check(response, { [`${response.request.method} ${response.request.url} is successful`]: r => r.status >= 200 && r.status < 300 });
  if (!ok && __ENV.DEBUG_FAILURES === 'true') {
    console.warn(`[${identity ? identity.username : 'unknown'}] ${response.request.method} ${response.request.url} => ${response.status} ${String(response.body).slice(0, 300)}`);
  }
  businessErrors.add(!ok);
  return response;
}

function seededIdentity() {
  const g = exec.vu.idInTest - 1;
  if (g === 0) return { username: 'uat-admin', privileged: true, writable: true };
  if (g === 1) return { username: 'admin-b', privileged: true, writable: true };
  if (g >= 2 && g <= 7) return { username: `leader-${String.fromCharCode(95 + g)}`, privileged: true, writable: true };
  if (g === 8) return { username: 'member-a1', privileged: false, writable: true };
  if (g === 9) return { username: 'member-a2', privileged: false, writable: true };
  if (g === 23) return { username: 'member-b1', privileged: false, writable: true };
  if (g >= 10 && g <= 97) return { username: `member-${String(g - 7).padStart(3, '0')}`, privileged: false, writable: true };
  if (g === 98) return { username: 'viewer-a', privileged: false, writable: false };
  return { username: `viewer-${String(g - 97).padStart(2, '0')}`, privileged: false, writable: false };
}

function login(identity) {
  const csrfResponse = http.get(`${BASE_URL}/api/v1/auth/csrf`, { tags: { name: 'GET /auth/csrf' } });
  const csrf = csrfResponse.json('token');
  const loginResponse = http.post(`${BASE_URL}/api/v1/auth/login`, JSON.stringify({
    username: identity.username, password: PASSWORD,
  }), {
    headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': csrf },
    tags: { name: 'POST /auth/login' },
  });
  const ok = check(loginResponse, { 'independent session login': r => r.status === 200 });
  if (__ENV.DEBUG_FAILURES === 'true') {
    const cookiePolicy = {};
    for (const [name, values] of Object.entries(loginResponse.cookies)) {
      cookiePolicy[name] = values.map(value => ({ secure: value.secure, path: value.path, domain: value.domain }));
    }
    console.info(`[${identity.username}] login cookie policy ${JSON.stringify(cookiePolicy)}`);
  }
  businessErrors.add(!ok);
  return csrf;
}

let csrfToken;
let identity;

export default function () {
  if (!csrfToken) {
    identity = seededIdentity();
    csrfToken = login(identity);
  }
  const selector = Math.random();
  if (selector < 0.18) {
    timed(authMe, () => http.get(`${BASE_URL}/api/v1/auth/me`, { tags: { name: 'GET /auth/me' } }));
  } else if (selector < 0.31) {
    timed(dashboard, () => http.get(`${BASE_URL}/api/v1/dashboard`, { tags: { name: 'GET /dashboard' } }));
  } else if (selector < 0.65) {
    const page = Math.floor(Math.random() * 1700);
    timed(customerPage, () => http.get(`${BASE_URL}/api/v1/customers?page=${page}&size=30&sort=updatedDesc`, {
      tags: { name: 'GET /customers page' },
    }));
  } else if (selector < 0.77) {
    const customer = String(1 + Math.floor(Math.random() * 50000)).padStart(5, '0');
    timed(customerSearch, () => http.get(`${BASE_URL}/api/v1/customers?search=UAT-20260720-%E8%A7%84%E6%A8%A1%E5%AE%A2%E6%88%B7-${customer}&size=10`, {
      tags: { name: 'GET /customers search' },
    }));
  } else if (selector < 0.84) {
    timed(circle, () => http.get(`${BASE_URL}/api/v1/circle/posts?page=${Math.floor(Math.random() * 300)}&size=20&sort=recommended`, {
      tags: { name: 'GET /circle' },
    }));
  } else if (selector < 0.89) {
    if (identity.writable) {
      timed(content, () => http.get(`${BASE_URL}/api/v1/content-tasks/overview`, { tags: { name: 'GET /content overview' } }));
    } else {
      timed(circle, () => http.get(`${BASE_URL}/api/v1/circle/posts?size=20&sort=recommended`, { tags: { name: 'GET /circle' } }));
    }
  } else if (selector < 0.94) {
    if (identity.privileged) {
      timed(teamData, () => http.get(`${BASE_URL}/api/v1/team-data/overview`, { tags: { name: 'GET /team-data' } }));
    } else {
      timed(dashboard, () => http.get(`${BASE_URL}/api/v1/dashboard`, { tags: { name: 'GET /dashboard' } }));
    }
  } else if (selector < 0.98) {
    timed(customerPage, () => http.get(`${BASE_URL}/api/v1/reminders`, { tags: { name: 'GET /reminders' } }));
  } else if (identity.writable) {
    const started = Date.now();
    const suffix = `${String(exec.vu.idInTest).padStart(3, '0')}${String(exec.scenario.iterationInTest).padStart(6, '0')}`;
    const created = http.post(`${BASE_URL}/api/v1/customers`, JSON.stringify({
      fullName: `K6-UAT-${suffix}`, phone: `18${suffix}`, visibility: 'PRIVATE',
      identityType: '在校生', focusDirections: ['升学'], intentionLevel: '中', urgencyLevel: '中',
      verificationStatus: '未核实', currentStatus: '跟进中', duplicateOverrideReason: '压测唯一数据',
    }), {
      headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': csrfToken },
      tags: { name: 'POST /customers' },
    });
    let ok = check(created, { 'write customer created': r => r.status === 201 });
    if (created.status === 201) {
      const id = created.json('id');
      const interaction = http.post(`${BASE_URL}/api/v1/customers/${id}/interactions`, JSON.stringify({
        contactedAt: new Date().toISOString(), channel: '微信', summary: 'k6 UAT 混合负载沟通',
        nextFollowUpAt: new Date(Date.now() + 86400000).toISOString(), currentStatus: '跟进中', tagIds: [],
      }), {
        headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': csrfToken },
        tags: { name: 'POST /interactions' },
      });
      ok = check(interaction, { 'write interaction created': r => r.status === 201 }) && ok;
    }
    writeFlow.add(Date.now() - started);
    businessErrors.add(!ok);
  } else {
    timed(authMe, () => http.get(`${BASE_URL}/api/v1/auth/me`, { tags: { name: 'GET /auth/me' } }));
  }
  sleep(1 + Math.random() * 2);
}
