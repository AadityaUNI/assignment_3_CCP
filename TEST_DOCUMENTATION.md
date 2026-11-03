# Testing Architecture & Verification Documentation

This document goes over the unit, integration, and concurrency stress testing for the COMP3011 STT web service. It proves that the functional requirements from the API spec works, and that the server won't crash or corrupt data under heavy load.

## 1. WebMvc Controller Unit Tests (SpeechControllerTests.java)

These tests check the web layer using @WebMvcTest and MockMvc. I mocked the SpeechService so the tests run fast without actually hitting the file system or the OpenAI API.

### getUptimeNormal()
Why it was developed: To make sure the GET /api/v1/admin/uptime endpoint works exactly as per the yaml spec.
Functional & Non-Functional Assurances:
* Makes sure it returns a 200 OK on all correct requests.
* Checks that the JSON fields like utcServerStart, utcNow, and serverUptimeSeconds are formatted as required.
  Expected Results: Status 200 OK and the JSON payload has the fake stubbed times and uptime.

### getUptimeThrowsException()
Why it was developed: To test if the controller handles random errors during the uptime calculation properly.
Functional & Non-Functional Assurances:
* Proves the ExceptionController catches random runtime exceptions and throws a 500 error.
* Makes sure no error jargon leaks to the frontend, just the proper ErrorResponse schema.
  Expected Results: Status 500 Internal Server Error, and the JSON has the proper status, error name, path, timestamp, and message.

### getUsageNormal()
Why it was developed: To verify the GET /api/v1/global/stats endpoint works.
Functional & Non-Functional Assurances:
* Verifies token usage JSON structure.
* Checks that it serializes the long integers for inputTokens and outputTokens.
  Expected Results: Status 200 OK and the payload matches the stubbed token counts.

### getUsageThrowsException()
Why it was developed: To confirm the error handling is consistent for the stats endpoint too.
Functional & Non-Functional Assurances:
* Proves the Global Exception Handler works on the stats route.
  Expected Results: Status 500 Internal Server Error with the standard error metadata.

### postShutdownNormal()
Why it was developed: To unit-test the POST /api/v1/admin/shutdown route.
Functional & Non-Functional Assurances:
* Checks that it returns a 202 Accepted status code ⇒ accepted the request but is processing it asynchronously.
* Verifies the exact confirmation string is returned.
  Expected Results: Status 202 Accepted and the payload has the message "Graceful shutdown requested."

### postShutdownThrowsException()
Why it was developed: To make sure if the shutdown mechanism fails internally, it returns a proper error instead of hanging the client.
Functional & Non-Functional Assurances:
* Guarantees a failed shutdown attempt returns a structured server error.
  Expected Results: Status 500 Internal Server Error with the standard error schema.

### postMultipleShutdownException()
Why it was developed: To test the rejection of shutdown requests if one is already happening, and returning an appropriate conflict error.
Functional & Non-Functional Assurances:
* Proves the app state is protected against spamming the shutdown endpoint.
* Confirms that the custom MultipleShutdownException throws a 409 Conflict instead of a standard 500 error.
  Expected Results: Status 409 Conflict, and the payload says "Shutdown is already in progress." with the right path.

### getTextFromSpeechNormal()
Why it was developed: To verify the POST /api/v1/getTextFromSpeech transcription endpoint.
Functional & Non-Functional Assurances:
* Checks that it accepts audio/webm files.
* Proves the audio bytes get passed down to the service and the raw string comes back.
  Expected Results: Status 200 OK and the body is just the raw transcript string.

### getTFSThrowsException()
Why it was developed: To simulate what happens if saving the temporary audio file fails.
Functional & Non-Functional Assurances:
* Proves that IOExceptions are caught and turned into clean 500 errors without leaking local server file paths to the user.
  Expected Results: Status 500 Internal Server Error matching the usual structure.

## 2. Concurrency & Integration Tests (ConcurrencyTests.java)

These tests spin up an actual local server on port 8080 to test the whole stack together. It tests the networking, virtual threads, and makes sure the service logic is thread-safe under heavy load.

Synchronization Strategy
I used a CountDownLatch setup to force all threads to run at the exact same time:
1. readyLatch: Threads count down when they are ready, making sure all 200+ are spun up before doing anything.
2. startLatch: The main test thread releases this latch to fire all the worker threads at the exact same millisecond.

### test200ConcurrentUptimeRequests()
Why it was developed: To hit the requirement of handling over 200 simultaneous blocking HTTP requests without crashing or taking forever.
Mechanism:
* Sets up a thread pool of 250.
* Fires 201 GET requests at /api/v1/admin/uptime all at once.
* Counts the successes and failures.
  Guarantees:
* Proves that enabling Virtual Threads in Spring Boot handles massive concurrency without running out of platform threads.
* Confirms the read endpoints don't deadlock under load.
  Expected Results:
* All 201 requests finish within the timeout.
* The success counter hits 201 with 0 failures.
* Everything finishes in under 5 seconds.

### test500SpeechRequests_SurfaceRaceCondition()
Why it was developed: To test the endpoints against a stubbed STT API, and to intentionally cause and prove a race condition.
Mechanism & Strategy:
* STT Stubbing: I mocked the OpenAI client so it returns a fake transcription with exactly 10 input tokens and 5 output tokens. This lets me test massive load without paying for API calls.
* Surfacing the Race Condition: 500 threads spam the text-from-speech endpoint. In the test, I used standard long arrays to count the tokens across threads. Because these aren't thread-safe, the threads overwrite each other and cause data loss.
* Validating Backend Thread Safety: While the test's client-side counters fail due to the race condition, the actual SpeechService uses AtomicLong for its counters. The test checks the /api/v1/global/stats endpoint at the end to prove the server didn't lose any data.
  Guarantees:
* Shows what a race condition actually looks like in shared memory.
* Proves the backend service is completely thread-safe for global counters even with 500 concurrent requests.
  Expected Results:
* All 500 requests pass.
* The client-side array counters don't add up to the correct mathematical total (proving the race condition happened).
* The server stats endpoint returns the exact correct totals (5000 input, 2500 output), proving the backend handled the load safely.

### test200ConcurrentShutdownRequests()
Why it was developed: To make sure the server doesn't crash if 200 people try to shut it down at the same time, and 
requests are sequentially handled for the graceful shutdown timeline.
Mechanism:
* 201 threads send a POST to /api/v1/admin/shutdown.
* The service uses AtomicBoolean compareAndSet to guard the shutdown logic.
  Guarantees:
* Prevents race conditions during critical state changes.
* Proves only one thread actually triggers the shutdown.
* Makes sure the other 200 threads get a 409 Conflict instead of crashing the server.
  Expected Results:
* Exactly 1 request gets a 202 Accepted.
* Exactly 200 requests get a 409 Conflict.
* 0 requests fail or time out.