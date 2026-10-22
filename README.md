# ✨ JobA!
### 나에게 맞는 채용공고, 이제 직접 찾지 마세요
### IT 채용공고 자동 수집 · AI 매칭 · 실시간 알림 서비스

<img width="1920" height="1080" alt="표지" src="https://github.com/user-attachments/assets/fa4c9941-82ce-4194-bf24-f2a444191e19" />

>TAVE 17기 프로젝트 <최우수상🏆> 수상
---

## 📌 Project Overview
JobA!는 사용자가 채용공고를 직접 찾는 대신,
**IT 채용공고를 자동으로 수집**하고
**AI 모델이 이력서와 매칭해 적합도를 점수화**하며,
**사용자에게 적합한 공고를 실시간으로 알려 주는** 채용 준비 자동화 서비스입니다.

공고 수집부터 이력서 매칭, 알림 발송, 지원 현황 관리까지
채용 준비의 모든 과정을 하나로 연결했습니다.

> 🏷️ TAVE 17th Project

---

## 🎯 Problem & Solution

### ❗ Problem
- **플랫폼 분산** — 취준생 절반 이상이 사람인·잡코리아·원티드 등 2개 이상을 동시에 사용해요
- **수시채용 전환** — 공고를 놓칠 위험이 커졌어요
- **직접 확인** — 공고를 하나씩 읽어 봐야 나와 맞는지 알 수 있어요
- **반복 확인** — 새 공고가 올라왔는지 계속 들어가 봐야 해요
- **근거 부족** — 왜 이 공고가 나에게 맞는지 추천 근거를 알기 어려워요
- **복잡한 조건** — 조건이 다양하고 복잡해 의도에 맞는 공고를 정확히 찾기 번거로워요
- **흩어진 일정** — 채용 관련 일정(지원 현황·면접 등)을 한곳에서 관리하기 어려워요


### ✅ Solution
- **자동 통합 수집** — 사기업·공공기관 IT 공고를 한곳에 자동으로 수집해 제공해요
- **AI 적합도 분석** — 이력서를 분석해 공고별 적합도를 0~100점으로 산출해요
- **AI 요약 제공** — 공고의 핵심 내용을 요약해 빠르게 확인할 수 있어요
- **신규 공고 알림** — 설정한 적합도 기준을 넘는 공고가 올라오면 Slack·Discord·이메일로 알려드려요
- **매칭 근거 제공** — 적합도 점수의 근거를 함께 제공해 신뢰성을 높여요
- **자연어 검색** — 키워드 한 단어든 긴 문장이든, 의도를 파악해 적합한 공고를 찾아드려요
- **일정 통합 관리** — 스크랩한 공고의 지원 현황·면접 일정을 한곳에서 관리해드려요
---

## 🧰 Key Features
- 🔎 **자동 수집 & 실시간 알림** (사기업·공공기관 IT 공고 통합 수집 → 임계값 초과 시 이메일 알림)
- 🤖 **AI 기반 적합도 매칭** (AI 서버의 자체 매칭 모델이 이력서–공고 적합도를 점수화 + 보유·부족 기술·추천 근거 제공)
- 💬 **자연어 공고 검색** (Query Expansion → Hybrid Search → Rerank 3단계 파이프라인으로 의도에 맞는 공고 탐색)
- 📄 **AI 공고 요약** (공고를 요약하고 이력서와 비교 분석해 적합도·추천 이유 표시)
- 🗂️ **입사 지원 현황 트래커** (지원 단계·면접 일정을 자동 정리, 다가오는 일정 요약)
- ⭐ **스크랩 & 마감 임박 알림** (관심 공고 저장, 마감 가까운 공고 자동 취합)
- ⚙️ **온보딩 기반 맞춤 설정** (희망 직무·근무 지역·고용 형태·적합도 기준·알림 채널)

