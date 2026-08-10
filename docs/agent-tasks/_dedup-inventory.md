# Cross-module Java duplicate inventory

Verified on branch `spike/spring-boot-4-spring-ai-2` on 2026-08-10.

Method: each non-test, non-`target` Java file whose basename appears more than once under `back`
was checked for its declared package and compared after removing whitespace. The 24 real duplicate
pairs were also byte-for-byte identical.

| Name | Paths | Package(s) | Classification |
| --- | --- | --- | --- |
| `AdherenceReview.java` | `avento-media/src/main/java/com/avento/dto/AdherenceReview.java`; `avento-workspace/src/main/java/com/avento/dto/AdherenceReview.java` | `com.avento.dto` | Real duplicate; identical |
| `AgentRunView.java` | `avento-execution/src/main/java/com/avento/dto/AgentRunView.java`; `avento-workspace/src/main/java/com/avento/dto/AgentRunView.java` | `com.avento.dto` | Real duplicate; identical |
| `AgentTimelineEvent.java` | `avento-agent/src/main/java/com/avento/model/AgentTimelineEvent.java`; `avento-execution/src/main/java/com/avento/model/AgentTimelineEvent.java` | `com.avento.model` | Real duplicate; identical; excluded from movement (JPA entity) |
| `AgentTimelineEventRepository.java` | `avento-agent/src/main/java/com/avento/model/AgentTimelineEventRepository.java`; `avento-execution/src/main/java/com/avento/model/AgentTimelineEventRepository.java` | `com.avento.model` | Real duplicate; identical; excluded from movement (Spring Data JPA repository) |
| `ApprovalReplayGuard.java` | `avento-agent/src/main/java/com/avento/service/execution/ApprovalReplayGuard.java`; `avento-execution/src/main/java/com/avento/service/execution/ApprovalReplayGuard.java` | `com.avento.service.execution` | Real duplicate; identical |
| `AssetDeletionResult.java` | `avento-media/src/main/java/com/avento/dto/AssetDeletionResult.java`; `avento-workspace/src/main/java/com/avento/dto/AssetDeletionResult.java` | `com.avento.dto` | Real duplicate; identical |
| `ChatUsage.java` | `avento-agent/src/main/java/com/avento/dto/ChatUsage.java`; `avento-workspace/src/main/java/com/avento/dto/ChatUsage.java` | `com.avento.dto` | Real duplicate; identical |
| `ConditioningReferences.java` | `avento-media/src/main/java/com/avento/dto/ConditioningReferences.java`; `avento-workspace/src/main/java/com/avento/dto/ConditioningReferences.java` | `com.avento.dto` | Real duplicate; identical |
| `DayTotal.java` | `avento-agent/src/main/java/com/avento/dto/DayTotal.java`; `avento-workspace/src/main/java/com/avento/dto/DayTotal.java` | `com.avento.dto` | Real duplicate; identical |
| `DocumentReadResult.java` | `avento-rag/src/main/java/com/avento/dto/DocumentReadResult.java`; `avento-workspace/src/main/java/com/avento/dto/DocumentReadResult.java` | `com.avento.dto` | Real duplicate; identical |
| `ImageJobView.java` | `avento-media/src/main/java/com/avento/dto/ImageJobView.java`; `avento-workspace/src/main/java/com/avento/dto/ImageJobView.java` | `com.avento.dto` | Real duplicate; identical |
| `ImageReference.java` | `avento-media/src/main/java/com/avento/dto/ImageReference.java`; `avento-workspace/src/main/java/com/avento/dto/ImageReference.java` | `com.avento.dto` | Real duplicate; identical |
| `JobDeletionResult.java` | `avento-media/src/main/java/com/avento/dto/JobDeletionResult.java`; `avento-workspace/src/main/java/com/avento/dto/JobDeletionResult.java` | `com.avento.dto` | Real duplicate; identical |
| `LocalModelInfo.java` | `avento-media/src/main/java/com/avento/dto/LocalModelInfo.java`; `avento-workspace/src/main/java/com/avento/dto/LocalModelInfo.java` | `com.avento.dto` | Real duplicate; identical |
| `Manifest.java` | `avento-rag/src/main/java/com/avento/dto/Manifest.java`; `avento-workspace/src/main/java/com/avento/dto/Manifest.java` | `com.avento.dto` | Real duplicate; identical |
| `ModelUsage.java` | `avento-agent/src/main/java/com/avento/dto/ModelUsage.java`; `avento-workspace/src/main/java/com/avento/dto/ModelUsage.java` | `com.avento.dto` | Real duplicate; identical |
| `PreparedImageWorkflow.java` | `avento-media/src/main/java/com/avento/dto/PreparedImageWorkflow.java`; `avento-workspace/src/main/java/com/avento/dto/PreparedImageWorkflow.java` | `com.avento.dto` | Real duplicate; identical |
| `RunScope.java` | `avento-execution/src/main/java/com/avento/dto/RunScope.java`; `avento-workspace/src/main/java/com/avento/dto/RunScope.java` | `com.avento.dto` | Real duplicate; identical |
| `ScannedFile.java` | `avento-rag/src/main/java/com/avento/dto/ScannedFile.java`; `avento-workspace/src/main/java/com/avento/dto/ScannedFile.java` | `com.avento.dto` | Real duplicate; identical |
| `ToolExecutionContext.java` | `avento-agent/src/main/java/com/avento/service/tools/ToolExecutionContext.java`; `avento-workspace/src/main/java/com/avento/service/tools/ToolExecutionContext.java` | `com.avento.service.tools` | Real duplicate; identical |
| `TranscriptionResult.java` | `avento-voice/src/main/java/com/avento/dto/TranscriptionResult.java`; `avento-workspace/src/main/java/com/avento/dto/TranscriptionResult.java` | `com.avento.dto` | Real duplicate; identical |
| `VideoJobView.java` | `avento-media/src/main/java/com/avento/dto/VideoJobView.java`; `avento-workspace/src/main/java/com/avento/dto/VideoJobView.java` | `com.avento.dto` | Real duplicate; identical |
| `VideoStatus.java` | `avento-media/src/main/java/com/avento/dto/VideoStatus.java`; `avento-workspace/src/main/java/com/avento/dto/VideoStatus.java` | `com.avento.dto` | Real duplicate; identical |
| `VideoSubmission.java` | `avento-media/src/main/java/com/avento/dto/VideoSubmission.java`; `avento-workspace/src/main/java/com/avento/dto/VideoSubmission.java` | `com.avento.dto` | Real duplicate; identical |
| `WhisperContext.java` | `whisper.cpp/bindings/java/src/main/java/io/github/ggerganov/whispercpp/WhisperContext.java`; `whisper.cpp/examples/whisper.android.java/app/src/main/java/com/whispercpp/java/whisper/WhisperContext.java` | `io.github.ggerganov.whispercpp`; `com.whispercpp.java.whisper` | Same name, different package; not a duplicate |
| `WhisperSegment.java` | `whisper.cpp/bindings/java/src/main/java/io/github/ggerganov/whispercpp/bean/WhisperSegment.java`; `whisper.cpp/examples/whisper.android.java/app/src/main/java/com/litongjava/whisper/android/java/bean/WhisperSegment.java` | `io.github.ggerganov.whispercpp.bean`; `com.litongjava.whisper.android.java.bean` | Same name, different package; not a duplicate |

## Result

- Real duplicates: 24 pairs; all are byte-for-byte identical.
- Same filename but different package: 2 pairs (`WhisperContext`, `WhisperSegment`).
- Diverged pairs: 0.
- Planned scope: move 22 non-JPA real duplicate pairs to `avento-core`, retaining their packages.
  `AgentTimelineEvent` and `AgentTimelineEventRepository` remain in their current modules because
  `avento-core` deliberately has no JPA dependency; adding persistence to the shared base module is
  an architectural change outside this mechanical consolidation.
