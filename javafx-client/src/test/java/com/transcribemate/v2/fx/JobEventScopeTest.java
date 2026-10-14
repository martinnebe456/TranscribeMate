package com.transcribemate.v2.fx;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JobEventScopeTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void sharedClientFanoutScopesTerminalEffectsToMatchingWindow() throws Exception {
        BackendClient sharedClient = BackendClient.auto();
        WindowListener firstWindow = new WindowListener("job-1");
        WindowListener secondWindow = new WindowListener("job-2");
        sharedClient.addEventListener(firstWindow);
        sharedClient.addEventListener(secondWindow);

        sharedClient.dispatchEvent(terminalEvent("job-1"));

        assertEquals(1, firstWindow.jobListRefreshes.get());
        assertEquals(1, secondWindow.jobListRefreshes.get());
        assertEquals(1, firstWindow.terminalProgressUpdates.get());
        assertEquals(1, firstWindow.speakerMappingDialogs.get());
        assertEquals(1, firstWindow.outputFolderOpenings.get());
        assertEquals(0, secondWindow.terminalProgressUpdates.get());
        assertEquals(0, secondWindow.speakerMappingDialogs.get());
        assertEquals(0, secondWindow.outputFolderOpenings.get());

        sharedClient.dispatchEvent(terminalEvent("job-2"));

        assertEquals(2, firstWindow.jobListRefreshes.get());
        assertEquals(2, secondWindow.jobListRefreshes.get());
        assertEquals(1, firstWindow.terminalProgressUpdates.get());
        assertEquals(1, firstWindow.speakerMappingDialogs.get());
        assertEquals(1, firstWindow.outputFolderOpenings.get());
        assertEquals(1, secondWindow.terminalProgressUpdates.get());
        assertEquals(1, secondWindow.speakerMappingDialogs.get());
        assertEquals(1, secondWindow.outputFolderOpenings.get());
    }

    private static JsonNode terminalEvent(String jobId) throws Exception {
        return MAPPER.readTree("""
                {"type":"event","event":"job.completed","job_id":"%s","payload":{"result":{"final_output_dir":"/output/job","artifacts":[{"kind":"sidecar","path":"/output/job/audio.diarization.json"}]}}}
                """.formatted(jobId));
    }

    private static final class WindowListener implements Consumer<JsonNode> {
        private final String currentJobId;
        private final AtomicInteger jobListRefreshes = new AtomicInteger();
        private final AtomicInteger terminalProgressUpdates = new AtomicInteger();
        private final AtomicInteger speakerMappingDialogs = new AtomicInteger();
        private final AtomicInteger outputFolderOpenings = new AtomicInteger();

        private WindowListener(String currentJobId) {
            this.currentJobId = currentJobId;
        }

        @Override
        public void accept(JsonNode event) {
            jobListRefreshes.incrementAndGet();
            if (JobEventScope.owns(currentJobId, event.path("job_id").asText(""))) {
                terminalProgressUpdates.incrementAndGet();
                JsonNode result = event.path("payload").path("result");
                if (result.path("artifacts").isArray() && !result.path("artifacts").isEmpty()) {
                    speakerMappingDialogs.incrementAndGet();
                }
                if (!result.path("final_output_dir").asText("").isBlank()) {
                    outputFolderOpenings.incrementAndGet();
                }
            }
        }
    }
}
