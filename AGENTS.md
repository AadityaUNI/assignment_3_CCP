# AGENTS.md — COMP3011 Assignment 3

Context for anyone (human or AI) working in this repo. The full spec and marking rubric are in **`rubric.md`**. Read it before making design decisions.

## What this project is

A university assignment (Adelaide University, COMP3011 Cloud and Concurrent Programming, Assignment 3). Two earlier assignments were merged into **one Maven project**:

- **Video player** (Assignment 2): a JavaFX desktop player using FFmpeg via JavaCV. It stacks "old film" effects (frame processors) chosen on the command line, and has a multi-threaded decode → effects → display pipeline.
- **Web server** (Assignment 1): a Spring Boot speech-to-text server that calls OpenAI.

**Assignment 3 goal:** add **speech-to-text subtitles** to the player. The player sends the media's audio to the server at `http://localhost:8080/api/v1/subtitles`. The server transcribes it with OpenAI `whisper-1` and returns text with word and segment timings. The player then renders subtitles onto the video in real time. A new `-S, --subtitle-frames` frame processor, a subtitle toggle button (right of mute) and the `s` hotkey control it. The server also reports cumulative Whisper usage at `/api/v1/global/usage`, and may cache transcriptions. See `rubric.md` for exact requirements, subtitle style rules and grading bands.

It finishes with a **live viva demo**. The student must be able to explain every part of the code, so prefer clear, explainable designs over clever ones.

## Layout

```
pom.xml                  Single merged pom (Spring Boot parent + JavaFX + JavaCV)
mvnw / mvnw.cmd / .mvn   Maven wrapper
src/main/java/comp3011/
  VideoPlayerApp.java          JavaFX Application (player entry point)
  VideoPlayerAppLauncher.java  Plain main() used as the packaged jar's entry point
  controllers/   CommandLineController (option parsing + effect list), VideoPlayerController (wires model to view, hotkeys)
  models/        VideoPlayerModel interface; MultiThreadedVideoPlayerModel (the real one); SingleThreadedVideoPlayerModel (A2 baseline, --single); AudioPlayer
  views/         VideoPlayerView (buttons, icons, tooltips)
  frameprocessors/  FrameProcessor base + one class per effect
  assignment1/   The Spring Boot server (package name kept from A1)
    Assignment1Application.java, controllers/, services/, beans/ (OpenAIConfig), models/, exception/
src/main/resources/      application.properties, static/ (A1 web page)
src/test/java/comp3011/  Player tests (CommandLineController, effect thread-safety, PlaybackLoadIT) + server tests
README.md                A2 player architecture and thread-safety notes (still accurate for the player)
movie.mp4, loadtest.mp4  Demo / load-test media
rubric.md                A3 spec + rubric
```

## Build and run

- `.\mvnw test-compile` compiles everything.
- `.\mvnw javafx:run -Djavafx.args="movie.mp4 -a"` runs the player. Plain `mvn` works too.
- To run only the server, start `comp3011.assignment1.Assignment1Application` from the IDE. It listens on port 8080.
- `.\mvnw verify` runs `PlaybackLoadIT`, which opens real windows and needs a display.
- `.\mvnw package` builds a Spring Boot jar whose `Start-Class` is `VideoPlayerAppLauncher`. It is about 800 MB because `javacv-platform` bundles native libraries for every OS.
- There are three `main` methods (`VideoPlayerApp`, `VideoPlayerAppLauncher`, `Assignment1Application`). Any packaging change must name the entry point explicitly.

## Current state

- **Player:** A2 is complete. Multi-threaded pipeline, all effects, hotkeys (Esc, Space, ←, →, Home, A). The thread ownership and race-condition tables in `README.md` describe it.
- **Server:** A1 is complete. `POST /api/v1/getTextFromSpeech` (audio/webm), plus uptime, stats and shutdown endpoints. `OpenAIConfig` builds the client with `OpenAIOkHttpClient.fromEnv()`, so the key comes from the `OPENAI_API_KEY` environment variable.
- **Not yet done:** none of the A3 subtitle work exists. The server doesn't start alongside the player yet. The planned approach is to start the Spring context inside `VideoPlayerApp.init()` (with headless off) and close it in `stop()`.

## Constraints and conventions

- **Keep all A2 behaviour and look.** The only visible change is the one new button. Effects must stay deterministic and must not race (see `README.md`). Subtitles must not disturb playback continuity, and must work alongside every effect at once under load.
- **Leave `SingleThreadedVideoPlayerModel` alone.** It is the A2 reference baseline.
- **The player talks to the server over HTTP** at `localhost:8080`, even though both live in one codebase. That is part of the spec.
- **Whisper call:** `whisper-1`, `response_format=verbose_json`, and both `timestamp_granularities[]=word` and `segment`. Skip segments where `noSpeechProb > 0.6 && aveLogProb < -1.0`. Usage is tracked in seconds as a cumulative double.
- **The FX thread must never block on the network.** Network calls happen off the FX thread with timeouts. Failures (server down, network lost, slow responses, media file disappearing) must never crash or freeze the player. Subtitles should resume on their own when the service comes back.
- **Seeking is a first-class case.** Subtitle timing must follow the playback clock and the A2 seek-generation mechanism, not wall-clock time.
- **Secrets:** never put an API key in code, comments, config committed to the repo, logs or test data. In the viva the key may be supplied as `key.txt` on a USB drive, so key loading must cope with that safely.
- **Attribution:** add the student's name to the header of any file they change. Acknowledge AI or external help in comments where it's used. The student has to be able to point to and explain those parts, which is a viva hurdle.
- **Package names:** the server stays under `comp3011.assignment1`. Renaming it means editing every `package` line. `@SpringBootApplication` only scans that subtree, which keeps Spring away from the player classes.
- **CLI letters are case-sensitive:** `-s` is already `--scratch-frames`, and `-S` is the new `--subtitle-frames`. New effects are added as described in the comment above `validEffects` in `CommandLineController.java`.
- **Style:** small, clearly named classes with one job each. Comment the *why*. Keep it explainable in a 15-minute viva.
