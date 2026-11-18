package com.taskpilot.projects.meetings.service;

import com.taskpilot.infrastructure.exception.BusinessException;
import com.taskpilot.projects.common.entity.MeetingRecordingEntity;
import com.taskpilot.projects.common.entity.MeetingRecordingEntity.Coverage;
import com.taskpilot.projects.common.entity.MeetingRecordingEntity.Origin;
import com.taskpilot.projects.common.entity.MeetingRecordingEntity.State;
import com.taskpilot.projects.common.entity.ProjectFileEntity;
import com.taskpilot.projects.common.entity.ProjectMeetingEntity;
import com.taskpilot.projects.common.enums.MemberRole;
import com.taskpilot.projects.common.enums.ProjectMeetingStatus;
import com.taskpilot.projects.common.repository.MeetingRecordingRepository;
import com.taskpilot.projects.common.repository.ProjectFileRepository;
import com.taskpilot.projects.common.repository.ProjectMeetingRepository;
import com.taskpilot.projects.common.repository.ProjectMemberRepository;
import com.taskpilot.projects.common.service.ProjectSecurityService;
import com.taskpilot.projects.files.service.ProjectFileService;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.InputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class ClientMeetingRecordingService {
    public static final long MAX_BYTES = 50 * 1024 * 1024;
    private static final long CAPTURE_DEADLINE_SECONDS = 10 * 60;
    private static final long UPLOAD_DEADLINE_SECONDS = 3 * 60;
    private static final String SAMPLE_SHA256 = "560a6e704000aa1035f92419b72b4f59aacd7d307fea059410be4e4eef892cf6";

    public record RecordingView(UUID id, State state, long version, Long fileId, Long durationMs, String mimeType,
                                Instant createdAt, Origin origin, Coverage coverage) {}

    private record VerifiedFile(Long id, Long recorderId, String mimeType, long bytes, String checksum) {}

    private final MeetingRecordingRepository recordings;
    private final ProjectMeetingRepository meetings;
    private final ProjectFileRepository files;
    private final ProjectMemberRepository members;
    private final ProjectSecurityService security;
    private final ProjectFileService projectFiles;
    private final TransactionTemplate transactions;

    @Transactional(readOnly = true)
    public List<RecordingView> list(Long projectId, Long meetingId, Long userId) {
        security.requireProject(projectId);
        security.validateMember(projectId, userId);
        meetings.findByIdAndProjectId(meetingId, projectId)
                .orElseThrow(() -> new BusinessException(404, "Meeting not found"));
        return recordings.findByMeetingIdAndProjectIdOrderByCreatedAtDesc(meetingId, projectId).stream()
                .filter(recording -> recording.getState() == State.READY && recording.getProjectFileId() != null
                        || recording.getState() != State.READY && Objects.equals(recording.getRecorderId(), userId))
                .map(ClientMeetingRecordingService::view)
                .toList();
    }

    @Transactional
    public RecordingView begin(Long projectId, Long meetingId, Long userId, UUID id, Origin origin, Coverage coverage) {
        ProjectMeetingEntity meeting = lockForRecorder(projectId, meetingId, userId);
        var existing = recordings.findById(id);
        if (existing.isPresent()) {
            MeetingRecordingEntity recording = authorize(existing.get(), meeting, userId);
            if (recording.getOrigin() != origin || recording.getCoverage() != coverage) {
                throw new BusinessException(409, "Recording parameters have changed");
            }
            return view(recording);
        }
        if ((origin == Origin.IMPORT) != (coverage == Coverage.IMPORTED_FILE)) {
            throw new BusinessException(400, "Recording source does not match the capture method");
        }
        if (origin == Origin.BROWSER && meeting.getStatus() != ProjectMeetingStatus.ACTIVE) {
            throw new BusinessException(409, "The meeting is not active");
        }
        Instant now = Instant.now();
        return view(recordings.saveAndFlush(MeetingRecordingEntity.builder()
                .id(id).projectId(projectId).meetingId(meetingId).recorderId(userId).origin(origin).coverage(coverage)
                .state(State.REQUESTED).createdAt(now).expiresAt(now.plusSeconds(CAPTURE_DEADLINE_SECONDS)).build()));
    }

    @Transactional
    public RecordingView transition(Long projectId, Long meetingId, Long userId, UUID id, State target, long version) {
        ProjectMeetingEntity meeting = lockForRecorder(projectId, meetingId, userId);
        MeetingRecordingEntity recording = recording(id, meeting, userId);
        State current = recording.getState();
        if (current == target) return view(recording);
        if (recording.getVersion() != version || !canTransition(recording, target)) {
            throw new BusinessException(409, "Recording state has changed");
        }
        if (target == State.CAPTURING && meeting.getStatus() != ProjectMeetingStatus.ACTIVE) {
            throw new BusinessException(409, "The meeting is not active");
        }
        recording.setState(target);
        if (target == State.FAILED) {
            recording.setExpiresAt(null);
        } else {
            long seconds = target == State.CAPTURING ? CAPTURE_DEADLINE_SECONDS : UPLOAD_DEADLINE_SECONDS;
            recording.setExpiresAt(Instant.now().plusSeconds(seconds));
        }
        return view(recordings.saveAndFlush(recording));
    }

    public RecordingView finalizeRecording(Long projectId, Long meetingId, Long userId, UUID id, Long fileId,
                                            Long durationMs, long byteLength, String mimeType, String checksum,
                                            long version) {
        RecordingView ready = transactions.execute(transaction -> {
            ProjectMeetingEntity meeting = lockForRecorder(projectId, meetingId, userId);
            MeetingRecordingEntity recording = recording(id, meeting, userId);
            if (recording.getState() == State.READY) {
                return sameFinalization(recording, fileId, durationMs, byteLength, mimeType, checksum);
            }
            requireUploadVersion(recording, version);
            return null;
        });
        if (ready != null) return ready;

        String extension = extension(mimeType);
        String expectedName = "meeting_" + meetingId + "_rec_" + id + "." + extension;
        VerifiedFile verified = verifyFile(projectId, fileId, userId, Pattern.compile(Pattern.quote(expectedName)));
        if (verified.bytes() != byteLength || !verified.mimeType().equalsIgnoreCase(mimeType)
                || !verified.checksum().equalsIgnoreCase(checksum)) {
            throw new BusinessException(422, "Recording metadata does not match the saved file");
        }

        return transactions.execute(transaction -> {
            ProjectMeetingEntity meeting = lockForRecorder(projectId, meetingId, userId);
            MeetingRecordingEntity recording = recording(id, meeting, userId);
            if (recording.getState() == State.READY) {
                return sameFinalization(recording, fileId, durationMs, byteLength, mimeType, checksum);
            }
            requireUploadVersion(recording, version);
            requireFile(projectId, fileId);
            finish(recording, meeting, verified, durationMs);
            return view(recordings.saveAndFlush(recording));
        });
    }

    public void linkLegacy(Long projectId, Long meetingId, Long userId, Long fileId) {
        boolean alreadyLinked = Boolean.TRUE.equals(transactions.execute(transaction ->
                Objects.equals(lockForRecorder(projectId, meetingId, userId).getRecordingFileId(), fileId)));
        if (alreadyLinked) return;

        VerifiedFile file = verifyFile(projectId, fileId, userId,
                Pattern.compile("meeting_" + meetingId + "_rec_[0-9]+[.](webm|mp4)"));
        UUID id = UUID.nameUUIDFromBytes(("legacy-recording:" + projectId + ":" + meetingId + ":" + fileId)
                .getBytes(StandardCharsets.UTF_8));
        transactions.executeWithoutResult(transaction -> {
            ProjectMeetingEntity meeting = lockForRecorder(projectId, meetingId, userId);
            if (recordings.existsById(id) || Objects.equals(meeting.getRecordingFileId(), fileId)) return;
            requireFile(projectId, fileId);
            var recording = MeetingRecordingEntity.builder()
                    .id(id).projectId(projectId).meetingId(meetingId).recorderId(file.recorderId())
                    .origin(Origin.IMPORT).coverage(Coverage.IMPORTED_FILE).createdAt(Instant.now()).build();
            finish(recording, meeting, file, null);
            recordings.saveAndFlush(recording);
        });
    }

    @Scheduled(fixedDelay = 60_000)
    @Transactional
    public void expireInterruptedRecordings() {
        recordings.expire(Instant.now(), State.INTERRUPTED);
    }

    private VerifiedFile verifyFile(Long projectId, Long fileId, Long userId, Pattern expectedName) {
        ProjectFileEntity file = requireFile(projectId, fileId);
        if (!Objects.equals(file.getUploaderId(), userId) || !expectedName.matcher(file.getOriginalName()).matches()) {
            throw new BusinessException(403, "This file does not belong to this recording");
        }
        if (file.getFileSize() <= 0 || file.getFileSize() > MAX_BYTES) {
            throw new BusinessException(422, "Recording size is invalid");
        }
        extension(file.getContentType());
        try (InputStream stream = projectFiles.downloadFileWithRange(projectId, fileId, userId, null, null).inputStream()) {
            if (stream == null) throw new IOException("Empty provider response");
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[16 * 1024];
            long bytes = 0;
            int count;
            while ((count = stream.read(buffer)) != -1) {
                bytes += count;
                if (bytes > file.getFileSize()) throw new IOException("Recording length exceeded");
                digest.update(buffer, 0, count);
            }
            if (bytes != file.getFileSize()) throw new IOException("Recording length changed");
            String checksum = HexFormat.of().formatHex(digest.digest());
            if (SAMPLE_SHA256.equals(checksum)) {
                throw new BusinessException(422, "The meeting demo video cannot be linked as a recording");
            }
            return new VerifiedFile(fileId, userId, file.getContentType(), bytes, checksum);
        } catch (IOException ex) {
            throw new BusinessException(503, "Could not verify the saved recording");
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private ProjectFileEntity requireFile(Long projectId, Long fileId) {
        return files.findByIdAndProjectId(fileId, projectId)
                .orElseThrow(() -> new BusinessException(404, "Recording file not found"));
    }

    private ProjectMeetingEntity lockForRecorder(Long projectId, Long meetingId, Long userId) {
        security.requireActiveProject(projectId);
        security.validateMember(projectId, userId);
        ProjectMeetingEntity meeting = meetings.findForUpdate(meetingId, projectId)
                .orElseThrow(() -> new BusinessException(404, "Meeting not found"));
        if (!Objects.equals(meeting.getHostId(), userId) && members.findByProjectIdAndUserId(projectId, userId)
                .filter(member -> member.getRole() == MemberRole.MANAGER).isEmpty()) {
            throw new BusinessException(403, "Only the host or project manager can edit recordings");
        }
        return meeting;
    }

    private MeetingRecordingEntity recording(UUID id, ProjectMeetingEntity meeting, Long userId) {
        return authorize(recordings.findById(id).orElseThrow(() -> new BusinessException(404, "Recording not found")),
                meeting, userId);
    }

    private static MeetingRecordingEntity authorize(MeetingRecordingEntity recording, ProjectMeetingEntity meeting, Long userId) {
        if (!Objects.equals(recording.getProjectId(), meeting.getProjectId())
                || !Objects.equals(recording.getMeetingId(), meeting.getId())
                || !Objects.equals(recording.getRecorderId(), userId)) {
            throw new BusinessException(404, "Recording not found");
        }
        return recording;
    }

    private static boolean canTransition(MeetingRecordingEntity record, State target) {
        State current = record.getState();
        return switch (target) {
            case CAPTURING -> current == State.REQUESTED && record.getOrigin() == Origin.BROWSER;
            case FINALIZING -> current == State.CAPTURING;
            case UPLOADING -> current == State.FINALIZING || current == State.INTERRUPTED || current == State.REQUESTED;
            case FAILED -> current != State.READY && current != State.INTERRUPTED;
            default -> false;
        };
    }

    private static String extension(String mimeType) {
        if (mimeType == null) throw new BusinessException(415, "Recording format is not supported");
        return switch (mimeType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT)) {
            case "video/webm" -> "webm";
            case "video/mp4" -> "mp4";
            default -> throw new BusinessException(415, "Recording format is not supported");
        };
    }

    private static void requireUploadVersion(MeetingRecordingEntity recording, long version) {
        if (recording.getState() != State.UPLOADING || recording.getVersion() != version) {
            throw new BusinessException(409, "Recording state has changed");
        }
    }

    private static RecordingView sameFinalization(MeetingRecordingEntity recording, Long fileId, Long durationMs,
                                                   long byteLength, String mimeType, String checksum) {
        if (!Objects.equals(recording.getProjectFileId(), fileId) || !Objects.equals(recording.getDurationMs(), durationMs)
                || !Objects.equals(recording.getByteLength(), byteLength) || !recording.getMimeType().equalsIgnoreCase(mimeType)
                || !recording.getChecksum().equalsIgnoreCase(checksum)) {
            throw new BusinessException(409, "The recording has already been finalized with a different file");
        }
        return view(recording);
    }

    private static void finish(MeetingRecordingEntity recording, ProjectMeetingEntity meeting, VerifiedFile file, Long durationMs) {
        recording.setProjectFileId(file.id());
        recording.setMimeType(file.mimeType());
        recording.setChecksum(file.checksum());
        recording.setByteLength(file.bytes());
        recording.setDurationMs(durationMs);
        recording.setState(State.READY);
        recording.setExpiresAt(null);
        meeting.setRecordingEnabled(true);
        meeting.setRecordingFileId(file.id());
    }

    private static RecordingView view(MeetingRecordingEntity recording) {
        return new RecordingView(recording.getId(), recording.getState(), recording.getVersion(), recording.getProjectFileId(),
                recording.getDurationMs(), recording.getMimeType(), recording.getCreatedAt(),
                recording.getOrigin(), recording.getCoverage());
    }
}
