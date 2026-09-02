package com.jobai.backend.domain.member.service;

import com.jobai.backend.domain.member.dto.ResumeResponseDTO;
import com.jobai.backend.domain.member.entity.Member;
import com.jobai.backend.domain.member.entity.Resumes;
import com.jobai.backend.domain.member.repository.MemberRepository;
import com.jobai.backend.domain.member.repository.ResumesRepository;
import com.jobai.backend.global.apiPayload.code.GeneralErrorCode;
import com.jobai.backend.global.apiPayload.exception.GeneralException;
import com.jobai.backend.domain.matching.repository.PrivateMatchScoreRepository;
import com.jobai.backend.domain.matching.repository.PublicMatchScoreRepository;
import com.jobai.backend.domain.matching.event.ResumeScoreCalculationRequestedEvent;
import com.jobai.backend.domain.search.service.EmbeddingService;
import com.jobai.backend.global.storage.FileStorageService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ResumeServiceTest {

    @Mock
    private ResumesRepository resumesRepository;

    @Mock
    private MemberRepository memberRepository;

    @Mock
    private FileStorageService fileStorageService;

    @Mock
    private ResumeParsingService resumeParsingService;

    @Mock
    private EmbeddingService embeddingService;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private PrivateMatchScoreRepository privateMatchScoreRepository;

    @Mock
    private PublicMatchScoreRepository publicMatchScoreRepository;

    private ResumeService resumeService;

    private static final String EMAIL = "test@jobai.com";

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
        org.mockito.Mockito.lenient().when(txManager.getTransaction(any()))
                .thenReturn(mock(org.springframework.transaction.TransactionStatus.class));
        TransactionTemplate transactionTemplate = new TransactionTemplate(txManager);

        resumeService = new ResumeService(
                resumesRepository, memberRepository, fileStorageService,
                resumeParsingService, embeddingService, eventPublisher,
                privateMatchScoreRepository, publicMatchScoreRepository,
                transactionTemplate);
    }

    private Member member(Long id, String email) {
        return Member.builder().id(id).email(email).build();
    }

    // -------------------------------------------------------
    // 조회
    // -------------------------------------------------------

    @Test
    @DisplayName("조회: 이력서 엔티티를 DTO로 매핑한다")
    void getResumes_mapsEntitiesToDto() {
        Resumes resume = Resumes.builder()
                .id(1L)
                .member(member(1L, EMAIL))
                .originalFilename("이력서.pdf")
                .storedFileUrl("https://bucket.s3.ap-northeast-2.amazonaws.com/resumes/1/key.pdf")
                .fileSize("1.2 MB")
                .isActive(true)
                .updatedAt(LocalDate.of(2026, 6, 6))
                .build();
        when(resumesRepository.findByMemberEmailOrderByUpdatedAtDescIdDesc(EMAIL))
                .thenReturn(List.of(resume));

        ResumeResponseDTO.ResumeListDTO result = resumeService.getResumes(EMAIL);

        assertThat(result.getResumes()).hasSize(1);
        ResumeResponseDTO.ResumeItemDTO item = result.getResumes().get(0);
        assertThat(item.getResumeId()).isEqualTo(1L);
        assertThat(item.getOriginalFilename()).isEqualTo("이력서.pdf");
        assertThat(item.getIsActive()).isTrue();
        assertThat(item.getUploadedAt()).isEqualTo(LocalDate.of(2026, 6, 6));
    }

    @Test
    @DisplayName("조회: 이력서가 없으면 빈 리스트를 반환한다")
    void getResumes_empty() {
        when(resumesRepository.findByMemberEmailOrderByUpdatedAtDescIdDesc(EMAIL))
                .thenReturn(List.of());

        ResumeResponseDTO.ResumeListDTO result = resumeService.getResumes(EMAIL);

        assertThat(result.getResumes()).isEmpty();
    }

    // -------------------------------------------------------
    // 업로드
    // -------------------------------------------------------

    @Test
    @DisplayName("업로드: 성공 시 새 이력서를 활성화하고 기존 이력서는 비활성화한다")
    void uploadResume_success_activatesNewAndDeactivatesOthers() {
        Member member = member(1L, EMAIL);
        MockMultipartFile file = new MockMultipartFile(
                "file", "이력서.pdf", "application/pdf", "%PDF-1.4 dummy".getBytes());
        String fileUrl = "https://bucket.s3.ap-northeast-2.amazonaws.com/resumes/1/key.pdf";

        when(memberRepository.findByEmail(EMAIL)).thenReturn(Optional.of(member));
        when(fileStorageService.upload(eq(file), anyString())).thenReturn(fileUrl);

        ArgumentCaptor<Resumes> captor = ArgumentCaptor.forClass(Resumes.class);
        Resumes savedResume = Resumes.builder()
                .id(99L)
                .member(member)
                .originalFilename("이력서.pdf")
                .storedFileUrl(fileUrl)
                .fileSize("14 B")
                .isActive(true)
                .updatedAt(LocalDate.now())
                .build();
        when(resumesRepository.save(captor.capture())).thenReturn(savedResume);
        when(resumesRepository.findById(99L)).thenReturn(Optional.of(savedResume));

        Long resumeId = resumeService.uploadResume(EMAIL, file);

        assertThat(resumeId).isEqualTo(99L);
        Resumes saved = captor.getValue();
        assertThat(saved.getIsActive()).isTrue();
        assertThat(saved.getOriginalFilename()).isEqualTo("이력서.pdf");
        assertThat(saved.getStoredFileUrl()).isEqualTo(fileUrl);
        assertThat(saved.getFileSize()).isEqualTo(file.getSize() + " B");
        verify(resumesRepository).deactivateOthersByMemberId(1L, 99L);
        ArgumentCaptor<ResumeScoreCalculationRequestedEvent> eventCaptor =
                ArgumentCaptor.forClass(ResumeScoreCalculationRequestedEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue().resumeId()).isEqualTo(99L);
    }

    @Test
    @DisplayName("업로드: PDF 시그니처가 아니면 400 예외를 던지고 S3 업로드를 시도하지 않는다")
    void uploadResume_invalidSignature_throwsBadRequest() {
        when(memberRepository.findByEmail(EMAIL)).thenReturn(Optional.of(member(1L, EMAIL)));
        MockMultipartFile file = new MockMultipartFile(
                "file", "fake.pdf", "application/pdf", "이것은 PDF가 아닙니다".getBytes());

        assertThatThrownBy(() -> resumeService.uploadResume(EMAIL, file))
                .isInstanceOf(GeneralException.class)
                .satisfies(ex -> assertThat(((GeneralException) ex).getErrorCode())
                        .isEqualTo(GeneralErrorCode.BAD_REQUEST));

        verifyNoInteractions(fileStorageService);
        verify(resumesRepository, never()).save(any());
    }

    @Test
    @DisplayName("업로드: 빈 파일이면 400 예외를 던진다")
    void uploadResume_emptyFile_throwsBadRequest() {
        when(memberRepository.findByEmail(EMAIL)).thenReturn(Optional.of(member(1L, EMAIL)));
        MockMultipartFile file = new MockMultipartFile("file", "empty.pdf", "application/pdf", new byte[0]);

        assertThatThrownBy(() -> resumeService.uploadResume(EMAIL, file))
                .isInstanceOf(GeneralException.class)
                .satisfies(ex -> assertThat(((GeneralException) ex).getErrorCode())
                        .isEqualTo(GeneralErrorCode.BAD_REQUEST));

        verifyNoInteractions(fileStorageService);
    }

    @Test
    @DisplayName("업로드: 존재하지 않는 회원이면 MEMBER_NOT_FOUND 예외를 던진다")
    void uploadResume_memberNotFound_throws() {
        when(memberRepository.findByEmail(EMAIL)).thenReturn(Optional.empty());
        MockMultipartFile file = new MockMultipartFile(
                "file", "이력서.pdf", "application/pdf", "%PDF-1.4".getBytes());

        assertThatThrownBy(() -> resumeService.uploadResume(EMAIL, file))
                .isInstanceOf(GeneralException.class)
                .satisfies(ex -> assertThat(((GeneralException) ex).getErrorCode())
                        .isEqualTo(GeneralErrorCode.MEMBER_NOT_FOUND));

        verifyNoInteractions(fileStorageService);
        verifyNoInteractions(resumesRepository);
    }

    @Test
    @DisplayName("업로드: DB 저장 실패 시 S3에 업로드된 파일을 삭제한다 (보상 트랜잭션)")
    void uploadResume_dbSaveFails_deletesUploadedFile() {
        when(memberRepository.findByEmail(EMAIL)).thenReturn(Optional.of(member(1L, EMAIL)));
        MockMultipartFile file = new MockMultipartFile(
                "file", "이력서.pdf", "application/pdf", "%PDF-1.4".getBytes());
        String fileUrl = "https://bucket.s3.ap-northeast-2.amazonaws.com/resumes/1/key.pdf";

        when(fileStorageService.upload(eq(file), anyString())).thenReturn(fileUrl);
        when(resumesRepository.save(any(Resumes.class))).thenThrow(new RuntimeException("DB down"));

        assertThatThrownBy(() -> resumeService.uploadResume(EMAIL, file))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("DB down");

        verify(fileStorageService).delete(fileUrl);
    }

    // -------------------------------------------------------
    // 활성화
    // -------------------------------------------------------

    @Test
    @DisplayName("활성화: 본인 소유 이력서면 다른 이력서를 비활성화하고 대상을 활성화한다")
    void activateResume_success() {
        Member owner = member(1L, EMAIL);
        Resumes resume = Resumes.builder().id(5L).member(owner).isActive(false).build();
        when(resumesRepository.findById(5L)).thenReturn(Optional.of(resume));

        resumeService.activateResume(EMAIL, 5L);

        verify(resumesRepository).deactivateOthersByMemberId(1L, 5L);
        assertThat(resume.getIsActive()).isTrue();
        verify(eventPublisher, never()).publishEvent(any(ResumeScoreCalculationRequestedEvent.class));
    }

    @Test
    @DisplayName("활성화: 존재하지 않는 이력서면 NOT_FOUND 예외를 던진다")
    void activateResume_notFound() {
        when(resumesRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> resumeService.activateResume(EMAIL, 999L))
                .isInstanceOf(GeneralException.class)
                .satisfies(ex -> assertThat(((GeneralException) ex).getErrorCode())
                        .isEqualTo(GeneralErrorCode.NOT_FOUND));
    }

    @Test
    @DisplayName("활성화: 다른 회원 소유 이력서면 FORBIDDEN 예외를 던진다")
    void activateResume_forbidden() {
        Member owner = member(1L, "owner@jobai.com");
        Resumes resume = Resumes.builder().id(5L).member(owner).isActive(false).build();
        when(resumesRepository.findById(5L)).thenReturn(Optional.of(resume));

        assertThatThrownBy(() -> resumeService.activateResume("attacker@jobai.com", 5L))
                .isInstanceOf(GeneralException.class)
                .satisfies(ex -> assertThat(((GeneralException) ex).getErrorCode())
                        .isEqualTo(GeneralErrorCode.FORBIDDEN));

        verify(resumesRepository, never()).deactivateOthersByMemberId(anyLong(), anyLong());
    }

    // -------------------------------------------------------
    // 삭제
    // -------------------------------------------------------

    @Test
    @DisplayName("삭제: 매칭 점수를 먼저 삭제한 후 DB 삭제, 그다음 S3 삭제 순서로 처리한다")
    void deleteResume_success_deletesMatchScoresBeforeDbBeforeS3() {
        Member owner = member(1L, EMAIL);
        String fileUrl = "https://bucket.s3.ap-northeast-2.amazonaws.com/resumes/1/key.pdf";
        Resumes resume = Resumes.builder().id(5L).member(owner).storedFileUrl(fileUrl).build();
        when(resumesRepository.findById(5L)).thenReturn(Optional.of(resume));

        resumeService.deleteResume(EMAIL, 5L);

        InOrder inOrder = inOrder(privateMatchScoreRepository, publicMatchScoreRepository,
                resumesRepository, fileStorageService);
        inOrder.verify(privateMatchScoreRepository).deleteByResumeId(5L);
        inOrder.verify(publicMatchScoreRepository).deleteByResumeId(5L);
        inOrder.verify(resumesRepository).delete(resume);
        inOrder.verify(fileStorageService).delete(fileUrl);
    }

    @Test
    @DisplayName("삭제: 존재하지 않는 이력서면 NOT_FOUND 예외를 던진다")
    void deleteResume_notFound() {
        when(resumesRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> resumeService.deleteResume(EMAIL, 999L))
                .isInstanceOf(GeneralException.class)
                .satisfies(ex -> assertThat(((GeneralException) ex).getErrorCode())
                        .isEqualTo(GeneralErrorCode.NOT_FOUND));
    }

    @Test
    @DisplayName("삭제: 다른 회원 소유 이력서면 FORBIDDEN 예외를 던지고 삭제를 수행하지 않는다")
    void deleteResume_forbidden() {
        Member owner = member(1L, "owner@jobai.com");
        Resumes resume = Resumes.builder().id(5L).member(owner).build();
        when(resumesRepository.findById(5L)).thenReturn(Optional.of(resume));

        assertThatThrownBy(() -> resumeService.deleteResume("attacker@jobai.com", 5L))
                .isInstanceOf(GeneralException.class)
                .satisfies(ex -> assertThat(((GeneralException) ex).getErrorCode())
                        .isEqualTo(GeneralErrorCode.FORBIDDEN));

        verify(resumesRepository, never()).delete(any());
        verifyNoInteractions(fileStorageService);
    }
}
