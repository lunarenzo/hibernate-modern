# Hibernate Modern

A separate rewrite of Hibernate for Paper and Folia, compiled against Paper 1.20.6 with `--release 21`. The plugin contains no NMS, server patches, reflection, thread sleeps, forced garbage collection, or bundled runtime libraries.

**This build implements the approved idle chunk-cleanup fallback. Server ticks continue.** It does not promise to freeze entities, redstone, farms, or other plugins, and it does not change the server tick rate. Native tick suspension is deliberately outside this build until its public API and Folia behavior can be independently verified.

## Install and configure

1. Stop the server and remove the old Hibernate JAR. Keep a backup of its configuration.
2. Install `build/libs/Hibernate-3.0.0.jar` in the server's plugins directory.
3. Move the old Hibernate configuration aside so this build can create its own `plugins/Hibernate/config.yml`.
4. Start the server. Configuration is read asynchronously; commands become operational when the settings-loaded message appears in the log.

The default configuration starts cleanup after **ten continuous minutes with no online players**:

```yaml
enabled: true
empty-delay-seconds: 600
```

`sleepMillis` was a main-thread sleep duration in the old plugin. It never represented an empty-server countdown. This build rejects a file containing that key with a migration explanation instead of guessing its meaning. Do not increase watchdog timeouts to accommodate the old sleeping implementation.

## Commands

All commands require `hibernate.toggle`, which defaults to operators. Commands are registered through Paper's native command lifecycle.

| Command | Behavior |
|---|---|
| `/hibernate` or `/hibernate status` | Show the current state, tracked chunk count, and pending cleanup count |
| `/hibernate reload` | Validate and apply the file; restart the empty-server countdown |
| `/hibernate enable` | Persist `enabled: true`, apply the file, and restart the countdown |
| `/hibernate disable` | Persist `enabled: false` and cancel queued cleanup |
| `/hibernate toggle` | Persist the inverse of the active enabled setting and apply the file |

Configuration operations accept players and the local server console. A player recipient is remembered only by UUID and receives its completion message through the entity scheduler. Unsupported sender types can request status but cannot initiate configuration changes. No arguments now means **status**, whereas the old plugin toggled; toggling is explicit to prevent accidental state changes.

Only one configuration operation can be in flight. Further requests receive the configurable busy message immediately without growing a work queue. Reads, parsing, serialization, temporary file creation, and atomic replacement run on the async I/O scheduler. Invalid YAML, invalid values, unsupported config versions, oversized files, and legacy settings retain the previous active snapshot. Startup with an invalid configuration disables the plugin and logs the cause. Toggle operations validate before touching the file.

## Cleanup behavior and limits

The global scheduler checks server occupancy and the monotonic countdown. Joining invalidates queued cleanup immediately, even if the player leaves again before the next occupancy check. Reload starts a fresh countdown. Detection after a deadline can be late by the configured check interval or server scheduling delays, but the timer does not count game ticks as seconds.

The chunk index stores only a world UUID and chunk X/Z coordinates. Chunk load/unload events maintain a bounded round-robin index. Each check considers at most `chunk-batch-size` candidates and creates at most `max-pending-tasks` outstanding region tasks across the plugin. A chunk cannot be queued twice simultaneously. Each callback executes on the owning region and checks the generation again before requesting unloading. A callback that has already passed its final check may finish as a player joins; the server's public unload request retains responsibility for whether unloading is safe.

Forced chunks, plugin-ticketed chunks, and excluded worlds are skipped. The plugin neither removes tickets nor forcibly unloads/saves worlds. `unloadChunkRequest` is a request: the engine may decline or defer it. Candidates remain indexed until an unload event or stale-chunk check removes them, allowing later retry when tickets disappear. Once idle, batches continue at the configured check interval.

There is no global scan of loaded chunks, which would violate region ownership on Folia. Consequently, chunks loaded before listener registration are not discovered, and cache overflow evicts the oldest candidate without touching that chunk. Cleanup is best effort over observed chunks, not a guarantee that every loaded chunk is reclaimed. Exceeding the metadata limit reduces coverage rather than growing memory. Negative coordinates are already provided as chunk coordinates by the API and are preserved unchanged.

No player cache, database, custom executor, remote blacklist fetch, or per-tick repeating task is required. The default poll interval is 20 ticks. Candidate rotation updates existing list nodes without allocating new cache entries. An administrator can intentionally choose a shorter poll interval; cleanup callbacks and API-returned collections still have bounded allocation costs. TPS/MSPT improvements require measurement on the actual server and are not guaranteed.

On disable, listeners are unregistered, work is invalidated, tracked keys are cleared, region/entity handles are cancelled, and global/async tasks owned by the plugin are cancelled. Already running bounded filesystem operations can finish closing their own handles; their late results are discarded. The plugin does not retain live Player, World, Chunk, Entity, Inventory, or Location objects in its caches.

## Configuration keys

Missing keys inherit the bundled defaults in memory; reload does not rewrite the administrator's file. Enable/disable operations preserve the current YAML's supported content, subject to the server YAML serializer's formatting.

