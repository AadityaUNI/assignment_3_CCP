package comp3011.assignment1;

import com.openai.client.OpenAIClient;
import com.openai.models.audio.transcriptions.Transcription;
import com.openai.models.audio.transcriptions.TranscriptionCreateParams;
import com.openai.models.audio.transcriptions.TranscriptionCreateResponse;
import com.openai.services.blocking.AudioService;
import com.openai.services.blocking.audio.TranscriptionService;
import comp3011.assignment1.exception.MultipleShutdownException;
import comp3011.assignment1.models.GlobalStatsResponse;
import comp3011.assignment1.models.ShutdownResponse;
import comp3011.assignment1.models.UptimeResponse;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
// Own cache file, so running the tests never reads or rewrites the player's real cache/subtitle-cache.json.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT,
properties = {"server.port=8080", "subtitle.cache.file=target/test-subtitle-cache.json"})
public class ConcurrencyTests {
    @LocalServerPort
    private int port;

    RestTemplate restTemplate = new RestTemplate(
            new HttpComponentsClientHttpRequestFactory());

    @MockitoBean
    private OpenAIClient gpt;


    @Test @Order(1)
    void test200ConcurrentUptimeRequests() throws InterruptedException {
        String uri = "http://localhost:" + port + "/api/v1/admin/uptime";
        ExecutorService pool = Executors.newFixedThreadPool(250);
        CountDownLatch readyLatch = new CountDownLatch(201);
        CountDownLatch startLatch = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);

        for (int i = 0; i <= 200; i++) {
            pool.submit(() -> {
                try {
                    readyLatch.countDown();
                    startLatch.await();
                    ResponseEntity<UptimeResponse> r = restTemplate.getForEntity(uri, UptimeResponse.class);
                    if (r.getStatusCode().is2xxSuccessful()) {
                        successCount.incrementAndGet();
                    }
                    else failureCount.incrementAndGet();
                }
                catch (Exception e) {
                    failureCount.incrementAndGet();
                    System.out.println("Failed: " + e.getClass().getSimpleName() + " - " + e.getMessage());
                }
            });
        }
        // wait for all threads to get added
        readyLatch.await();
        // release the start thread
        long start = System.currentTimeMillis();
        startLatch.countDown();

        pool.shutdown();
        boolean finished = pool.awaitTermination(10, TimeUnit.SECONDS);
        long elapsed = System.currentTimeMillis() - start;

