package com.jobai.backend.domain.matching.event;

import com.jobai.backend.domain.matching.service.PrivateMatchingService;
import com.jobai.backend.domain.matching.service.PublicMatchingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Slf4j
@Component
@RequiredArgsConstructor
public class ResumeScoreCalculationEventListener {

    private final PrivateMatchingService privateMatchingService;
    private final PublicMatchingService publicMatchingService;

    @Async("schedulerTaskExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void calculateScores(ResumeScoreCalculationRequestedEvent event) {
        calculatePrivateScores(event.resumeId());
        calculatePublicScores(event.resumeId());
    }

    private void calculatePrivateScores(Long resumeId) {
        try {
            privateMatchingService.calculateScores(resumeId);
        } catch (Exception e) {
            log.error("Private match score calculation failed: resumeId={}", resumeId, e);
        }
    }

    private void calculatePublicScores(Long resumeId) {
        try {
            publicMatchingService.calculateScores(resumeId);
        } catch (Exception e) {
            log.error("Public match score calculation failed: resumeId={}", resumeId, e);
        }
    }
}
