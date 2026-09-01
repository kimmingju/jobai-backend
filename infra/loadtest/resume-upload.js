import http from 'k6/http';
import { check } from 'k6';
import { Trend, Rate } from 'k6/metrics';
import { SharedArray } from 'k6/data';

/**
 * 이력서를 동시에 업로드했을 때 DB 커넥션 풀이 고갈되어
 * 무관한 일반 API까지 느려지는지 검증한다.
 *
 * 배경:
 *  - uploadResume(@Transactional): S3 업로드 + PDF 파싱 + AI 임베딩 2회 (실측 3.3초)
 *  - calculateScores(@Transactional, @Async): 공고 630건마다 AI 채점 호출
 *  - HikariCP 풀 크기 10 (전체 API가 공유)
 */

const BASE = __ENV.BASE_URL;
const N = parseInt(__ENV.UPLOADS || '15');

const tokens = new SharedArray('tokens', () => JSON.parse(open('./tokens.json')));
const pdf = open('./resume.pdf', 'b');

const uploadLatency = new Trend('upload_latency', true);
const searchLatency = new Trend('bystander_search_latency', true);
const searchErrors = new Rate('bystander_search_errors');

export const options = {
  scenarios: {
    uploads: {
      executor: 'per-vu-iterations',
      vus: N,
      iterations: 1,
      exec: 'upload',
      startTime: '15s',   // 먼저 평상시 기준선을 15초 확보한 뒤 시작
    },
    bystanders: {
      executor: 'constant-arrival-rate',
      rate: 5,
      timeUnit: '1s',
      duration: '3m',
      preAllocatedVUs: 30,
      maxVUs: 200,
      exec: 'bystander',
    },
  },
};

export function upload() {
  // 업로드 계정은 토큰 목록 뒤쪽(이미 채점된 계정과 겹치지 않게)에서 고른다
  const t = tokens[(__VU - 1) % tokens.length];
  const res = http.post(`${BASE}/api/v1/members/resumes`,
    { file: http.file(pdf, 'resume.pdf', 'application/pdf') },
    { headers: { Cookie: `accessToken=${t.token}` }, timeout: '300s', tags: { name: 'upload' } });
  uploadLatency.add(res.timings.duration);
  check(res, { 'upload 201': (r) => r.status === 201 });
}

export function bystander() {
  const res = http.get(`${BASE}/api/search/jobs?query=%EB%B0%B1%EC%97%94%EB%93%9C&page=0&size=20`,
                       { timeout: '120s', tags: { name: 'bystander_search' } });
  searchLatency.add(res.timings.duration);
  searchErrors.add(!check(res, { 'search 200': (r) => r.status === 200 }));
}