---

  ## 🚀 Technical Highlights                                                                                                                      
                                                                                                                                                  
  - 🌙 **매일 새벽 2시 자동 실행되는 6단계 수집·매칭 파이프라인**                                                                                 
    - 스케줄러가 매일 새벽 2시에 **수집 → 분류 → 임베딩 → 매칭 점수 산출 → 알림**까지 6단계를 자동 실행                                           
    - 각 단계를 **개별 try-catch로 격리**해, 앞 단계가 실패해도 뒷 단계는 정상 실행                                                               
    - **Step 1 · 사기업 수집** — 크롤링으로 공고를 수집하고, 신규는 **INSERT**, 변경은 **UPDATE**, 미노출 공고는 **마감 처리**
    - **Step 2 · 공기업 수집** — 공공데이터 API로 공고를 수집하며, 신규·변경 사항을 동일하게 반영
    - **Step 3 · 분류** — 직무 카테고리·고용형태·경력·지역이 누락된 공고를 **LLM으로 일괄 분류**하여 저장
    - **Step 4 · 이력서 임베딩 복구** — 업로드 시 AI 서버 장애로 임베딩 생성에 실패한 이력서를 자동으로 재시도하여 복구
    - **Step 5 · 공고 임베딩** — 임베딩이 없는 공고를 **AI 서버의 임베딩 모델**로 벡터 변환하며, 미생성 건수가 **0건이 될 때까지 반복 처리**
    - **Step 6 · 매칭 점수 산출 및 알림** — **AI 서버의 스코어링 모델**로 활성 이력서–공고 간 적합도 점수를 산출·저장하며, 사기업·공기업은 **독립
  실행**하고 임계값 이상 공고는 **즉시 알림 발송**

  - 🧭 **이원화된 공고 수집 전략**
    - 사기업: **YAML 설정 기반 크롤러(18종) + Jsoup**, `source_type`(json / embedded_json)별 수집 → `mapRecord` 필드 매핑 → `applyFilter` → 상세
  보강 → `source_job_id` 기준 Upsert → **Claude API 기반 LLM 분류**
    - 공기업: **공공데이터 API + 상세 병렬 조회**, 직무기술서(PDF·HWP·ZIP) 다운로드 후 **본문·NCS 소분류 파싱** → `pblntfNo` 기준 Upsert
    - 회사별 순회 시 **에러 격리**로 일부 실패가 전체 수집을 막지 않도록 설계

  - 🗣 **4-path 자연어 검색 파이프라인**
    - 형태소 분석기(Komoran)로 쿼리에서 카테고리·지역·경력·회사명을 구조화 조건으로 추출하고,
      인식되지 않은 표현(unmatched token)은 다음 단계로 전달

    - **① Query Expansion** — LLM이 unmatched token을 세 유형으로 분류
      - `EXACT_REQUIRED`: 특정 기술명(Kafka, Java 등) → 해당 단어가 공고에 반드시 포함되어야 함
      - `SEMANTIC_REQUIRED`: 의미 표현("재택근무" 등) → 원격근무·WFH 등 유사 키워드로 확장하여 필터 적용
      - `SEMANTIC_PREFERRED`: 분위기·문화 표현("수평적 문화" 등) → 벡터 검색 힌트로만 활용

    - **② 4-path 검색 라우팅** — 분석 결과에 따라 최적 경로 선택
      - **Path A (Keyword)**: 모든 토큰이 구조화 조건으로 인식된 경우 → DB 필터 검색
      - **Path B (Hybrid)**: 구조화 조건 또는 확장 키워드가 있는 경우 → DB 필터로 조건 일치 수준별 후보 그룹(STRICT → RELAXED)을 수집하고, 그룹
  순서를 유지하면서 그룹 내부에서 벡터 유사도로 재정렬
      - **Path C (Vector)**: 구조화 앵커 없는 순수 자연어 → 벡터 유사도 검색
      - **Path D (Exact-first)**: `EXACT_REQUIRED` 토큰만 존재하는 경우 → 기술명 포함 공고 우선, 미포함 공고를 벡터 검색으로 후순위 병합

    - **③ Per-group Rerank** — 조건 일치 수준별 그룹(STRICT → RELAXED) 순서를 유지하면서,
      그룹 내부에서만 한국어 특화 **Cross-Encoder** 모델로 쿼리–공고 관련성을 재평가
      *(전체 리스트 재정렬 시 필터 기반 그룹 순서가 붕괴되어 Recall@10이 하락하는 문제를 실험으로 검증 후 적용)*

    - 각 단계를 **독립적으로 On/Off** 가능하도록 설계하고,
      외부 AI 서버 장애 시 **키워드 검색으로 자동 Fallback**

    - 평가 지표(**MRR@10 / Recall@5 / Recall@10**) 기반으로 파이프라인 개선 효과를 수치로 검증
      - **동일 조건(R3) 기준 파이프라인 단독 효과: KEYWORD Recall@10 0.38 → HYBRID+RERANK 0.40**
      - **평가 과정에서 발견한 데이터·필터 문제(경력='미확인' 누락) 수정 포함 종합 효과: Recall@10 0.29 → 0.40 (+38%)**

  - ⚡ **Kafka 이벤트 드리븐 스코어링**
    - 단일 스레드 동기 처리(42.8s)를 **6파티션 × 6Consumer 병렬 처리**(28.7s)로 전환하여 **33% 단축**
    - Redis 카운터 기반 **분산 완료 판정** + `setIfAbsent`로 배치 알림 **정확히 1회 발송** 보장
    - 실패 메시지는 **DLT(Dead Letter Topic)**에 보관하여 운영 복구 경로 확보

  - 🛡 **멀티레이어 캐싱 (Caffeine L1 + Redis L2)**
    - 전 사용자 공유 데이터(최신 공고·테크카드)는 **L1(JVM 힙)**까지, 사용자·쿼리별 데이터(검색·추천)는 **L2(Redis)**만 사용
    - 검색 결과와 매칭 점수를 **캐시 키에서 분리**하여 히트율 보존
    - Spring `Cache`/`CacheManager` 인터페이스를 직접 구현한 **TwoLevelCache**
    - 검색 API 응답 **3,600ms → 25ms (99.3% 단축)**, L1 히트율 **94%**

  - 🔒 **외부 서비스 장애 격리 (Resilience4j)**
    - AI 서버·공공데이터 API·Anthropic API를 **독립 서킷 3개로 분리**
    - Reactive 체인에는 `CircuitBreakerOperator`, blocking 호출에는 `executeCheckedSupplier` 적용
    - 서킷 OPEN 시 처리시간 **121s → 3s (97.5% 감소)**

  - 📊 **APM 모니터링 (Micrometer + Prometheus + Grafana)**
    - MDC 기반으로 **HTTP → Kafka → @Async** 전체 흐름을 단일 Request ID로 추적
    - JVM·API 응답시간·HikariCP·Kafka Lag·AI 채점 소요시간 등 메트릭 수집 + 대시보드 시각화
    - 서버 다운·힙 90%·5xx 에러율 등 **임계치 초과 시 Discord 자동 알림**

