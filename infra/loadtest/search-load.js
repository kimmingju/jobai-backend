import http from 'k6/http';
import { check, group } from 'k6';
import { Trend, Rate } from 'k6/metrics';

const BASE = __ENV.BASE_URL || 'http://13.125.41.124:8080';

// 존재하지 않는 공고 조회의 404는 정상 응답이므로 실패율에서 제외한다.
http.setResponseCallback(http.expectedStatuses({ min: 200, max: 399 }, 404));

// 실제 존재하는 공고 id (조회가 실제 DB 조회를 타도록)
const JOB_IDS = [1611,1606,1603,1596,1580,1583,1587,1577,1600,1595,1575,1586,1601,1597,45,44,1573,1571];

// 엔드포인트별로 따로 봐야 병목이 어디인지 구분된다.
const searchLatency = new Trend('lat_search', true);
const latestLatency = new Trend('lat_latest_jobs', true);
const rankingLatency = new Trend('lat_scrap_rankings', true);
const detailLatency = new Trend('lat_job_detail', true);
const errors = new Rate('business_errors');

export const options = {
  // 부하를 계단식으로 올려 어느 지점에서 무너지는지 관찰한다.
  stages: [
    // 콜드 스타트(JIT, 커넥션 풀 워밍업)가 측정치를 오염시키지 않도록 먼저 데운다.
    { duration: '30s', target: 3 },
    { duration: '30s', target: 5 },
    { duration: '1m', target: 20 },
    { duration: '1m', target: 50 },
    { duration: '1m', target: 100 },
    { duration: '30s', target: 0 },
  ],
  thresholds: {
    http_req_failed: ['rate<0.05'],
    http_req_duration: ['p(95)<2000'],
  },
};

const QUERIES = ['백엔드', '프론트엔드', '신입 개발자', '서울 백엔드', 'AI', '데이터 분석', 'devops'];

export default function () {
  group('search', () => {
    const q = QUERIES[Math.floor(Math.random() * QUERIES.length)];
    const res = http.get(`${BASE}/api/search/jobs?query=${encodeURIComponent(q)}&page=0&size=20`, {
      tags: { name: 'search_jobs' },
    });
    searchLatency.add(res.timings.duration);
    const ok = check(res, { 'search 200': (r) => r.status === 200 });
    errors.add(!ok);
  });

  group('home', () => {
    const res = http.get(`${BASE}/api/v1/home/latest-jobs`, { tags: { name: 'latest_jobs' } });
    latestLatency.add(res.timings.duration);
    errors.add(!check(res, { 'latest 200': (r) => r.status === 200 }));

    const res2 = http.get(`${BASE}/api/v1/home/scrap-rankings`, { tags: { name: 'scrap_rankings' } });
    rankingLatency.add(res2.timings.duration);
    errors.add(!check(res2, { 'rankings 200': (r) => r.status === 200 }));
  });

  group('detail', () => {
    const id = JOB_IDS[Math.floor(Math.random() * JOB_IDS.length)];
    const res = http.get(`${BASE}/api/v1/private-jobs/${id}`, { tags: { name: 'job_detail' } });
    detailLatency.add(res.timings.duration);
    // 없는 공고 id는 404가 정상이므로 5xx만 오류로 센다.
    errors.add(res.status >= 500);
  });
}
