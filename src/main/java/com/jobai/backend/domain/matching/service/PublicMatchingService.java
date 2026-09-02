package com.jobai.backend.domain.matching.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobai.backend.global.ai.client.AiScoringClient;
import com.jobai.backend.global.ai.dto.ScorePublicRequest;
import com.jobai.backend.global.ai.dto.ScorePublicResponse;
import com.jobai.backend.domain.matching.entity.PublicMatchScore;
import com.jobai.backend.domain.matching.repository.PublicMatchScoreRepository;
import com.jobai.backend.domain.member.entity.Resumes;
import com.jobai.backend.domain.member.repository.ResumesRepository;
import com.jobai.backend.domain.publicInstitution.entity.PublicJobPosting;
import com.jobai.backend.domain.publicInstitution.repository.JobPostingRepository;
import com.jobai.backend.domain.search.entity.JobEmbedding;
import com.jobai.backend.global.enums.JobSource;
import com.jobai.backend.domain.search.repository.JobEmbeddingRepository;
import com.jobai.backend.domain.search.service.EmbeddingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 이력서 업로드 시 모든 활성 공기업 공고에 대해
 * AI 서버 /score/public(NCS)를 호출하여 매칭 점수를 계산하고 저장하는 서비스.
 *
 * <p>자격증(certs)은 이력서에서 아직 파싱하지 않아 항상 빈 값으로 전달한다.
 * 이력서의 job_role도 별도 입력이 없어 비워두고, 대신 추출된 이력서 원문을
 * summary로 함께 보내 스킬/본문 텍스트 기반으로 NCS 직무 클러스터가 분류되도록 한다.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PublicMatchingService {

    private static final int MAX_SUMMARY_LENGTH = 4000;

    private final AiScoringClient aiScoringClient;
    private final JobPostingRepository jobPostingRepository;
    private final JobEmbeddingRepository jobEmbeddingRepository;
    private final EmbeddingService embeddingService;
    private final PublicMatchScoreRepository publicMatchScoreRepository;
    private final ResumesRepository resumesRepository;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    /**
     * 비동기로 매칭 점수를 계산한다.
     * 이력서 업로드 트랜잭션 커밋 후 별도 스레드에서 실행된다.
     */
    public void calculateScores(Long resumeId) {
        // 읽기 단계: 짧은 readOnly 트랜잭션으로 필요한 데이터 조회
        record ReadResult(
                Resumes resume,
                List<PublicJobPosting> activePostings,
                Map<Long, PublicMatchScore> existingScores,
                ScorePublicRequest.ResumePayload resumePayload,
                List<Double> resumeVec
        ) {}

        TransactionTemplate readOnlyTx = new TransactionTemplate(transactionTemplate.getTransactionManager());
        readOnlyTx.setReadOnly(true);

        ReadResult readResult = readOnlyTx.execute(status -> {
            Resumes resume = resumesRepository.findById(resumeId).orElse(null);
            if (resume == null) {
                log.warn("이력서를 찾을 수 없음: resumeId={}", resumeId);
                return null;
            }
            if (resume.getNcsEmbedding() == null) {
                log.warn("이력서 NCS 임베딩이 없어 공기업 점수 계산 불가: resumeId={}", resumeId);
                return null;
            }

            List<PublicJobPosting> activePostings = jobPostingRepository.findActivePublicPostings();
            if (activePostings.isEmpty()) {
                log.info("활성 공기업 공고가 없어 점수 계산 건너뜀: resumeId={}", resumeId);
                return null;
            }

            Map<Long, PublicMatchScore> existingScores = publicMatchScoreRepository.findByResumeId(resumeId).stream()
                    .collect(Collectors.toMap(score -> score.getPublicJobPosting().getId(), score -> score));

            return new ReadResult(
                    resume,
                    activePostings,
                    existingScores,
                    buildResumePayload(resume),
                    toDoubleList(resume.getNcsEmbedding())
            );
        });

        if (readResult == null) return;

        // AI 호출 단계: 트랜잭션 밖에서 루프 돌며 점수 계산
        List<PublicMatchScore> toSave = new ArrayList<>();
        List<Long> toDeleteIds = new ArrayList<>();
        int successCount = 0;
        int failCount = 0;

        for (PublicJobPosting posting : readResult.activePostings()) {
            try {
                JobEmbeddingData jdData = getOrCreateJobEmbedding(posting);
                if (jdData == null) {
                    failCount++;
                    continue;
                }

                ScorePublicRequest request = buildRequest(posting, jdData, readResult.resumePayload(), readResult.resumeVec());

                ScorePublicResponse response = aiScoringClient.scorePublic(request).block();
                if (response == null) {
                    failCount++;
                    continue;
                }

                PublicMatchScore existing = readResult.existingScores().get(posting.getId());
                if (existing != null) {
                    toDeleteIds.add(existing.getId());
                }

                PublicMatchScore replacement = PublicMatchScore.builder()
                        .member(readResult.resume().getMember())
                        .resume(readResult.resume())
                        .publicJobPosting(posting)
                        .score((int) Math.round(response.score()))
                        .scoreReason(response.scoreReason())
                        .matchedSkills(toJson(response.matchedSkills()))
                        .missingSkills(toJson(response.missingSkills()))
                        .matchedCerts(toJson(response.matchedCerts()))
                        .missingCerts(toJson(response.missingCerts()))
                        .jobCluster(response.jobCluster())
                        .resumeCluster(response.resumeCluster())
                        .build();
                toSave.add(replacement);
                successCount++;
            } catch (Exception e) {
                log.warn("공고 점수 계산 실패: postingId={}, error={}", posting.getId(), e.getMessage());
                failCount++;
            }
        }

        // 저장 단계: 짧은 트랜잭션에서 일괄 저장
        if (!toSave.isEmpty() || !toDeleteIds.isEmpty()) {
            transactionTemplate.executeWithoutResult(status -> {
                if (!toDeleteIds.isEmpty()) {
                    publicMatchScoreRepository.deleteAllById(toDeleteIds);
                    publicMatchScoreRepository.flush();
                }
                publicMatchScoreRepository.saveAll(toSave);
            });
        }

        log.info("공기업 매칭 점수 계산 결과: resumeId={}, 성공={}, 실패={}", resumeId, successCount, failCount);
    }

    private ScorePublicRequest buildRequest(
            PublicJobPosting posting, JobEmbeddingData jdData,
            ScorePublicRequest.ResumePayload resumePayload, List<Double> resumeVec
    ) {
        return new ScorePublicRequest(
                posting.getId(),
                posting.getTitle(),
                posting.getCompanyName(),
                posting.getJobRole(),
                posting.getWorkExperience(),
                posting.getRecrutType(),
                posting.getApplyQualification(),
                posting.getApplicationMethod(),
                posting.getHtmlContent(),
                jdData.text(),
                resumePayload,
                toDoubleList(jdData.vector()),
                resumeVec
        );
    }

    private ScorePublicRequest.ResumePayload buildResumePayload(Resumes resume) {
        List<String> resumeSkills = parseSkills(resume.getResumeSkills());
        int experienceYears = resolveExperienceYears(resume);
        String summary = resume.getExtractedText() != null && resume.getExtractedText().length() > MAX_SUMMARY_LENGTH
                ? resume.getExtractedText().substring(0, MAX_SUMMARY_LENGTH)
                : resume.getExtractedText();

        return new ScorePublicRequest.ResumePayload(
                resumeSkills,
                List.of(),
                experienceYears,
                "",
                summary
        );
    }

    private record JobEmbeddingData(float[] vector, String text) {
    }

    private JobEmbeddingData getOrCreateJobEmbedding(PublicJobPosting posting) {
        Optional<JobEmbedding> existing = jobEmbeddingRepository
                .findBySourceAndSourceId(JobSource.PUBLIC, posting.getId());
        if (existing.isPresent()) {
            JobEmbedding je = existing.get();
            return new JobEmbeddingData(je.getEmbedding(), je.getEmbeddingText());
        }

        try {
            embeddingService.embedPublicPosting(posting);
            return jobEmbeddingRepository
                    .findBySourceAndSourceId(JobSource.PUBLIC, posting.getId())
                    .map(je -> new JobEmbeddingData(je.getEmbedding(), je.getEmbeddingText()))
                    .orElse(null);
        } catch (Exception e) {
            log.warn("공고 임베딩 생성 실패: postingId={}, error={}", posting.getId(), e.getMessage());
            return null;
        }
    }

    private int resolveExperienceYears(Resumes resume) {
        return resume.getExperienceYears() != null ? resume.getExperienceYears() : 0;
    }

    private List<String> parseSkills(String skillsJson) {
        if (skillsJson == null || skillsJson.isBlank()) return List.of();
        try {
            return objectMapper.readValue(skillsJson, new TypeReference<List<String>>() {});
        } catch (JsonProcessingException e) {
            return List.of();
        }
    }

    private List<Double> toDoubleList(float[] floats) {
        List<Double> list = new ArrayList<>(floats.length);
        for (float f : floats) {
            list.add((double) f);
        }
        return list;
    }

    private String toJson(List<String> list) {
        if (list == null) return "[]";
        try {
            return objectMapper.writeValueAsString(list);
        } catch (JsonProcessingException e) {
            return "[]";
        }
    }
}