| Key | Default | Accepted values / purpose |
|---|---|---|
| `config-version` | `1` | Currently only 1 |
| `enabled` | `true` | Boolean; master switch |
| `empty-delay-seconds` | `600` | Integer 0–604800; zero starts immediately when empty |
| `check-interval-ticks` | `20` | Integer 1–1200; occupancy/countdown checks and cleanup batches |
| `unloadChunks` | `true` | Boolean; controls chunk unload requests |
| `blacklist` | `[]` | At most 128 installed plugin names; case insensitive; blocks cleanup; no URLs |
| `excluded-worlds` | `[]` | At most 128 world names; case insensitive |
| `chunk-batch-size` | `8` | Integer 1–64; candidates per check |
| `max-tracked-chunks` | `4096` | Integer 1–65536; strict metadata bound; evicts oldest candidate |
| `max-pending-tasks` | `32` | Integer 1–128; outstanding region cleanup work |
| `max-pending-replies` | `16` | Integer 1–64; outstanding player replies; excess replies are dropped |
| `messages.*` | See bundled config | All 14 message/state strings; MiniMessage; maximum 4096 characters each |

Names are at most 128 characters. Files and serialized output have a fixed 64 KiB safety ceiling. Reducing the chunk limit trims the cache immediately. Reducing a reply limit prevents further admission until existing replies finish; existing replies remain bounded by the previous validated limit. Blacklist matching is computed at startup/reload against installed plugin names, including disabled installed plugins; reload after changing installed plugins.

The complete default text for each message is in [`src/main/resources/config.yml`](src/main/resources/config.yml). `messages.status` supports `{state}`, `{tracked}`, and `{pending}`. State strings are editable independently.

## Build and verification

Use a JDK 21 or 25:

```powershell
.\gradlew.bat --no-daemon clean check build
```

```sh
./gradlew --no-daemon clean check build
```

The checksum-pinned Gradle 9.1 wrapper is included. `compileOnly` Paper API is the only production dependency and is supplied by the server, including Adventure/MiniMessage. The same Paper API is on the test classpath; no additional test framework dependency is introduced. `--release 21`, UTF-8, `-Xlint:all`, and `-Werror` are enforced. JAR entries use reproducible ordering/timestamps.

`verifyLogic` executes a dependency-free assertion runner. `test` depends on that runner; the conventional JUnit/TestNG task is deliberately disabled because no such framework is installed. `check` and `build` run the suite. CI runs the build on JDK 21 and 25, validates the wrapper through the Gradle setup action, and uploads each resulting plugin JAR as a workflow artifact. CI does not claim to boot Minecraft servers.

The suite covers the ten-minute deadline, monotonic clock wrapping, brief joins, enable/disable, blacklist states, concurrent cache admission/removal, eviction/shrink/shutdown, malformed and oversized YAML, atomic file cleanup, configuration persistence, region ownership, forced/plugin tickets, bounded queues, duplicate tasks, callback-before-handle races, player wakeup, reload cancellation, entity retirement and shutdown.

## Compatibility and deployment checks

The source uses only APIs declared in the Paper 1.20.6 baseline. There are no version-specific calls, so no version adapter or guessed version threshold is needed. The descriptor declares `api-version: '1.20'` and `folia-supported: true`. API declarations were checked against published Paper 1.21.1, 1.21.3–1.21.11, 26.1.2 and 26.2 pages and Folia 1.21.11/26.2 pages. Paper 1.21.2 and Folia 1.20.6 documentation paths returned 404. The independently unavailable documentation does not prove a runtime incompatibility, but it prevents claiming a completed verification for those paths. Future 26.x releases must be tested when published.

The local verification suite passed 57 assertions on JDK 21 and 25. API presence, compilation, and deterministic tests are distinct from live-server certification. Live Minecraft integration, the full server version matrix, and production load/heap profiling have not been run. Research evidence and detailed validation notes are local-only development files excluded from Git history.

Before production use on each required server distribution/version:

1. Boot with this JAR and confirm startup plus command registration; confirm no unsupported API/thread-ownership errors.
2. Temporarily set a short empty delay, reload, leave, and confirm the idle state after that delay. Rejoin during queued work and confirm normal joining.
3. Confirm forced chunks, a plugin-ticketed chunk, excluded worlds, `unloadChunks: false`, disabled mode, and blacklist mode are respected.
4. Reload valid/invalid files; restart after enable/disable to check persistence. Restore the desired delay.
5. Exercise exploration, player join/quit, world unload, and repeated config changes while checking bounded tracked/pending counts. Stop cleanly and inspect logs.
6. Profile with the actual player/plugin workload and RAM budget; monitor MSPT, TPS, chunk saves, and heap over time. This project does not certify a 1 GB Folia deployment or guarantee 20 TPS.

Do not use Bukkit `/reload` or third-party plugin hot-reload tools as a lifecycle test; use `/hibernate reload` for configuration and a server restart to change the JAR.