---

## 🛠 Tech Stack

### 🛠 Backend & Framework
<div>
  <img src="https://img.shields.io/badge/Java 21-007396?style=flat-square&logo=openjdk&logoColor=white">
  <img src="https://img.shields.io/badge/Gradle-02303A?style=flat-square&logo=gradle&logoColor=white">
  <img src="https://img.shields.io/badge/Spring Boot-6DB33F?style=flat-square&logo=springboot&logoColor=white">
  <img src="https://img.shields.io/badge/Spring Data JPA-6DB33F?style=flat-square&logo=hibernate&logoColor=white">
  <img src="https://img.shields.io/badge/Flyway-CC0200?style=flat-square&logo=flyway&logoColor=white">
</div>

### 📨 Messaging
<div>
  <img src="https://img.shields.io/badge/Apache Kafka-231F20?style=flat-square&logo=apachekafka&logoColor=white">
</div>

### 🤖 AI
<div>
  <img src="https://img.shields.io/badge/Claude API (Anthropic)-D97757?style=flat-square&logo=anthropic&logoColor=white">
  <img src="https://img.shields.io/badge/KOMORAN-4B8BBE?style=flat-square&logoColor=white">
</div>

### 🔐 Authentication & Security
<div>
  <img src="https://img.shields.io/badge/Spring Security-6DB33F?style=flat-square&logo=springsecurity&logoColor=white">
  <img src="https://img.shields.io/badge/OAuth2-000000?style=flat-square&logo=oauth&logoColor=white">
  <img src="https://img.shields.io/badge/JWT-000000?style=flat-square&logo=jsonwebtokens&logoColor=white">
</div>

