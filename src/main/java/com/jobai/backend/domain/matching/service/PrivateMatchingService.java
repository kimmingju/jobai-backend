package com.jobai.backend.domain.matching.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobai.backend.global.ai.client.AiScoringClient;
import com.jobai.backend.global.ai.dto.ScorePrivateRequest;
import com.jobai.backend.global.ai.dto.ScorePrivateResponse;
import com.jobai.backend.domain.privatejobposting.entity.PrivateJobPosting;
import com.jobai.backend.domain.privatejobposting.repository.PrivateJobPostingRepository;
import com.jobai.backend.domain.matching.entity.PrivateMatchScore;
import com.jobai.backend.domain.matching.repository.PrivateMatchScoreRepository;
import com.jobai.backend.domain.member.entity.Resumes;
import com.jobai.backend.domain.member.repository.ResumesRepository;
import com.jobai.backend.domain.search.entity.JobEmbedding;
import com.jobai.backend.global.enums.JobSource;
import com.jobai.backend.global.enums.JobCategory;
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
 * 이력서 업로드 시 모든 활성 사기업 공고에 대해
 * AI 서버 /score/private를 호출하여 매칭 점수를 계산하고 저장하는 서비스.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PrivateMatchingService {

    private final AiScoringClient aiScoringClient;
    private final PrivateJobPostingRepository privateJobPostingRepository;
    private final JobEmbeddingRepository jobEmbeddingRepository;
    private final EmbeddingService embeddingService;
    private final PrivateMatchScoreRepository privateMatchScoreRepository;
    private final ResumesRepository resumesRepository;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    public void calculateScores(Long resumeId) {
        // 읽기 단계: 짧은 readOnly 트랜잭션으로 필요한 데이터 조회
        record ReadResult(
                Resumes resume,
                List<PrivateJobPosting> activePostings,
                Map<Long, PrivateMatchScore> existingScores,
                List<String> resumeSkills,
                List<Double> resumeVec,
                int experienceYears
        ) {}

        TransactionTemplate readOnlyTx = new TransactionTemplate(transactionTemplate.getTransactionManager());
        readOnlyTx.setReadOnly(true);

        ReadResult readResult = readOnlyTx.execute(status -> {
            Resumes resume = resumesRepository.findById(resumeId).orElse(null);
            if (resume == null) {
                log.warn("이력서를 찾을 수 없음: resumeId={}", resumeId);
                return null;
            }
            if (resume.getEmbedding() == null) {
                log.warn("이력서 임베딩이 없어 점수 계산 불가: resumeId={}", resumeId);
                return null;
            }

            List<PrivateJobPosting> activePostings = findActivePostings();
            if (activePostings.isEmpty()) {
                log.info("활성 공고가 없어 점수 계산 건너뜀: resumeId={}", resumeId);
                return null;
            }

            Map<Long, PrivateMatchScore> existingScores = privateMatchScoreRepository.findByResumeId(resumeId).stream()
                    .collect(Collectors.toMap(score -> score.getPrivateJobPosting().getId(), score -> score));

            return new ReadResult(
                    resume,
                    activePostings,
                    existingScores,
                    parseSkills(resume.getResumeSkills()),
                    toDoubleList(resume.getEmbedding()),
                    resolveExperienceYears(resume)
            );
        });

        if (readResult == null) return;

        // AI 호출 단계: 트랜잭션 밖에서 루프 돌며 점수 계산
        List<PrivateMatchScore> toSave = new ArrayList<>();
        List<Long> toDeleteIds = new ArrayList<>();
        int successCount = 0;
        int failCount = 0;

        for (PrivateJobPosting posting : readResult.activePostings()) {
            try {
                float[] jdVec = getOrCreateJobEmbedding(posting);
                if (jdVec == null) {
                    failCount++;
                    continue;
                }

                String jdText = buildJdText(posting);
                ScorePrivateRequest request = new ScorePrivateRequest(
                        jdText, toDoubleList(jdVec), readResult.resumeVec(),
                        readResult.resumeSkills(), readResult.experienceYears());

                ScorePrivateResponse response = aiScoringClient.scorePrivate(request).block();
                if (response == null) {
                    failCount++;
                    continue;
                }

                PrivateMatchScore existing = readResult.existingScores().get(posting.getId());
                if (existing != null) {
                    toDeleteIds.add(existing.getId());
                }

                PrivateMatchScore replacement = PrivateMatchScore.builder()
                        .member(readResult.resume().getMember())
                        .resume(readResult.resume())
                        .privateJobPosting(posting)
                        .score((int) Math.round(response.score()))
                        .scoreReason(response.scoreReason())
                        .matchedSkills(toJson(response.matchedSkills()))
                        .missingSkills(toJson(response.missingSkills()))
                        .careerMet(response.careerMet())
                        .modelVersion(response.modelVersion())
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
                    privateMatchScoreRepository.deleteAllById(toDeleteIds);
                    privateMatchScoreRepository.flush();
                }
                privateMatchScoreRepository.saveAll(toSave);
            });
        }

        log.info("매칭 점수 계산 결과: resumeId={}, 성공={}, 실패={}", resumeId, successCount, failCount);
    }

    private float[] getOrCreateJobEmbedding(PrivateJobPosting posting) {
        Optional<JobEmbedding> existing = jobEmbeddingRepository
                .findBySourceAndSourceId(JobSource.PRIVATE, posting.getId());
        if (existing.isPresent()) {
            return existing.get().getEmbedding();
        }

        try {
            embeddingService.embedPrivatePosting(posting);
            return jobEmbeddingRepository
                    .findBySourceAndSourceId(JobSource.PRIVATE, posting.getId())
                    .map(JobEmbedding::getEmbedding)
                    .orElse(null);
        } catch (Exception e) {
            log.warn("공고 임베딩 생성 실패: postingId={}, error={}", posting.getId(), e.getMessage());
            return null;
        }
    }

    private List<PrivateJobPosting> findActivePostings() {
        return privateJobPostingRepository.findActiveByValidCategories(JobCategory.matchTargetLabels());
    }

    private String buildJdText(PrivateJobPosting posting) {
        String title = posting.getTitle() != null ? posting.getTitle() : "";
        String desc = posting.getDescription() != null ? posting.getDescription() : "";
        return title + "\n" + desc;
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