        assertTrue(finished, "Requests did not complete within timeout possible deadlock/thread starvation");
        assertEquals(201, successCount.get(), "Some requests failed: " + failureCount.get());
        assertTrue(elapsed < 5000, "Requests took too long: " + elapsed + "ms");
    }


    // 200 concurrent speech calls: the stub's plain counters race, while global stats should still be exact
    @Test @Order(2)
    void test500SpeechRequests_SurfaceRaceCondition() throws InterruptedException {

        AudioService as = mock(AudioService.class);
        TranscriptionService ts = mock(TranscriptionService.class);
        TranscriptionCreateParams tcp = mock(TranscriptionCreateParams.class);
        TranscriptionCreateResponse tcr = mock(TranscriptionCreateResponse.class);

        long inputTokenCost = 10L;
        long outputTokenCost = 5L;
        long totalTokenCost = 15L;
        int requestCount = 500;

        Transcription.Usage usage = Transcription.Usage.ofTokens(
                Transcription.Usage.Tokens.builder()
                        .inputTokens(inputTokenCost)
                        .outputTokens(outputTokenCost)
                        .totalTokens(totalTokenCost)
                        .build()
        );

        Transcription stubT = Transcription.builder()
                .text("bro this assignment is so long")
                .usage(usage)
                .build();

        when(gpt.audio()).thenReturn(as);
        when(as.transcriptions()).thenReturn(ts);
        when(ts.create(any(TranscriptionCreateParams.class))).thenReturn(tcr);
        when(tcr.asTranscription()).thenReturn(stubT);

        String uri = "http://localhost:" + port + "/api/v1/getTextFromSpeech";
        ExecutorService pool = Executors.newFixedThreadPool(requestCount + 10);
        CountDownLatch readyLatch = new CountDownLatch(requestCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);
        // using arrays because int reassignment not allowed in lambda inside threads.
        long[] inputTokens = {0};
        long[] outputTokens = {0};

        byte[] fakeAudioBytes = "not real audio data".getBytes();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("audio/webm"));
        HttpEntity<byte[]> requestEntity = new HttpEntity<>(fakeAudioBytes, headers);

        for (int i = 0; i < requestCount; i++) {
            pool.submit(() -> {
                try {
                    readyLatch.countDown();
                    startLatch.await();
                    ResponseEntity<String> r = restTemplate.postForEntity(uri, requestEntity, String.class);
                    if (r.getStatusCode().is2xxSuccessful()) {
                        successCount.incrementAndGet();
                        inputTokens[0] += inputTokenCost;
                        outputTokens[0] += outputTokenCost;
                    }
                    else failureCount.incrementAndGet();
                }
                catch (Exception e) {
                    failureCount.incrementAndGet();
                    System.out.println("Failed: " + e.getClass().getSimpleName() + " - " + e.getMessage());
                }
            });
        }
        // wait for all threads to get added
        readyLatch.await();
        // release the start thread
        long start = System.currentTimeMillis();
        startLatch.countDown();

        pool.shutdown();
        boolean finished = pool.awaitTermination(10, TimeUnit.SECONDS);
        long elapsed = System.currentTimeMillis() - start;

        assertTrue(finished, "Requests did not complete within timeout possible deadlock/thread starvation");
        assertEquals(requestCount, successCount.get(), "Some requests failed: " + failureCount.get());
        assertTrue(elapsed < 10000, "Requests took too long: " + elapsed + "ms");

        long actualTotalInputs = inputTokenCost*requestCount;
        long actualTotalOutputs = outputTokenCost*requestCount;

        // THESE CAN SOMETIMES BE EQUAL AND MOST OF THE TIME NOT EQUAL => EVIDENCE TO A RACE CONDITION!
        assertNotEquals(actualTotalInputs, inputTokens[0], "input tokens matching the correct amount, cannot happen");
        assertNotEquals(actualTotalOutputs, outputTokens[0], "output tokens matching the correct amount, cannot happen");

        // get the actual total inputs and outputs from the server
        ResponseEntity<GlobalStatsResponse> response = restTemplate.getForEntity("http://localhost:" + port + "/api/v1/global/stats", GlobalStatsResponse.class);

        assert response.getBody() != null;
        assertEquals(response.getBody().inputTokens(), actualTotalInputs, "Server input token count is incorrect");
        assertEquals(response.getBody().outputTokens(), actualTotalOutputs, "Server output token count is incorrect");

    }

    // also tests race condition on shutdown requests maybe
    @Test @Order(3)
    void test200ConcurrentShutdownRequests() throws InterruptedException {
        String uri = "http://localhost:" + port + "/api/v1/admin/shutdown";
        ExecutorService pool = Executors.newFixedThreadPool(250);
        CountDownLatch readyLatch = new CountDownLatch(201);
        CountDownLatch startLatch = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger conflictCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);

        for (int i = 0; i <= 200; i++) {
            pool.submit(() -> {
                try {
                    readyLatch.countDown();
                    startLatch.await();
                    ResponseEntity<ShutdownResponse> r = restTemplate.postForEntity(uri, null, ShutdownResponse.class);
                    if (r.getStatusCode().is2xxSuccessful()) {
                        successCount.incrementAndGet();
                    }
                    else {
                        failureCount.incrementAndGet();
                    }
                }
                catch (HttpClientErrorException e) {
                    if (e.getStatusCode() == HttpStatus.CONFLICT) {
                        conflictCount.incrementAndGet();
                    } else {
                        failureCount.incrementAndGet();
                        System.out.println("Unexpected client error: " + e.getStatusCode());
                    }
                }
                catch (Exception e) {
                    failureCount.incrementAndGet();
                    System.out.println("Failed: " + e.getClass().getSimpleName() + " - " + e.getMessage());
                }
            });
        }
        // wait for all threads to get added
        readyLatch.await();
        // release the start thread
        long start = System.currentTimeMillis();
        startLatch.countDown();
        pool.shutdown();
        boolean finished = pool.awaitTermination(10, TimeUnit.SECONDS);
        long elapsed = System.currentTimeMillis() - start;

        assertTrue(finished, "Requests did not complete within timeout possible deadlock/thread starvation");
        assertEquals(1, successCount.get(), "Some requests failed: " + failureCount.get());
        assertEquals(200, conflictCount.get(), "Conflicts are not as much as expected, failures: " + failureCount.get());
        assertTrue(elapsed < 5000, "Requests took too long: " + elapsed + "ms");

    }

}
