package comp3011.assignment1;

import comp3011.assignment1.controllers.SpeechController;
import comp3011.assignment1.exception.MultipleShutdownException;
import comp3011.assignment1.models.GlobalStatsResponse;
import comp3011.assignment1.models.ShutdownResponse;
import comp3011.assignment1.models.UptimeResponse;
import comp3011.assignment1.services.SpeechService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;

import java.io.IOException;
import java.time.Instant;

import static org.mockito.Mockito.when;

@WebMvcTest(SpeechController.class)
public class SpeechControllerTests {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SpeechService service;

    @Test
    void getUptimeNormal() throws Exception {
        when(service.uptimeHelper()).thenReturn(new UptimeResponse("stubStart", "utcnow", 1));

        mockMvc.perform(get("/api/v1/admin/uptime"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.utcServerStart").value("stubStart"))
                .andExpect(jsonPath("$.utcNow").value("utcnow"))
                .andExpect(jsonPath("$.serverUptimeSeconds").value(1));
    }

    @Test
    void getUptimeThrowsException() throws Exception {
        when(service.uptimeHelper()).thenThrow(new RuntimeException("LOL BROKEN"));

        mockMvc.perform(get("/api/v1/admin/uptime"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.error").value("Internal Server Error"))
                .andExpect(jsonPath("$.path").value("/api/v1/admin/uptime"))
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    void getUsageNormal() throws Exception {
        when(service.statsHelper()).thenReturn(new GlobalStatsResponse(1, 1));

        mockMvc.perform(get("/api/v1/global/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inputTokens").value(1))
                .andExpect(jsonPath("$.outputTokens").value(1));
    }

    @Test
    void getUsageThrowsException() throws Exception {
        when(service.statsHelper()).thenThrow(new RuntimeException("LOL BROKEN"));

        mockMvc.perform(get("/api/v1/global/stats"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.error").value("Internal Server Error"))
                .andExpect(jsonPath("$.path").value("/api/v1/global/stats"))
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    void postShutdownNormal() throws Exception {
        when(service.shutdownHelper()).thenReturn(new ResponseEntity<ShutdownResponse>(new ShutdownResponse("Graceful shutdown requested."), HttpStatus.ACCEPTED));

        mockMvc.perform(post("/api/v1/admin/shutdown"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.message").value("Graceful shutdown requested."));
    }

    @Test
    void postShutdownThrowsException() throws Exception {
        when(service.shutdownHelper()).thenThrow(new RuntimeException("LOL BROKEN"));

        mockMvc.perform(post("/api/v1/admin/shutdown"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.error").value("Internal Server Error"))
                .andExpect(jsonPath("$.path").value("/api/v1/admin/shutdown"))
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    void postMultipleShutdownException() throws Exception {
        when(service.shutdownHelper())
                .thenThrow(new MultipleShutdownException());

        mockMvc.perform(post("/api/v1/admin/shutdown"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.error").value("Conflict"))
                .andExpect(jsonPath("$.message").value("Shutdown is already in progress."))
                .andExpect(jsonPath("$.path").value("/api/v1/admin/shutdown"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    void getTextFromSpeechNormal() throws Exception {
        byte[] audioBytes = {1};
        when(service.textFromSpeechHelper(audioBytes)).thenReturn("got the audio bro");

        mockMvc.perform(post("/api/v1/getTextFromSpeech").
                contentType("audio/webm").
                content(audioBytes))
                .andExpect(status().isOk())
                .andExpect(content().string("got the audio bro"));
    }

    @Test
    void getTFSThrowsException() throws Exception {
        when(service.textFromSpeechHelper(null)).thenThrow(new IOException("LOL BROKEN"));

        mockMvc.perform(post("/api/v1/getTextFromSpeech"))
                .andDo(print())
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.error").value("Internal Server Error"))
                .andExpect(jsonPath("$.path").value("/api/v1/getTextFromSpeech"))
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.message").exists());
    }
}
