package comp3011.assignment1.controllers;

import comp3011.assignment1.models.*;
import comp3011.assignment1.services.SpeechService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;

@RestController
public class SpeechController {
    private final SpeechService service;

    public SpeechController(SpeechService service) {
        this.service = service;
    }

    @PostMapping(value = "/api/v1/getTextFromSpeech", consumes = "audio/webm")
    String getTextFromSpeech(@RequestBody byte[] audioBytes) throws IOException {
        return service.textFromSpeechHelper(audioBytes);
    }

    @PostMapping(value = "/api/v1/subtitles", consumes = "audio/wav")
    SubtitleResponse getSubtitles(@RequestBody byte[] audioBytes) throws IOException {
        return service.subtitleHelper(audioBytes);
    }

    @GetMapping("/api/v1/admin/uptime")
    UptimeResponse getUptime() {
        return service.uptimeHelper();
    }

    @PostMapping("/api/v1/admin/shutdown")
    public ResponseEntity<ShutdownResponse> shutdown() {
        return service.shutdownHelper();
    }

    @GetMapping("/api/v1/global/stats")
    GlobalStatsResponse globalStats() {
        return service.statsHelper();
    }

    @GetMapping("/api/v1/global/usage")
    UsageResponse subtitleUsage() {
        return new UsageResponse(service.getSubtitleUsage());
    }
}
