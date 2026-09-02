package com.jobai.backend.domain.matching.event;

import com.jobai.backend.domain.matching.service.PrivateMatchingService;
import com.jobai.backend.domain.matching.service.PublicMatchingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.batch.support.transaction.ResourcelessTransactionManager;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@SpringJUnitConfig(ResumeScoreCalculationTransactionIntegrationTest.TestConfig.class)
class ResumeScoreCalculationTransactionIntegrationTest {

    @jakarta.annotation.Resource
    private ApplicationEventPublisher eventPublisher;

    @jakarta.annotation.Resource
    private PlatformTransactionManager transactionManager;

    @jakarta.annotation.Resource
    private PrivateMatchingService privateMatchingService;

    @jakarta.annotation.Resource
    private PublicMatchingService publicMatchingService;

    @BeforeEach
    void resetMocks() {
        reset(privateMatchingService, publicMatchingService);
    }

    @Test
    @DisplayName("점수 계산 이벤트는 트랜잭션 안에서는 실행되지 않고 커밋 후 실행된다")
    void handlesEventOnlyAfterCommit() {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        transaction.executeWithoutResult(status -> {
            eventPublisher.publishEvent(new ResumeScoreCalculationRequestedEvent(10L));
            verifyNoInteractions(privateMatchingService, publicMatchingService);
        });

        verify(privateMatchingService).calculateScores(10L);
        verify(publicMatchingService).calculateScores(10L);
    }

    @Test
    @DisplayName("이력서 트랜잭션이 롤백되면 점수 계산 이벤트를 실행하지 않는다")
    void ignoresEventAfterRollback() {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        transaction.executeWithoutResult(status -> {
            eventPublisher.publishEvent(new ResumeScoreCalculationRequestedEvent(10L));
            status.setRollbackOnly();
        });

        verifyNoInteractions(privateMatchingService, publicMatchingService);
    }

    @Configuration
    @EnableAsync
    @EnableTransactionManagement
    static class TestConfig {

        @Bean
        PlatformTransactionManager transactionManager() {
            return new ResourcelessTransactionManager();
        }

        @Bean
        TaskExecutor schedulerTaskExecutor() {
            return new SyncTaskExecutor();
        }

        @Bean
        PrivateMatchingService privateMatchingService() {
            return Mockito.mock(PrivateMatchingService.class);
        }

        @Bean
        PublicMatchingService publicMatchingService() {
            return Mockito.mock(PublicMatchingService.class);
        }

        @Bean
        ResumeScoreCalculationEventListener listener(
                PrivateMatchingService privateMatchingService,
                PublicMatchingService publicMatchingService
        ) {
            return new ResumeScoreCalculationEventListener(privateMatchingService, publicMatchingService);
        }
    }
}
