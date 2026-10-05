# Hibernate Modern

**Configurable idle chunk cleanup for Paper and Folia.**

Hibernate Modern is the LunaTech fork and rewrite of the original Hibernate plugin, maintained by **lunarenzo**. When your server has been empty for a configurable period, it requests unloading of eligible chunks in small, bounded batches. A player joining resets the countdown and invalidates queued cleanup.

The default delay is **10 minutes**. You can change the delay, reload the configuration, and enable or disable cleanup without restarting the server.

> **What “hibernate” means in this build:** idle chunk cleanup. Server ticks continue. This plugin does not suspend the server or promise to freeze farms, entities, redstone, or other plugins. Memory savings depend on which chunks the server can unload; TPS and CPU improvements are not guaranteed.

## Features

- Configurable empty-server countdown, measured in elapsed seconds.
- Configuration reload and persistent enable/disable commands.
- Paper and Folia scheduling, with chunk work dispatched to its owning region.
- Protection for forced chunks and chunks held by plugin tickets.
- World exclusions and an installed-plugin blacklist that blocks cleanup.
- Bounded chunk tracking, pending cleanup tasks, and player replies.
- Editable Adventure/MiniMessage messages.
- No required companion plugin, client mod, database, or bundled runtime library.

## Requirements and compatibility

| Requirement | Details |
|---|---|
| Server software | Paper or Folia; plain Spigot/Bukkit is not supported |
| Target Minecraft versions | 1.20.6, 1.21.1–1.21.11, and 26.1+ |
| Java | Java 21 bytecode; builds and logic checks run on JDK 21 and 25. Use the Java version required by your server release |
| Installation | Server-side plugin; players need no client installation |

These are compatibility targets, not a claim that every server version has been boot-tested. Live-server testing across the complete matrix and production load/heap profiling have **not** been run. Future 26.x releases need testing when available.

## Installation

