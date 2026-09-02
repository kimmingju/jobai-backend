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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ResumeService {

    private final ResumesRepository resumesRepository;
    private final MemberRepository memberRepository;
    private final FileStorageService fileStorageService;
    private final ResumeParsingService resumeParsingService;
    private final EmbeddingService embeddingService;
    private final ApplicationEventPublisher eventPublisher;
    private final PrivateMatchScoreRepository privateMatchScoreRepository;
    private final PublicMatchScoreRepository publicMatchScoreRepository;
    private final TransactionTemplate transactionTemplate;

    public ResumeResponseDTO.ResumeListDTO getResumes(String email) {
        List<Resumes> resumes = resumesRepository.findByMemberEmailOrderByUpdatedAtDescIdDesc(email);
        List<ResumeResponseDTO.ResumeItemDTO> items = resumes.stream()
                .map(r -> ResumeResponseDTO.ResumeItemDTO.builder()
                        .resumeId(r.getId())
                        .originalFilename(r.getOriginalFilename())
                        .storedFileUrl(r.getStoredFileUrl())
                        .fileSize(r.getFileSize())
                        .isActive(r.getIsActive())
                        .uploadedAt(r.getUpdatedAt())
                        .build())
                .collect(Collectors.toList());
        return ResumeResponseDTO.ResumeListDTO.builder().resumes(items).build();
    }

    @Transactional
    public Long uploadResume(String email, MultipartFile file) {
        Member member = memberRepository.findByEmail(email)
                .orElseThrow(() -> new GeneralException(GeneralErrorCode.MEMBER_NOT_FOUND));

        validatePdf(file);

        // 1) S3 업로드 — 트랜잭션 밖
        String key = "resumes/" + member.getId() + "/" + UUID.randomUUID() + "_" + file.getOriginalFilename();
        String fileUrl = fileStorageService.upload(file, key);

        // 2) 짧은 트랜잭션 1: resume 메타데이터 저장 + 기존 이력서 비활성화
        Resumes resume;
        try {
            resume = transactionTemplate.execute(status -> {
                Resumes r = Resumes.builder()
                        .member(member)
                        .originalFilename(file.getOriginalFilename())
                        .storedFileUrl(fileUrl)
                        .fileSize(formatFileSize(file.getSize()))
                        .isActive(true)
                        .updatedAt(LocalDate.now())
                        .build();
                Resumes saved = resumesRepository.save(r);
                resumesRepository.deactivateOthersByMemberId(member.getId(), saved.getId());
                return saved;
            });
        } catch (Exception e) {
            fileStorageService.delete(fileUrl);
            throw e;
        }

        Long resumeId = resume.getId();

        // 3) 외부 호출 — 트랜잭션 밖 (커넥션 점유 없음)
        // 이력서 파싱 (PDF 텍스트 추출 + 기술스택 파싱)
        try {
            byte[] pdfBytes = file.getBytes();
            resumeParsingService.parseAndUpdate(resume, pdfBytes);
        } catch (Exception e) {
            log.warn("이력서 파싱 실패 (업로드는 정상 완료): resumeId={}, error={}", resumeId, e.getMessage());
        }

        // 이력서 임베딩 (AI 서버 호출)
        float[] embedding = null;
        float[] ncsEmbedding = null;
        if (resume.getExtractedText() != null && !resume.getExtractedText().isBlank()) {
            try {
                embedding = embeddingService.embedResumeText(resume.getExtractedText());
            } catch (Exception e) {
                log.warn("이력서 임베딩 실패 (업로드는 정상 완료): resumeId={}, error={}", resumeId, e.getMessage());
            }

            try {
                ncsEmbedding = embeddingService.embedResumeTextNcs(resume.getExtractedText());
            } catch (Exception e) {
                log.warn("이력서 NCS 임베딩 실패 (업로드는 정상 완료): resumeId={}, error={}", resumeId, e.getMessage());
            }
        }

        // 4) 짧은 트랜잭션 2: 파싱/임베딩 결과 업데이트 + 이벤트 발행
        final float[] finalEmbedding = embedding;
        final float[] finalNcsEmbedding = ncsEmbedding;
        transactionTemplate.executeWithoutResult(status -> {
            Resumes managed = resumesRepository.findById(resumeId).orElseThrow();
            if (finalEmbedding != null) {
                managed.updateEmbedding(finalEmbedding);
            }
            if (finalNcsEmbedding != null) {
                managed.updateNcsEmbedding(finalNcsEmbedding);
            }
            eventPublisher.publishEvent(new ResumeScoreCalculationRequestedEvent(resumeId));
        });

        return resumeId;
    }

    @Transactional
    public void activateResume(String email, Long resumeId) {
        Resumes resume = resumesRepository.findById(resumeId)
                .orElseThrow(() -> new GeneralException(GeneralErrorCode.NOT_FOUND, "해당 이력서를 찾을 수 없습니다."));

        if (!resume.getMember().getEmail().equals(email)) {
            throw new GeneralException(GeneralErrorCode.FORBIDDEN, "해당 이력서에 대한 권한이 없습니다.");
        }

        resumesRepository.deactivateOthersByMemberId(resume.getMember().getId(), resumeId);
        resume.activate();
    }

    @Transactional
    public void deleteResume(String email, Long resumeId) {
        Resumes resume = resumesRepository.findById(resumeId)
                .orElseThrow(() -> new GeneralException(GeneralErrorCode.NOT_FOUND, "해당 이력서를 찾을 수 없습니다."));

        if (!resume.getMember().getEmail().equals(email)) {
            throw new GeneralException(GeneralErrorCode.FORBIDDEN, "해당 이력서에 대한 권한이 없습니다.");
        }

        // DB를 먼저 삭제 후 S3 삭제: DB 실패 시 파일은 보존되어 재시도 가능
        // 매칭 점수는 resume_id FK(NOT NULL, cascade 없음)를 갖고 있어 이력서보다 먼저 삭제해야 함
        String storedFileUrl = resume.getStoredFileUrl();
        privateMatchScoreRepository.deleteByResumeId(resumeId);
        publicMatchScoreRepository.deleteByResumeId(resumeId);
        resumesRepository.delete(resume);
        fileStorageService.delete(storedFileUrl);
    }

    private void validatePdf(MultipartFile file) {
        if (file.isEmpty()) {
            throw new GeneralException(GeneralErrorCode.BAD_REQUEST, "파일이 비어 있습니다.");
        }
        try {
            // Content-Type은 클라이언트가 위조 가능하므로 실제 파일 시그니처(%PDF-)로 검증
            byte[] header = file.getInputStream().readNBytes(5);
            if (header.length < 5 || !new String(header).startsWith("%PDF-")) {
                throw new GeneralException(GeneralErrorCode.BAD_REQUEST, "PDF 파일만 업로드할 수 있습니다.");
            }
        } catch (GeneralException e) {
            throw e;
        } catch (IOException e) {
            throw new GeneralException(GeneralErrorCode.INTERNAL_SERVER_ERROR, "파일을 읽는 중 오류가 발생했습니다.");
        }
    }

    private String formatFileSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024));
    }
}
