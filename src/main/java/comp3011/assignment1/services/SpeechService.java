package comp3011.assignment1.services;

import comp3011.assignment1.exception.MultipleShutdownException;
import comp3011.assignment1.models.ErrorResponse;
import comp3011.assignment1.models.GlobalStatsResponse;
import comp3011.assignment1.models.ShutdownResponse;
import comp3011.assignment1.models.UptimeResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.audio.AudioModel;
import com.openai.models.audio.transcriptions.Transcription;
import com.openai.models.audio.transcriptions.TranscriptionCreateParams;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.client.RestClient;

@Service
public class SpeechService {
    private final OpenAIClient gpt;
    private final Clock clock;
    private final String startUTC;
    private final long startMillis;
    private AtomicLong totalInput = new AtomicLong(0);
    private AtomicLong totalOutput = new AtomicLong(0);
    AtomicBoolean requestingShutdown = new AtomicBoolean(false);


    SpeechService(OpenAIClient gptC) {
        gpt = gptC;
        clock = Clock.systemUTC();
        startUTC = clock.instant().toString();
        startMillis = clock.millis();
    }

    public String textFromSpeechHelper(byte[] audioBytes) throws IOException {
        try {
            Path path = Files.createTempFile("audioFile", ".webm");
            Files.write(path, audioBytes);
            TranscriptionCreateParams createParams = TranscriptionCreateParams.builder()
                    .file(path)
                    .model(AudioModel.GPT_4O_MINI_TRANSCRIBE)
                    .build();

            Transcription transcription =
                    gpt.audio().transcriptions().create(createParams).asTranscription();

            // add tokens if attached to response
            transcription.usage().ifPresent(usage -> {
                usage.tokens().ifPresent(tokens -> {
                    totalInput.addAndGet(tokens.inputTokens());
                    totalOutput.addAndGet(tokens.outputTokens());
                });
            });

            return transcription.text();

        } catch (IOException e) {
            System.out.println("Unable to create temp file");
            throw new IOException("An unexpected server error occurred while processing speech.");        }
    }

    public UptimeResponse uptimeHelper() {
        return new UptimeResponse(startUTC, clock.instant().toString(), (double) (clock.millis() - startMillis) /1000);
    }

    public GlobalStatsResponse statsHelper() {
        return new GlobalStatsResponse(totalInput.get(), totalOutput.get());
    }

    public ResponseEntity<ShutdownResponse> shutdownHelper() {
        // returns true if value was changed, meaning we need to skip this for the true (first one)
        // and run for the rest
        if (!requestingShutdown.compareAndSet(false, true)) {
            throw new MultipleShutdownException();
        }

        RestClient shutdownClient = RestClient.create();
        shutdownClient.post().uri("http://localhost:8080/actuator/shutdown").retrieve().toBodilessEntity();
        String message = "Graceful shutdown requested.";
        return new ResponseEntity<ShutdownResponse>(new ShutdownResponse(message), HttpStatus.ACCEPTED);
    }
}