1. Stop your server. If replacing the original Hibernate, remove its JAR and back up its configuration.
2. Obtain `Hibernate-3.0.0.jar` from a successful [GitHub Actions build](https://github.com/lunarenzo/hibernate-modern/actions), or build it using the instructions below.
3. Place the JAR in your server's `plugins/` directory. Move an old Hibernate configuration aside before the first start.
4. Start the server and wait for Hibernate's settings-loaded message.
5. Edit `plugins/Hibernate/config.yml`, then run `/hibernate reload`.

Use a server restart when replacing the JAR. Use Hibernate's own reload command when changing its configuration.

## Set the empty-server delay

For cleanup to begin after **10 continuous minutes** without players:

```yaml
enabled: true
empty-delay-seconds: 600
```

Examples: `300` is five minutes, `1800` is thirty minutes, and `0` allows cleanup at the next empty-server check. Apply changes with `/hibernate reload`.

A player joining resets the countdown, including a brief join between occupancy checks. Reloading also starts a fresh countdown. The deadline is checked at `check-interval-ticks`; busy servers may detect it later.

**Migrating from the original plugin:** `sleepMillis` controlled how long the old implementation slept a server thread. It was not an empty-server delay. Raising it to `600000` could block that thread for ten minutes and trigger watchdog reports. This rewrite has no thread-sleep mechanism and rejects configurations containing `sleepMillis`. Back up the old file and start with the new bundled configuration.

## Commands and permission

All commands require **`hibernate.toggle`**, granted to operators by default. Configuration changes can be requested by players or the local server console.

| Command | Purpose |
|---|---|
| `/hibernate` or `/hibernate status` | Show the state, tracked chunks, and pending cleanup tasks |
| `/hibernate reload` | Validate and apply configuration; restart the countdown |
| `/hibernate enable` | Save `enabled: true` and apply configuration |
| `/hibernate disable` | Save `enabled: false` and cancel queued cleanup |
| `/hibernate toggle` | Save and apply the opposite enabled setting |

Running `/hibernate` alone shows status; it does not toggle the plugin. Only one configuration operation runs at a time. Extra requests receive a busy message instead of entering a queue. An invalid reload leaves the previous active settings in place; an invalid startup configuration disables the plugin and logs the reason.

## Configuration reference

See the [complete default configuration](src/main/resources/config.yml) for editable messages and examples. Missing keys use bundled defaults in memory; reload does not rewrite the file.

| Key | Default | Purpose / accepted values |
|---|---|---|
| `config-version` | `1` | Configuration format; currently only `1` |
| `enabled` | `true` | Master cleanup switch |
| `empty-delay-seconds` | `600` | Empty-server delay; integer 0–604800 |
| `check-interval-ticks` | `20` | Check and batch interval; integer 1–1200 |
| `unloadChunks` | `true` | Allow chunk unload requests |
| `blacklist` | `[]` | Installed plugin names that block cleanup; no URLs |
| `excluded-worlds` | `[]` | World names excluded from cleanup |
| `chunk-batch-size` | `8` | Candidates considered per check; integer 1–64 |
| `max-tracked-chunks` | `4096` | Chunk metadata limit; integer 1–65536 |
| `max-pending-tasks` | `32` | Pending region cleanup limit; integer 1–128 |
| `max-pending-replies` | `16` | Pending player reply limit; integer 1–64 |
| `messages.*` | Bundled text | All 14 message/state strings; MiniMessage |

Blacklist and world names are case insensitive, with up to 128 entries per list and 128 characters per name. Blacklist matching includes disabled installed plugins and is refreshed at startup/reload. Each message allows up to 4096 characters. `messages.status` supports `{state}`, `{tracked}`, and `{pending}`.

Configuration files have a 64 KiB ceiling. Enable/disable/toggle writes preserve supported YAML content, though serializer formatting may change. Excess player replies are dropped when their queue limit is reached.

## How cleanup works

Hibernate tracks chunk load/unload events and rotates through observed candidates while idle. Before an unload request, it checks the owning region, current cleanup generation, world exclusions, forced status, and plugin tickets. It does not remove tickets or force unloading. The server may decline or defer a request.

Tracking has a fixed capacity. Overflow evicts metadata rather than unloading a chunk. Chunks loaded before listener registration are not discovered through a startup scan. Cleanup therefore covers observed, eligible chunks; it cannot guarantee removal of every loaded chunk.

Joining cancels queued cleanup, but a callback already past its final check may finish requesting an unload. The server remains responsible for whether that unload is safe. Protected or otherwise needed chunks can remain loaded normally.

## Troubleshooting and support

- **Cleanup has not started:** check `/hibernate status`, the delay, online players, `enabled`, `unloadChunks`, and blacklist matches.
- **Chunks remain loaded:** check forced chunks, plugin tickets, excluded worlds, and tracking coverage. An idle state does not mean every chunk is unloadable.
- **Reload failed:** read the console error and correct the YAML/value. Previous active settings remain in use.
- **Watchdog errors after upgrading:** ensure the old Hibernate JAR is removed and the server has restarted. This build does not sleep server threads; investigate the watchdog stack trace for the actual blocking work.

Report problems through [GitHub Issues](https://github.com/lunarenzo/hibernate-modern/issues), including the server build, Java version, plugin version, relevant configuration, and logs. Remove private information before posting.

## For developers

The Gradle group is `lunatech`; the Java base package is `lunatech.hibernate`. The plugin descriptor names `lunatech.hibernate.HibernatePlugin` as its entry point and `lunarenzo` as its author.

Build with JDK 21 or 25 using the included, checksum-pinned Gradle 9.1 wrapper:

```powershell
.\gradlew.bat --no-daemon clean check build
```

```sh
./gradlew --no-daemon clean check build
```

Output: `build/libs/Hibernate-3.0.0.jar`.

The project compiles against Paper 1.20.6 with `--release 21`, UTF-8, `-Xlint:all`, and `-Werror`. Paper supplies the production API and Adventure/MiniMessage. No additional test framework dependency is required. JAR entries use reproducible ordering and timestamps.

Under `src/main/java/lunatech/hibernate/`, `HibernatePlugin` wires the lifecycle; `config/` handles immutable settings and file I/O; `data/model/` holds state and identity records; `cache/` holds bounded chunk metadata; `service/` handles countdown and cleanup; `scheduler/` bounds task ownership; and `listener/`, `command/`, and `messaging/` handle server integration.

Configuration I/O runs asynchronously. Global tasks handle occupancy and countdowns, region tasks handle chunk operations, and entity tasks deliver player replies. Long-lived caches store identifiers rather than live server objects. Disable invalidates work, cancels owned tasks, unregisters listeners, and clears tracking.

`verifyLogic` runs the assertion suite. `test`, `check`, and `build` invoke it; the conventional framework-based test task is disabled. Tests cover countdown boundaries, brief joins, concurrent cache bounds, configuration persistence/errors, region ownership, protected chunks, task races, reload, and shutdown. GitHub Actions builds with JDK 21 and 25 and uploads the JARs; it does not boot Minecraft servers.

Before production deployment, test startup, joining during cleanup, protected chunks, reload failures, disable/re-enable, and shutdown on the intended server build. Measure memory, MSPT, and TPS with the actual workload. A 1 GB deployment or 20 TPS is not certified by these logic tests.

## Publishing descriptions

### Short description

> Configurable idle chunk cleanup for Paper and Folia.

### Modrinth description

The introduction, Features, Requirements, Installation, delay example, and Commands sections above provide an owner-facing Markdown description. Include the cleanup limitation and compatibility-testing qualification when copying them. Replace the repository-relative configuration link with [this source link](https://github.com/lunarenzo/hibernate-modern/blob/main/src/main/resources/config.yml) if you include the configuration reference.

**Development disclosure:** substantial portions of this rewrite and its documentation were produced with AI assistance under human direction. This is not a claim of Modrinth publication eligibility. Modrinth's [AI disclosure and usage policy](https://support.modrinth.com/en/articles/16551575-disclosure-and-usage-of-ai) requires disclosure for significant AI-generated content and restricts projects that are entirely or almost entirely AI-generated with little human input beyond prompting and testing. Review the project against that policy before submitting it. Modrinth supports [GitHub-flavored Markdown](https://support.modrinth.com/en/articles/8801962-advanced-markdown-formatting).

### SpigotMC description (BBCode)

SpigotMC supports [BBCode formatting](https://www.spigotmc.org/help/bb-codes). The following copy states the required server software even when published on SpigotMC:

```text
[B]Hibernate Modern[/B]
Configurable idle chunk cleanup for Paper and Folia.

The LunaTech fork and rewrite of Hibernate, maintained by lunarenzo. After your server has been empty for a configurable period, Hibernate requests unloading of eligible chunks in small batches. The default delay is 10 minutes. Joining resets the countdown and invalidates queued cleanup.

[B]What it does[/B]
This build performs idle chunk cleanup. Server ticks continue. It does not pause the server or guarantee that farms, entities, redstone, or other plugins stop. Memory savings depend on eligible chunks; TPS and CPU improvements are not guaranteed.

[B]Features[/B]
[LIST]
[*]Configurable empty-server delay and reloadable configuration.
[*]Persistent enable, disable, and toggle commands.
[*]Paper/Folia scheduling with chunk work on its owning region.
[*]Forced chunks and plugin-ticketed chunks are protected.
[*]World exclusions, plugin blacklist, and bounded tracking/tasks.
[*]Customizable MiniMessage messages; no client mod required.
[/LIST]

[B]Requirements[/B]
Paper or Folia. Plain Spigot/Bukkit is not supported.
Targets: Minecraft 1.20.6, 1.21.1–1.21.11, and 26.1+.
Java 21 bytecode; use the Java version required by your server release.
The full live-server version matrix and production load tests have not been run. Future releases require testing.

[B]Setup[/B]
Stop the server, remove the old Hibernate JAR, back up its configuration, and install Hibernate-3.0.0.jar in plugins/. Start with the new configuration. For a ten-minute empty-server delay:
[CODE]
enabled: true
empty-delay-seconds: 600
[/CODE]
Apply changes with /hibernate reload. Reload starts a fresh countdown.
The old sleepMillis setting was a thread-sleep duration, not an empty-server delay. It is rejected by this rewrite.

[B]Commands[/B]
/hibernate status — state and tracked/pending counts
/hibernate reload — validate and apply configuration
/hibernate enable — persistently enable cleanup
/hibernate disable — persistently disable cleanup
/hibernate toggle — persistently toggle cleanup
Permission: hibernate.toggle (operators by default).

Cleanup is best effort over observed chunks. Forced chunks, plugin tickets, exclusions, and server unload decisions can keep chunks loaded. Invalid reloads preserve active settings.

[B]Development disclosure[/B]
Substantial portions of the rewrite and documentation were produced with AI assistance under human direction.

[URL=https://github.com/lunarenzo/hibernate-modern]Source and builds[/URL]
[URL=https://github.com/lunarenzo/hibernate-modern/issues]Report an issue[/URL]
```
