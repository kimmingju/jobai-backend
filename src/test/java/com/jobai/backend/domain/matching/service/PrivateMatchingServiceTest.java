package com.jobai.backend.domain.matching.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobai.backend.global.ai.client.AiScoringClient;
import com.jobai.backend.global.ai.dto.ScorePrivateRequest;
import com.jobai.backend.global.ai.dto.ScorePrivateResponse;
import com.jobai.backend.domain.matching.entity.PrivateMatchScore;
import com.jobai.backend.domain.privatejobposting.entity.PrivateJobPosting;
import com.jobai.backend.domain.privatejobposting.repository.PrivateJobPostingRepository;
import com.jobai.backend.domain.matching.repository.PrivateMatchScoreRepository;
import com.jobai.backend.domain.member.entity.Member;
import com.jobai.backend.domain.member.entity.Resumes;
import com.jobai.backend.domain.member.repository.ResumesRepository;
import com.jobai.backend.domain.search.entity.JobEmbedding;
import com.jobai.backend.global.enums.JobSource;
import com.jobai.backend.domain.search.repository.JobEmbeddingRepository;
import com.jobai.backend.domain.search.service.EmbeddingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PrivateMatchingServiceTest {

    private AiScoringClient aiScoringClient;
    private PrivateJobPostingRepository privateJobPostingRepository;
    private JobEmbeddingRepository jobEmbeddingRepository;
    private EmbeddingService embeddingService;
    private PrivateMatchScoreRepository privateMatchScoreRepository;
    private ResumesRepository resumesRepository;
    private ObjectMapper objectMapper;

    private PrivateMatchingService service;

    private final AtomicLong postingIdCounter = new AtomicLong(100);

    @BeforeEach
    void setUp() {
        aiScoringClient = Mockito.mock(AiScoringClient.class);
        privateJobPostingRepository = Mockito.mock(PrivateJobPostingRepository.class);
        jobEmbeddingRepository = Mockito.mock(JobEmbeddingRepository.class);
        embeddingService = Mockito.mock(EmbeddingService.class);
        privateMatchScoreRepository = Mockito.mock(PrivateMatchScoreRepository.class);
        resumesRepository = Mockito.mock(ResumesRepository.class);
        objectMapper = new ObjectMapper();
        when(privateMatchScoreRepository.findByResumeId(anyLong())).thenReturn(List.of());

        PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
        when(txManager.getTransaction(any())).thenReturn(mock(org.springframework.transaction.TransactionStatus.class));
        TransactionTemplate transactionTemplate = new TransactionTemplate(txManager);

        service = new PrivateMatchingService(
                aiScoringClient,
                privateJobPostingRepository,
                jobEmbeddingRepository,
                embeddingService,
                privateMatchScoreRepository,
                resumesRepository,
                objectMapper,
                transactionTemplate
        );
    }

    private Member createMember(String careerType) {
        return Member.builder()
                .email("test@example.com")
                .careerTypes(careerType == null ? List.of() : List.of(careerType))
                .build();
    }

    private Resumes createResume(Member member, float[] embedding, String skills) {
        return Resumes.builder()
                .member(member)
                .extractedText("이력서 텍스트")
                .embedding(embedding)
                .resumeSkills(skills)
                .isActive(true)
                .build();
    }

    private PrivateJobPosting createPosting(String title, String category, boolean closed) {
        return PrivateJobPosting.builder()
                .id(postingIdCounter.getAndIncrement())
                .company("testcompany")
                .sourceJobId("job-" + title)
                .title(title)
                .description("설명")
                .jobCategory(category)
                .isClosed(closed)
                .build();
    }

    private float[] dummyEmbedding() {
        return new float[]{0.1f, 0.2f, 0.3f};
    }

    private ScorePrivateResponse dummyScoreResponse(double score) {
        return new ScorePrivateResponse(
                score, true, List.of("Java", "Spring"), List.of("Kubernetes"),
                true, "기술스택 일치", "v1.0"
        );
    }

    @Test
    @DisplayName("이력서가 없으면 점수 계산을 건너뛴다")
    void calculateScores_이력서없음() {
        when(resumesRepository.findById(1L)).thenReturn(Optional.empty());

        service.calculateScores(1L);

        verify(aiScoringClient, never()).scorePrivate(any());
        verify(privateMatchScoreRepository, never()).saveAll(anyList());
    }

    @Test
    @DisplayName("이력서 임베딩이 없으면 점수 계산을 건너뛴다")
    void calculateScores_임베딩없음() {
        Member member = createMember("신입");
        Resumes resume = createResume(member, null, null);
        when(resumesRepository.findById(1L)).thenReturn(Optional.of(resume));

        service.calculateScores(1L);

        verifyNoInteractions(aiScoringClient);
        verify(privateMatchScoreRepository, never()).saveAll(anyList());
    }

    @Test
    @DisplayName("활성 공고가 없으면 점수 계산을 건너뛴다")
    void calculateScores_활성공고없음() {
        Member member = createMember("신입");
        Resumes resume = createResume(member, dummyEmbedding(), "[\"Java\"]");
        when(resumesRepository.findById(1L)).thenReturn(Optional.of(resume));
        when(privateJobPostingRepository.findActiveByValidCategories(anyList())).thenReturn(List.of());

        service.calculateScores(1L);

        verify(privateMatchScoreRepository, never()).saveAll(anyList());
        verify(aiScoringClient, never()).scorePrivate(any());
    }

    @Test
    @DisplayName("마감된 공고는 DB 쿼리 수준에서 제외된다")
    void calculateScores_마감공고제외() {
        Member member = createMember("신입");
        Resumes resume = createResume(member, dummyEmbedding(), "[\"Java\"]");
        when(resumesRepository.findById(1L)).thenReturn(Optional.of(resume));
        // findActiveByValidCategories는 DB 쿼리에서 마감 공고를 제외하므로 빈 리스트 반환
        when(privateJobPostingRepository.findActiveByValidCategories(anyList())).thenReturn(List.of());

        service.calculateScores(1L);

        verify(privateMatchScoreRepository, never()).saveAll(anyList());
        verify(aiScoringClient, never()).scorePrivate(any());
    }

    @Test
    @DisplayName("미분류/미대상 카테고리의 공고는 DB 쿼리 수준에서 제외된다")
    void calculateScores_미분류미대상_제외() {
        Member member = createMember("신입");
        Resumes resume = createResume(member, dummyEmbedding(), "[\"Java\"]");
        when(resumesRepository.findById(1L)).thenReturn(Optional.of(resume));
        // findActiveByValidCategories는 유효 카테고리만 조회하므로 미분류/미대상은 제외
        when(privateJobPostingRepository.findActiveByValidCategories(anyList())).thenReturn(List.of());

        service.calculateScores(1L);

        verify(privateMatchScoreRepository, never()).saveAll(anyList());
        verify(aiScoringClient, never()).scorePrivate(any());
    }

    @Test
    @DisplayName("정상적으로 매칭 점수를 계산하고 저장한다")
    void calculateScores_정상계산() {
        Member member = createMember("경력직");
        Resumes resume = createResume(member, dummyEmbedding(), "[\"Java\",\"Spring\"]");
        when(resumesRepository.findById(1L)).thenReturn(Optional.of(resume));

        PrivateJobPosting posting = createPosting("백엔드 개발자", "백엔드", false);
        when(privateJobPostingRepository.findActiveByValidCategories(anyList())).thenReturn(List.of(posting));

        float[] jdVec = new float[]{0.4f, 0.5f, 0.6f};
        JobEmbedding jobEmbedding = JobEmbedding.builder()
                .source(JobSource.PRIVATE)
                .sourceId(posting.getId())
                .embedding(jdVec)
                .embeddingText("백엔드 개발자\n설명")
                .build();
        when(jobEmbeddingRepository.findBySourceAndSourceId(JobSource.PRIVATE, posting.getId()))
                .thenReturn(Optional.of(jobEmbedding));

        when(aiScoringClient.scorePrivate(any()))
                .thenReturn(Mono.just(dummyScoreResponse(85.5)));

        service.calculateScores(1L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<PrivateMatchScore>> captor = ArgumentCaptor.forClass(List.class);
        verify(privateMatchScoreRepository).saveAll(captor.capture());
        List<PrivateMatchScore> saved = captor.getValue();
        assertThat(saved).hasSize(1);
        assertThat(saved.get(0).getScore()).isEqualTo(86); // Math.round(85.5)
        assertThat(saved.get(0).getScoreReason()).isEqualTo("기술스택 일치");
        assertThat(saved.get(0).getCareerMet()).isTrue();
        assertThat(saved.get(0).getModelVersion()).isEqualTo("v1.0");
    }

    @Test
    @DisplayName("공고 임베딩이 없으면 새로 생성한다")
    void calculateScores_임베딩생성() {
        Member member = createMember("신입");
        Resumes resume = createResume(member, dummyEmbedding(), "[\"Python\"]");
        when(resumesRepository.findById(1L)).thenReturn(Optional.of(resume));

        PrivateJobPosting posting = createPosting("ML 엔지니어", "AI/ML", false);
        when(privateJobPostingRepository.findActiveByValidCategories(anyList())).thenReturn(List.of(posting));

        float[] jdVec = new float[]{0.7f, 0.8f};
        JobEmbedding created = JobEmbedding.builder()
                .source(JobSource.PRIVATE)
                .sourceId(posting.getId())
                .embedding(jdVec)
                .embeddingText("ML 엔지니어\n설명")
                .build();
        when(jobEmbeddingRepository.findBySourceAndSourceId(JobSource.PRIVATE, posting.getId()))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(created));

        when(aiScoringClient.scorePrivate(any()))
                .thenReturn(Mono.just(dummyScoreResponse(72.0)));

        service.calculateScores(1L);

        verify(embeddingService).embedPrivatePosting(posting);
        verify(privateMatchScoreRepository).saveAll(anyList());
    }

    @Test
    @DisplayName("공고 임베딩 생성 실패 시 해당 공고를 건너뛰고 계속한다")
    void calculateScores_임베딩생성실패_계속진행() {
        Member member = createMember("신입");
        Resumes resume = createResume(member, dummyEmbedding(), "[]");
        when(resumesRepository.findById(1L)).thenReturn(Optional.of(resume));

        PrivateJobPosting posting1 = createPosting("공고1", "프론트엔드", false);
        PrivateJobPosting posting2 = createPosting("공고2", "백엔드", false);
        when(privateJobPostingRepository.findActiveByValidCategories(anyList())).thenReturn(List.of(posting1, posting2));

        // posting1: 임베딩 생성 실패
        when(jobEmbeddingRepository.findBySourceAndSourceId(JobSource.PRIVATE, posting1.getId()))
                .thenReturn(Optional.empty());
        doThrow(new RuntimeException("AI 서버 오류"))
                .when(embeddingService).embedPrivatePosting(posting1);

        // posting2: 정상
        JobEmbedding embed2 = JobEmbedding.builder()
                .source(JobSource.PRIVATE).sourceId(posting2.getId())
                .embedding(new float[]{0.1f}).embeddingText("text").build();
        when(jobEmbeddingRepository.findBySourceAndSourceId(JobSource.PRIVATE, posting2.getId()))
                .thenReturn(Optional.of(embed2));
        when(aiScoringClient.scorePrivate(any()))
                .thenReturn(Mono.just(dummyScoreResponse(90.0)));

        service.calculateScores(1L);

        // posting1 실패해도 posting2는 정상 저장
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<PrivateMatchScore>> captor = ArgumentCaptor.forClass(List.class);
        verify(privateMatchScoreRepository).saveAll(captor.capture());
        assertThat(captor.getValue()).hasSize(1);
    }

    @Test
    @DisplayName("AI 스코어링 호출 실패 시 해당 공고를 건너뛰고 계속한다")
    void calculateScores_스코어링실패_계속진행() {
        Member member = createMember("신입");
        Resumes resume = createResume(member, dummyEmbedding(), "[]");
        when(resumesRepository.findById(1L)).thenReturn(Optional.of(resume));

        PrivateJobPosting posting = createPosting("공고", "백엔드", false);
        when(privateJobPostingRepository.findActiveByValidCategories(anyList())).thenReturn(List.of(posting));

        PrivateMatchScore existingScore = PrivateMatchScore.builder()
                .member(member)
                .resume(resume)
                .privateJobPosting(posting)
                .score(70)
                .build();
        when(privateMatchScoreRepository.findByResumeId(1L)).thenReturn(List.of(existingScore));

        JobEmbedding embed = JobEmbedding.builder()
                .source(JobSource.PRIVATE).sourceId(posting.getId())
                .embedding(new float[]{0.1f}).embeddingText("text").build();
        when(jobEmbeddingRepository.findBySourceAndSourceId(JobSource.PRIVATE, posting.getId()))
                .thenReturn(Optional.of(embed));
        when(aiScoringClient.scorePrivate(any()))
                .thenThrow(new RuntimeException("AI 서버 타임아웃"));

        service.calculateScores(1L);

        verify(privateMatchScoreRepository, never()).saveAll(anyList());
        verify(privateMatchScoreRepository, never()).deleteAllById(anyList());
    }

    @Test
    @DisplayName("이력서에 경력 연수가 있으면 experienceYears가 해당 값으로 설정된다")
    void calculateScores_경력회원_연수3() {
        Member member = createMember("경력직");
        Resumes resume = Resumes.builder()
                .member(member)
                .extractedText("이력서 텍스트")
                .embedding(dummyEmbedding())
                .resumeSkills("[\"Java\"]")
                .experienceYears(3)
                .isActive(true)
                .build();
        when(resumesRepository.findById(1L)).thenReturn(Optional.of(resume));

        PrivateJobPosting posting = createPosting("개발자", "백엔드", false);
        when(privateJobPostingRepository.findActiveByValidCategories(anyList())).thenReturn(List.of(posting));

        JobEmbedding embed = JobEmbedding.builder()
                .source(JobSource.PRIVATE).sourceId(posting.getId())
                .embedding(new float[]{0.1f}).embeddingText("text").build();
        when(jobEmbeddingRepository.findBySourceAndSourceId(JobSource.PRIVATE, posting.getId()))
                .thenReturn(Optional.of(embed));
        when(aiScoringClient.scorePrivate(any()))
                .thenReturn(Mono.just(dummyScoreResponse(80.0)));

        service.calculateScores(1L);

        ArgumentCaptor<ScorePrivateRequest> captor =
                ArgumentCaptor.forClass(ScorePrivateRequest.class);
        verify(aiScoringClient).scorePrivate(captor.capture());
        assertThat(captor.getValue().experienceYears()).isEqualTo(3);
    }

    @Test
    @DisplayName("이력서에 경력 연수가 없으면 experienceYears가 0으로 설정된다")
    void calculateScores_신입회원_연수0() {
        Member member = createMember("신입");
        Resumes resume = createResume(member, dummyEmbedding(), "[\"Python\"]");
        when(resumesRepository.findById(1L)).thenReturn(Optional.of(resume));

        PrivateJobPosting posting = createPosting("개발자", "프론트엔드", false);
        when(privateJobPostingRepository.findActiveByValidCategories(anyList())).thenReturn(List.of(posting));

        JobEmbedding embed = JobEmbedding.builder()
                .source(JobSource.PRIVATE).sourceId(posting.getId())
                .embedding(new float[]{0.1f}).embeddingText("text").build();
        when(jobEmbeddingRepository.findBySourceAndSourceId(JobSource.PRIVATE, posting.getId()))
                .thenReturn(Optional.of(embed));
        when(aiScoringClient.scorePrivate(any()))
                .thenReturn(Mono.just(dummyScoreResponse(70.0)));

        service.calculateScores(1L);

        ArgumentCaptor<ScorePrivateRequest> captor =
                ArgumentCaptor.forClass(ScorePrivateRequest.class);
        verify(aiScoringClient).scorePrivate(captor.capture());
        assertThat(captor.getValue().experienceYears()).isEqualTo(0);
    }

    @Test
    @DisplayName("resumeSkills가 null이면 빈 리스트로 전달된다")
    void calculateScores_스킬null_빈리스트() {
        Member member = createMember("신입");
        Resumes resume = createResume(member, dummyEmbedding(), null);
        when(resumesRepository.findById(1L)).thenReturn(Optional.of(resume));

        PrivateJobPosting posting = createPosting("개발자", "백엔드", false);
        when(privateJobPostingRepository.findActiveByValidCategories(anyList())).thenReturn(List.of(posting));

        JobEmbedding embed = JobEmbedding.builder()
                .source(JobSource.PRIVATE).sourceId(posting.getId())
                .embedding(new float[]{0.1f}).embeddingText("text").build();
        when(jobEmbeddingRepository.findBySourceAndSourceId(JobSource.PRIVATE, posting.getId()))
                .thenReturn(Optional.of(embed));
        when(aiScoringClient.scorePrivate(any()))
                .thenReturn(Mono.just(dummyScoreResponse(60.0)));

        service.calculateScores(1L);

        ArgumentCaptor<ScorePrivateRequest> captor =
                ArgumentCaptor.forClass(ScorePrivateRequest.class);
        verify(aiScoringClient).scorePrivate(captor.capture());
        assertThat(captor.getValue().resumeSkills()).isEmpty();
    }

    @Test
    @DisplayName("여러 공고에 대해 각각 점수를 계산하고 일괄 저장한다")
    void calculateScores_여러공고_각각저장() {
        Member member = createMember("신입");
        Resumes resume = createResume(member, dummyEmbedding(), "[\"Java\"]");
        when(resumesRepository.findById(1L)).thenReturn(Optional.of(resume));

        PrivateJobPosting posting1 = createPosting("공고A", "백엔드", false);
        PrivateJobPosting posting2 = createPosting("공고B", "프론트엔드", false);
        when(privateJobPostingRepository.findActiveByValidCategories(anyList())).thenReturn(List.of(posting1, posting2));

        JobEmbedding embed1 = JobEmbedding.builder()
                .source(JobSource.PRIVATE).sourceId(posting1.getId())
                .embedding(new float[]{0.1f}).embeddingText("text1").build();
        JobEmbedding embed2 = JobEmbedding.builder()
                .source(JobSource.PRIVATE).sourceId(posting2.getId())
                .embedding(new float[]{0.2f}).embeddingText("text2").build();

        when(jobEmbeddingRepository.findBySourceAndSourceId(JobSource.PRIVATE, posting1.getId()))
                .thenReturn(Optional.of(embed1));
        when(jobEmbeddingRepository.findBySourceAndSourceId(JobSource.PRIVATE, posting2.getId()))
                .thenReturn(Optional.of(embed2));

        when(aiScoringClient.scorePrivate(any()))
                .thenReturn(Mono.just(dummyScoreResponse(85.0)))
                .thenReturn(Mono.just(dummyScoreResponse(72.0)));

        service.calculateScores(1L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<PrivateMatchScore>> captor = ArgumentCaptor.forClass(List.class);
        verify(privateMatchScoreRepository).saveAll(captor.capture());
        assertThat(captor.getValue()).hasSize(2);
    }

    @Test
    @DisplayName("기존 점수는 AI 응답 성공 후 새 결과로 교체한다")
    void calculateScores_기존점수_성공후교체() {
        Member member = createMember("신입");
        Resumes resume = createResume(member, dummyEmbedding(), "[]");
        when(resumesRepository.findById(1L)).thenReturn(Optional.of(resume));

        PrivateJobPosting posting = createPosting("공고", "백엔드", false);
        when(privateJobPostingRepository.findActiveByValidCategories(anyList())).thenReturn(List.of(posting));

        PrivateMatchScore existingScore = PrivateMatchScore.builder()
                .id(10L)
                .member(member)
                .resume(resume)
                .privateJobPosting(posting)
                .score(70)
                .build();
        when(privateMatchScoreRepository.findByResumeId(1L)).thenReturn(List.of(existingScore));

        JobEmbedding embed = JobEmbedding.builder()
                .source(JobSource.PRIVATE).sourceId(posting.getId())
                .embedding(new float[]{0.1f}).embeddingText("text").build();
        when(jobEmbeddingRepository.findBySourceAndSourceId(JobSource.PRIVATE, posting.getId()))
                .thenReturn(Optional.of(embed));
        when(aiScoringClient.scorePrivate(any()))
                .thenReturn(Mono.just(dummyScoreResponse(80.0)));

        service.calculateScores(1L);

        var inOrder = inOrder(privateMatchScoreRepository);
        inOrder.verify(privateMatchScoreRepository).findByResumeId(1L);
        inOrder.verify(privateMatchScoreRepository).deleteAllById(List.of(10L));
        inOrder.verify(privateMatchScoreRepository).flush();
        inOrder.verify(privateMatchScoreRepository).saveAll(anyList());
    }

    @Test
    @DisplayName("일부 공고 재계산 실패 시 실패 공고의 기존 점수는 보존한다")
    void preservesExistingScoreWhenPrivateRecalculationPartiallyFails() {
        Member member = createMember("신입");
        Resumes resume = createResume(member, dummyEmbedding(), "[]");
        when(resumesRepository.findById(1L)).thenReturn(Optional.of(resume));

        PrivateJobPosting failedPosting = createPosting("실패 공고", "백엔드", false);
        PrivateJobPosting successfulPosting = createPosting("성공 공고", "백엔드", false);
        when(privateJobPostingRepository.findActiveByValidCategories(anyList())).thenReturn(List.of(failedPosting, successfulPosting));

        PrivateMatchScore failedExisting = PrivateMatchScore.builder()
                .id(20L)
                .member(member).resume(resume).privateJobPosting(failedPosting).score(81).build();
        PrivateMatchScore successfulExisting = PrivateMatchScore.builder()
                .id(21L)
                .member(member).resume(resume).privateJobPosting(successfulPosting).score(72).build();
        when(privateMatchScoreRepository.findByResumeId(1L))
                .thenReturn(List.of(failedExisting, successfulExisting));

        JobEmbedding failedEmbedding = JobEmbedding.builder()
                .source(JobSource.PRIVATE).sourceId(failedPosting.getId())
                .embedding(new float[]{0.1f}).embeddingText("failed").build();
        JobEmbedding successfulEmbedding = JobEmbedding.builder()
                .source(JobSource.PRIVATE).sourceId(successfulPosting.getId())
                .embedding(new float[]{0.2f}).embeddingText("successful").build();
        when(jobEmbeddingRepository.findBySourceAndSourceId(JobSource.PRIVATE, failedPosting.getId()))
                .thenReturn(Optional.of(failedEmbedding));
        when(jobEmbeddingRepository.findBySourceAndSourceId(JobSource.PRIVATE, successfulPosting.getId()))
                .thenReturn(Optional.of(successfulEmbedding));
        when(aiScoringClient.scorePrivate(any()))
                .thenThrow(new RuntimeException("AI 서버 타임아웃"))
                .thenReturn(Mono.just(dummyScoreResponse(90.0)));

        service.calculateScores(1L);

        // 실패한 공고의 기존 점수(id=20)는 삭제 대상에 포함되지 않음
        // 성공한 공고의 기존 점수(id=21)만 삭제 후 새 점수로 교체
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Iterable<Long>> deleteCaptor = ArgumentCaptor.forClass(Iterable.class);
        verify(privateMatchScoreRepository).deleteAllById(deleteCaptor.capture());
        assertThat(deleteCaptor.getValue()).containsExactly(21L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<PrivateMatchScore>> saveCaptor = ArgumentCaptor.forClass(List.class);
        verify(privateMatchScoreRepository).saveAll(saveCaptor.capture());
        assertThat(saveCaptor.getValue()).hasSize(1);
        assertThat(saveCaptor.getValue().get(0).getPrivateJobPosting()).isEqualTo(successfulPosting);
        assertThat(saveCaptor.getValue().get(0).getScore()).isEqualTo(90);
    }
}