### 🗄 Database
<div>
  <img src="https://img.shields.io/badge/PostgreSQL-4169E1?style=flat-square&logo=postgresql&logoColor=white">
  <img src="https://img.shields.io/badge/pgvector-4169E1?style=flat-square&logo=postgresql&logoColor=white">
  <img src="https://img.shields.io/badge/Redis-DC382D?style=flat-square&logo=redis&logoColor=white">
  <img src="https://img.shields.io/badge/Redisson-DC382D?style=flat-square&logo=redis&logoColor=white">
  <img src="https://img.shields.io/badge/Caffeine Cache-6DB33F?style=flat-square&logoColor=white">
</div>

### ☁️ Cloud (AWS)
<div>
  <img src="https://img.shields.io/badge/AWS EC2-FF9900?style=flat-square&logo=amazonec2&logoColor=white">
  <img src="https://img.shields.io/badge/AWS S3-569A31?style=flat-square&logo=amazons3&logoColor=white">
  <img src="https://img.shields.io/badge/AWS Lambda-FF9900?style=flat-square&logo=awslambda&logoColor=white">
  <img src="https://img.shields.io/badge/AWS SES-DD344C?style=flat-square&logo=amazonsimpleemailservice&logoColor=white">
</div>

### 🧲 Data Collection
<div>
  <img src="https://img.shields.io/badge/Jsoup-1E9E4A?style=flat-square&logo=java&logoColor=white">
  <img src="https://img.shields.io/badge/Rome (RSS)-FA9B39?style=flat-square&logoColor=white">
  <img src="https://img.shields.io/badge/Apache PDFBox-D22128?style=flat-square&logo=apache&logoColor=white">
  <img src="https://img.shields.io/badge/HWPLib-0068B7?style=flat-square&logoColor=white">
  <img src="https://img.shields.io/badge/공공데이터 API-003D7C?style=flat-square&logoColor=white">
  <img src="https://img.shields.io/badge/@Scheduled-6DB33F?style=flat-square&logo=spring&logoColor=white">
</div>

### 💬 Communication
<div>
  <img src="https://img.shields.io/badge/WebSocket-010101?style=flat-square&logo=socketdotio&logoColor=white">
  <img src="https://img.shields.io/badge/Slack-4A154B?style=flat-square&logo=slack&logoColor=white">
  <img src="https://img.shields.io/badge/Discord-5865F2?style=flat-square&logo=discord&logoColor=white">
  <img src="https://img.shields.io/badge/Email-EA4335?style=flat-square&logo=gmail&logoColor=white">
</div>

### 📊 Monitoring
<div>
  <img src="https://img.shields.io/badge/Micrometer-4DB33D?style=flat-square&logoColor=white">
  <img src="https://img.shields.io/badge/Prometheus-E6522C?style=flat-square&logo=prometheus&logoColor=white">
  <img src="https://img.shields.io/badge/Grafana-F46800?style=flat-square&logo=grafana&logoColor=white">
</div>

### 🚀 DevOps
<div>
  <img src="https://img.shields.io/badge/Docker-2496ED?style=flat-square&logo=docker&logoColor=white">
  <img src="https://img.shields.io/badge/GitHub Actions-2088FF?style=flat-square&logo=githubactions&logoColor=white">
</div>

### 🧪 Docs & Test
<div>
  <img src="https://img.shields.io/badge/Swagger-85EA2D?style=flat-square&logo=swagger&logoColor=black">
  <img src="https://img.shields.io/badge/JUnit5-25A162?style=flat-square&logo=junit5&logoColor=white">
</div>

---

## 🏗 Architecture
<img width="1640" height="804" alt="image 49" src="https://github.com/user-attachments/assets/64689a0f-8852-4dcf-a6bf-317ded21281b" />

---

## 🗄 ERD
<img width="2701" height="1812" alt="image" src="https://github.com/user-attachments/assets/c6cfeb1e-6067-40ac-b540-0c9a5a18c2d3" />

---

## 📖 API Documentation
프로젝트의 전체 API 명세는 Swagger를 통해 확인할 수 있습니다.

