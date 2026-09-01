import http from 'k6/http';
import { check } from 'k6';
import { Trend } from 'k6/metrics';

/**
 * 실사용 트래픽 가정에 기반한 부하 테스트.
 *
 *   DAU 10,000 / 피크 1시간에 15% 집중 / 1인당 20요청
 *     정상 피크 = 10000 * 0.15 * 20 / 3600 =  8.3 req/s
 *     스파이크  = 정상 피크의 10배        = 83   req/s  (공채 오픈일 가정)
 *
 * 고정 VU가 아니라 도착률(arrival rate) 모델을 쓴다.
 * 실제 사용자는 "동시 접속자 N명"이 아니라 "초당 N명이 도착"하는 형태이고,
 * 서버가 느려져도 도착률은 줄지 않기 때문에 이쪽이 현실에 가깝다.
 */

const BASE = __ENV.BASE_URL || 'http://13.125.41.124:8080';
http.setResponseCallback(http.expectedStatuses({ min: 200, max: 399 }, 404));

const JOB_IDS = [1611,1606,1603,1596,1580,1583,1587,1577,1600,1595,1575,1586,1601,1597,45,44,1573,1571];
const QUERIES = ['백엔드', '프론트엔드', '신입 개발자', '서울 백엔드', 'AI', '데이터 분석', 'devops'];

const normalLat = new Trend('lat_normal_peak', true);
const spikeLat = new Trend('lat_spike', true);

export const options = {
  scenarios: {
    normal_peak: {
      executor: 'constant-arrival-rate',
      rate: 9,                 // 초당 9 세션 (정상 피크 8.3 올림)
      timeUnit: '1s',
      duration: '2m',
      preAllocatedVUs: 20,
      maxVUs: 100,
      exec: 'normalPeak',
      tags: { phase: 'normal' },
    },
    spike: {
      executor: 'ramping-arrival-rate',
      startRate: 9,
      timeUnit: '1s',
      preAllocatedVUs: 50,
      maxVUs: 400,
      startTime: '2m30s',      // 정상 피크 종료 후 30초 쉬고 시작
      stages: [
        { duration: '30s', target: 83 },  // 공채 오픈 순간 급증
        { duration: '1m30s', target: 83 },// 스파이크 유지
        { duration: '30s', target: 9 },   // 진정
      ],
      exec: 'spikePhase',
      tags: { phase: 'spike' },
    },
  },
  // SLO. 하나라도 못 지키면 k6가 실패로 종료된다.
  thresholds: {
    'http_req_failed{phase:normal}': ['rate<0.001'],
    'http_req_duration{phase:normal}': ['p(95)<200'],
    'http_req_failed{phase:spike}': ['rate<0.01'],
    'http_req_duration{phase:spike}': ['p(95)<500'],
  },
};

// 사용자 한 명의 전형적인 세션: 검색 -> 목록 -> 상세 -> 랭킹
function userSession(trend) {
  const q = QUERIES[Math.floor(Math.random() * QUERIES.length)];
  let res = http.get(`${BASE}/api/search/jobs?query=${encodeURIComponent(q)}&page=0&size=20`,
                     { tags: { name: 'search_jobs' } });
  trend.add(res.timings.duration);
  check(res, { 'search ok': (r) => r.status === 200 });

  res = http.get(`${BASE}/api/v1/home/latest-jobs`, { tags: { name: 'latest_jobs' } });
  trend.add(res.timings.duration);
  check(res, { 'latest ok': (r) => r.status === 200 });

  const id = JOB_IDS[Math.floor(Math.random() * JOB_IDS.length)];
  res = http.get(`${BASE}/api/v1/private-jobs/${id}`, { tags: { name: 'job_detail' } });
  trend.add(res.timings.duration);

  res = http.get(`${BASE}/api/v1/home/scrap-rankings`, { tags: { name: 'scrap_rankings' } });
  trend.add(res.timings.duration);
  check(res, { 'rankings ok': (r) => r.status === 200 });
}

export function normalPeak() { userSession(normalLat); }
export function spikePhase() { userSession(spikeLat); }
