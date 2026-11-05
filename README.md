# COMP3011 Assignment 3 - Multi-threaded Video Player with Subtitles

> "This README was restructured and formatted by AI (Claude, Anthropic). The design and code it describes are my own
> unless acknowledged otherwise in the source comments."

For more information on this code and how to get started, see https://learn.adelaide.edu.au/courses/30747/assignments/94505

Modified by Aaditya Singh, A1946894.

## Subtitles: quick start

```
.\mvnw javafx:run -Djavafx.args="movie.mp4 -a -S"
```

- **`-S`, `--subtitle-frames`** starts the player with subtitles **on**. Without it they start **off**. Note that `-s` (lowercase) is the scratch effect.
- Press **`s`**, or click the subtitle button to the right of mute, to toggle subtitles at any time during playback.
- `-S` can be stacked with other effects like any other letter, e.g. `-wSv`. Subtitles are always drawn on top of the other effects.
- The subtitle server starts inside the player, so there is nothing else to run. It needs the `OPENAI_API_KEY` environment variable. If the server can't start, the video still plays without subtitles.

## Architecture

```
 decode thread  --decodedFrames-->  effect workers  --processedFrames-->  FX thread
 (grab + stamp)    (bounded queue)  (effects + convert)  (priority queue)  (audio + display)
```

| Thread | Job |
|---|---|
| **FX thread** (JavaFX) | Owns all control (play, pause, seek, stop), the playback clock, audio output, and deciding when each frame is shown. `pumpPlayback` never blocks. |
| **`video-decoder`** | The only thread that reads from FFmpeg during playback. Grabs a frame, queues its audio, hands the video to the pool. |
| **`effect-worker-N`** pool | Runs the effect stack on a private copy of a frame and converts it to an image. Several frames are processed at once. |

Class layout:
- `controllers/`
  - `CommandLineController` parses options and builds the effect list.
  - `VideoPlayerController` picks the model and wires it to the view.
- `models/`
  - `VideoPlayerModel` is an interface with two implementations.
  - `MultiThreadedVideoPlayerModel` is the new design.
  - `SingleThreadedVideoPlayerModel` is the starter model, kept unchanged and used only with `--single`.
- `views/`: the unchanged JavaFX view.
- `frameprocessors/`: the effects.
  - `FrameMottlerLegacy` is the original mottler, kept only as a test baseline (in `src/test/java`).
- **Adding a new effect:** see the comment above `validEffects` in `CommandLineController.java`.

## Subtitle pipeline

```
 subtitle-audio-decoder --audioChunks--> subtitle workers (x3) --HTTP--> /api/v1/subtitles --> Whisper
 (own grabber, 16 kHz PCM)                                       <--JSON--   (SpeechService + SubtitleCache)
                                               |
                                         subtitleCache (per 20 s chunk)
                                               |
 video-decoder: findSubtitleAt(frame time) --InfoFrame--> effect worker: FrameSubtitler draws it
```