### 🔗 Documentation
* Swagger UI : [https://api.jobai.site/swagger-ui/index.html](https://api.jobai.site/swagger-ui/index.html)

## 📂 Directory Structure
```text
📦 src/main/java/com/jobai/backend
 ├── 📁 domain                        # 핵심 비즈니스 로직
 │   ├── 📁 application               # 입사 지원 현황 트래커
 │   ├── 📁 auth                      # 인증 (OAuth2)
 │   ├── 📁 bloom                     # Bloom Filter 기반 공고 중복 제거
 │   ├── 📁 crawler                   # 선언형 웹 크롤러 엔진
 │   │   ├── 📁 service                   # 크롤링 엔진 핵심 로직
 │   │   └── 📁 spec                      # 기업별 크롤링 스펙 정의
 │   ├── 📁 home                      # 홈 화면 추천 공고
 │   ├── 📁 matching                  # AI 매칭 점수 산출 · 배치
 │   ├── 📁 member                    # 회원 관리 · 이력서 · 온보딩
 │   ├── 📁 notification              # 알림 (Slack · Email · Discord)
 │   ├── 📁 privatejob                # 사기업 공고 상세 조회 · AI 요약
 │   ├── 📁 privatejobposting         # 사기업 공고 수집 · 분류 · 스케줄링
 │   │   ├── 📁 runner                    # 수집 실행기
 │   │   ├── 📁 scheduler                 # 수집 스케줄러
 │   │   └── 📁 service                   # 수집 · 분류 · 내보내기
 │   ├── 📁 publicInstitution         # 공공기관 채용공고 수집 (공공데이터 API)
 │   ├── 📁 scrap                     # 공고 스크랩 · 마감 임박 알림
 │   ├── 📁 search                    # 자연어 검색 · 임베딩 · 벡터 검색
 │   └── 📁 techcard                  # 테크 트렌드 카드 수집 · 요약
 │
 └── 📁 global                        # 공통 모듈
     ├── 📁 ai                        # AI 서버 클라이언트 (스코어링 · 임베딩)
     ├── 📁 apiPayload                # 공통 응답 · 에러 코드 · 예외 처리
     ├── 📁 auth                      # Security Filter · OAuth2 핸들러
     ├── 📁 cache                     # 멀티레이어 캐시 (Caffeine L1 + Redis L2)
     ├── 📁 config                    # WebClient · Redis · Swagger · S3 설정
     ├── 📁 enums                     # 공통 Enum (직무 · 고용형태 · 경력)
     ├── 📁 kafka                     # Kafka Producer · Consumer · 이벤트 정의
     ├── 📁 llm                       # Claude API 클라이언트
     ├── 📁 storage                   # 파일 스토리지 (S3)
     └── 📁 util                      # 공통 유틸리티

📦 src/main/resources
 ├── 📄 application.yaml              # 기본 설정
 ├── 📄 application-classify.yml      # 공고 분류 프로필
 ├── 📄 application-collect.yml       # 공고 수집 프로필
 ├── 📄 application-export.yml        # 내보내기 프로필
 ├── 📁 db/migration                  # Flyway 마이그레이션 (V1~V13)
 └── 📁 specs                         # 크롤러 기업별 스펙 (18개 기업)

📦 infra                              # 인프라 구성
 ├── 📁 nginx                         # Nginx 설정
 ├── 📁 postgres                      # DB 초기화 스크립트
 └── 📁 terraform                     # AWS IaC (EC2 / ECR / IAM / S3)

📄 .github/workflows                  # GitHub Actions CI/CD
📄 docker-compose.yml                 # 로컬 개발용
📄 docker-compose.prod.yml            # 운영 배포용
📄 Dockerfile
📄 build.gradle
```





---

## 👨‍👩‍👧‍👦 Developer

## Devloper
| 김민주 | 이원준 | 이정헌 | 
|:------:|:------:|:------:|
| <img src="https://github.com/user-attachments/assets/c367f7cd-0700-428a-9571-a8ccf10a2572" alt="김민주" width="190" height="200"> | <img src="https://github.com/user-attachments/assets/6495bd60-1034-4b58-a5a6-ffe410464efd" alt="이원준" width="190" height="200"> | <img src="https://github.com/user-attachments/assets/6de709d7-39f3-43df-b7bb-e636c42463c0" alt="이정헌" width="190" height="200"> |
| BE | BE | BE | 
| [GitHub](https://github.com/kimmingju) | [GitHub](https://github.com/wonjun-lee-fcwj245) |  [GitHub](https://github.com/LeeJeongHeon02) | 

---