1. **Chunking.** The video is split into 20 s chunks. Chunk `i` covers `[i*20, (i+1)*20)` s. `pendingChunks` and `pendingAudioChunks` are sorted sets of the chunks still to do.
2. **Audio.** The `subtitle-audio-decoder` thread has its **own** FFmpeg grabber, so it never contends with playback's `grabberLock`. It decodes each chunk to 16 kHz mono PCM, always picking the first pending chunk at or after the playhead.
3. **Fetching.** Three subtitle workers claim chunks with `pendingChunks.remove()` (only one worker can win a given chunk). Each sends the chunk as WAV to the local server with a 3 s connect timeout and a 60 s request timeout. Whisper times each reply from 0, so the player adds the chunk's start time to each subtitle before caching it, so the cache holds whole-file times.
4. **Server.** `SpeechService` calls `whisper-1` (`verbose_json`, word and segment timings) and drops segments with `noSpeechProb > 0.6 && avgLogprob < -1.0`. `processSegments` then rebuilds the text into subtitles that break after punctuation (once 3+ words have been shown for at least 1 s), never run past 7 s, and stay under 84 characters (two 42-character lines). Usage seconds are summed in a `DoubleAdder` and reported at `/api/v1/global/usage`.
5. **Server cache.** Requests carry `X-Media-Key` (from `MediaFingerprint`, built from the file's contents, worked out on its own thread) and `X-Chunk-Index`. `SubtitleCache` answers repeat requests without calling OpenAI and saves to `cache/subtitle-cache.json` when the player closes. That is why a second viewing, or a seek forward into chunks seen before, shows subtitles straight away, even with no internet.
6. **Display.** When the video decoder grabs a frame, `findSubtitleAt()` looks up the subtitle for that frame's **media timestamp** (not wall-clock time) and puts it in the frame's `InfoFrame`. On an effect worker, `FrameSubtitler` draws the text into a transparent strip (cached per text) and hands it back through `InfoFrame`. It does **not** paint into the video pixels. The FX thread copies the strip into one reused transparent image, but only when the subtitle changes. That image is shown in a second `ImageView` stacked on top of the video, and the GPU combines the two layers. Seeking needs no extra subtitle logic: frames are already stamped with media time and `seekGeneration`, and the fetchers simply move to the chunk at the new playhead.

### Failures

| Situation | What happens |
|---|---|
| Server down / no internet / OpenAI error | The request fails or returns non-200. The chunk goes back into `pendingChunks` and that worker backs off (1 s, doubling to a 5 s cap). Subtitles resume on their own once a request succeeds. |
| Slow response | Only a subtitle worker waits. The FX thread never touches the network. |
| Media file removed | If fingerprinting can't read the file it falls back to "no key", so requests still work, just uncached. Chunks already fetched stay in the player's cache. |
| Toggle while playing | The toggle only shows or hides the subtitle layer (`setVisible`), so it takes effect on the next redraw. The layer is kept up to date even while hidden, so turning subtitles on shows the right text straight away. Video frames and audio are never touched. Trade-off: subtitles always sit on top of every effect, wherever `S` is in the chain. |

## Design correctness

### Who owns shared state

| State | Protection |
|---|---|
| `grabber`, `nextDecodeIndex` | `grabberLock` |
| Playback clock, `nextDisplayIndex`, pause state, `screenImage` | FX thread only |
| `pendingAudio` | `ConcurrentLinkedQueue` (decode thread adds, FX thread drains) |
| `seekGeneration` | `AtomicInteger` |
| `playbackOpen`, `endOfStreamGeneration`, `audioOutputEnabled` | `volatile` |
| Frame pixels | Each frame is `clone()`d, so no two threads share pixel data |
| Frame counts in the pipeline | `framesInFlight` semaphore |

### Race conditions handled (model)

| # | Race | Fix |
|---|---|---|
| 1 | FFmpeg's grabber is not thread safe | Only the decode thread grabs, and only while holding `grabberLock`. |
| 2 | The grabber reuses its frame buffer on the next grab | The frame is `clone()`d inside the lock. |
| 3 | Workers finish frames out of order | Each frame gets a sequence index. `processedFrames` is sorted, and only `nextDisplayIndex` may be shown. |
| 4 | Frames from before a seek still in the pipeline | Each frame is stamped with `seekGeneration`. Workers check it between effects and the FX thread checks it before display. Stale frames are dropped. |
| 5 | A frame stamped half-way through a seek | The seek runs `setTimestamp()` + `invalidateQueuedFrames()` under the same lock as grab + stamp. |
| 6 | Old audio playing after a seek | Audio is queued under `grabberLock` and cleared under it, so everything cleared is old. |
| 7 | Decoder running ahead, memory growth, decoding while paused | `framesInFlight` permits. The decoder takes one per frame. It is returned exactly once, when the frame is shown or dropped (`queuedForDisplay` marks the hand-off). |
| 8 | A worker blocking forever after a seek emptied the queue | Workers use `poll()`, not `take()`. |
| 9 | Peek-then-poll while workers insert | The FX thread uses `remove(head)`, so it only removes the frame it checked. |
| 10 | Grabber closed mid-grab, or effects re-initialised while workers still use them | Fixed close order: stop timer → interrupt + join decoder → invalidate → wait for all permits → close audio/grabber/converter. |
| 11 | Converter set to null by close while a worker uses it | Workers read it once into a local and skip the frame if it is null. |
| 12 | A late error handler from an old session closing a new one | Errors go to the FX thread via `Platform.runLater` and are re-checked against the current generation / decode thread. |
| 13 | Last frames cut off at end of stream | Playback only finishes when `endOfStreamGeneration` is current and every permit is back. The decoder `park`s until a seek `unpark`s it. An early `unpark` is not lost. |
| 14 | Effect fields written in `initialise()` not visible to workers | `initialise()` runs on the decode thread before the frame is queued. The queue `put`/`poll` and the executor `submit` guarantee workers see those writes (happens-before). |

### No deadlocks
- There is only one lock (`grabberLock`), so lock ordering can't go wrong.
- No thread blocks while holding it: the queue `put` and semaphore `acquire` both happen outside the lock. The FX thread can always take it to seek.
- The FX thread never blocks in `pumpPlayback`.
- The decoder's blocking calls (`acquire`, `put`, `park`) respond to interrupt, so `stopDecodeThread()`'s `join()` returns quickly.

## Effects and thread sharing

There is one instance per effect position, shared by every worker. One instance per worker would not work: each instance's random seed comes from its creation index, so a frame would look different depending on which worker ran it. Shared instances mean `process()` must not keep per-frame state in fields.

| Effect | Problem in starter | Fix |
|---|---|---|
| `FrameBleeder` | Bright/blur mask arrays were fields | Allocated per call and passed as arguments |
| `FrameJitterer` | `sourcePixels` / `blackRow` were fields | Per-call locals |
| `FrameMottler` | Per-block scale indices were fields | Returned as an `int[]` |
| `FrameMottler` | Damage-mask cache fields were overwritten by other frames | 3-slot `AtomicReferenceArray` of immutable `DamageMask` records, keyed by index. A race only causes an identical rebuild. Cleared in `initialise()`. |
| Other 8 effects | none | Only write fields in `initialise()`, so already safe |

## Performance decisions
- **Workers = cores − 2**, at below-normal priority and as daemon threads. When every core is busy, the FX and render threads still get the CPU first.
- **FFmpeg gets 2 threads.** Decoding one video is cheap, and FFmpeg's default of cores + 1 would compete with the effects.
- **`framesBuffer = 35`** (~1.5 s at 24 fps). Enough to absorb slow frames, and a small refill after each seek.
- **One reused on-screen `WritableImage`.** Showing a new image per frame filled JavaFX's GPU texture pool, which forced full `System.gc()` stalls.
- **The clock starts at the first frame ready to show,** not when it was decoded, so pipeline delay never makes frames late.
- **Sharing the mottler without a lock** is ~8× faster than sharing the legacy mottler behind a lock on 16 cores (see tests).

## Tests

| Test | Asserts |
|---|---|
| **`CommandLineControllerTests`** (`mvn test`) | |
| `generalStackedParse` | Chained letters keep their order and mix with long forms and flags (`-a`, `-x`, `-2`). |
| `repeatedEffectsParse` | A repeated effect (`-sns`) is applied each time it is given, and each use gets its own processor. |
| `threadingModeParse` | Multi-threaded by default; `--single` selects the original and isn't read as an effect chain. |
| `helpWithoutFile` | `--help` alone needs no file; effects without a file are an error. |
| `invalidParse` | Unknown letters or flags mixed into a chain are rejected with no effects created. |
| `subtitleFramesParse` | `-S` and `--subtitle-frames` add a `FrameSubtitler` and start subtitles on; `S` keeps its place in a chain. |
| `subtitleLetterIsCaseSensitive` | `-s` is still scratch (subtitles start off); `-sS` gives one of each. |
| **`FrameMottlerThreadSafetyTests`** (`mvn test`) | |
| `refactoredMottlerLooksIdenticalToLegacy` | The refactor produces byte-identical frames to the original. |
| `sharedLegacyMottlerCorruptsFrames` | A shared legacy mottler corrupts frames, which shows the tests can detect a race. |
| `sharedMottlerMatchesSequentialDuringPlayback` | A shared mottler on N threads matches a single-threaded run (5 rounds). |
| `sharedMottlerMatchesSequentialWhenFramesArriveOutOfOrder` | Same, with shuffled frames (like seeking), which stresses the mask cache. |
| `sharedMottlerOutpacesLockedLegacy` | The lock-free shared mottler is > 1.5× faster than a locked legacy one. |
| **`SharedEffectThreadSafetyTests`** (`mvn test`) | |
| `bleederChangesFramesDeterministically` / `jittererChangesFramesDeterministically` | The effect changes frames and repeats exactly, so the thread test below can't pass trivially. |
| `sharedBleederMatchesSequential` / `sharedJittererMatchesSequential` | A shared instance on N threads matches a single-threaded run (5 rounds). The same test against the starter versions corrupted 32 (Bleeder) and 21 (Jitterer) of 200 frames. |
| **`PlaybackLoadIT`** (`mvn verify`) | |
| `multiThreadedKeepsUpAtLeastAsWellAsSingleThreaded` | Plays the first minute with 6 and 12 effects on both players. The multi-threaded player must have no more late frames and no more negative-headroom frames than the single-threaded one (1 % tolerance). Prints a comparison table. Opens a real window, so it needs a display. Takes ~4-5 min. |

Thread-safety tests use in-memory frames with random but repeatable pixels. Effects are deterministic, so a single-threaded run is an exact reference: any race shows up as a byte difference.

Only the Mottler, Bleeder and Jitterer have thread-safety tests. The other 8 effects were already thread safe and their design was not changed, so no tests were written for them.

### Load test results

Output of `PlaybackLoadIT` (`mvn verify`) on a 16-core machine, playing the first 60 s of `movie.mp4` (1920x800, 24 fps, 41.7 ms frame budget).

**6 effects (`-sfwyvd`)**

| Model | Frames | Late frames | Negative headroom | Worst late | Worst headroom |
|---|---|---|---|---|---|
| single | 1415 | 0 (0.0%) | 5 (0.4%) | 24.5 ms | -12.0 ms |
| multi | 1415 | 0 (0.0%) | 0 (0.0%) | 16.6 ms | 931.2 ms |

**12 effects (`-sfwyvdjmbpnp`)**

| Model | Frames | Late frames | Negative headroom | Worst late | Worst headroom |
|---|---|---|---|---|---|
| single | 1415 | 1 (0.1%) | 596 (42.1%) | 41.7 ms | -35.8 ms |
| multi | 1415 | 0 (0.0%) | 0 (0.0%) | 16.5 ms | 926.5 ms |
