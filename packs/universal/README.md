# Universal — L2Topzone Vote Reward

**One JAR for any L2j fork.** Java **8** bytecode, so it runs on every JVM from 8 upward.

Use this build when your pack is not in the supported matrix, or when a pack-specific
JAR reports it cannot find a class on your revision.

## Why it works anywhere

This build imports **nothing** from the server pack. It resolves everything at boot:

| What it needs | How it finds it |
|---|---|
| Pack root package | Reads the caller's package off the call stack — your `GameServer` calls us, so its package *is* the answer |
| `VoicedCommandHandler` | Tried under each candidate root: `<root>.gameserver.handler.…`, `<root>.game.handler.…`, `<root>.handler.…` |
| `IVoicedCommandHandler` | **Never guessed** — read from the parameter type of the registry's own `registerHandler` method, then implemented with a `java.lang.reflect.Proxy` |
| Online player list | `World` / `L2World` → `getPlayers()` or `getAllPlayers()`, accepting either a `Collection` or a `Map` |
| Item delivery | Best matching `addItem(...)` overload on the actual player class (`int` or `long` count, with or without a reference argument) |
| Scheduling | Its own daemon thread — no dependency on the pack's thread pool |

Because the command interface is taken from the registry's signature rather than a
hard-coded name, the integration is correct by construction on any pack that follows
the standard L2j handler shape.

## Forks in the built-in fallback list

`net.sf.l2j` (aCis all revisions, L2jLisvus, L2jArchid) · `org.l2jmobius` · `com.l2jserver` ·
`com.l2jfrozen` · `org.l2junity` · `l2r` (Reunion, Sunrise) · `com.l2jhellas` · `com.l2jorion` ·
`l2e` (Energy, Eternity) · `com.l2jfree` · `com.l2jdream` · `com.l2jbrasil` · `com.l2jprime` ·
`com.l2jarchid` · `com.l2jteon` · `ru.catssoftware`

The list is only a fallback. Auto-detection from the call stack handles **private forks
with renamed packages** too, so a fork not listed here is still expected to work.

## Install

1. Drop `l2topzone-vote-reward-universal.jar` into your gameserver's `libs/` folder.
2. Copy `L2TopzoneVoteReward.properties.template` to `config/L2TopzoneVoteReward.properties` and set `ApiKey`.
3. Add one line to `GameServer.java`, near the other `getInstance()` calls:

```java
l2topzone.universal.VoteRewardManager.getInstance();
```

> Call it from `GameServer` (or any class in your pack's own package). Detection reads
> the calling class's package — invoking it from a class outside the pack namespace
> falls back to the built-in list instead.

4. Restart. The boot log tells you exactly what was detected:

```
[L2Topzone] Universal build attached to 'com.l2jorion.gameserver' via VoicedCommandHandler.registerHandler(IVoicedCommandHandler)
[L2Topzone] universal vote-reward manager ready. Command(s): .vote
```

## If detection fails

The log names what it tried. Set the root package explicitly in the properties file —
the part of your package before `.gameserver`:

```properties
PackBasePackage = com.l2jorion
```

Two failure modes are reported separately:

| Log line | Meaning |
|---|---|
| `Could not locate VoicedCommandHandler` | No registry found. Set `PackBasePackage`. |
| `Global milestone rewards are inactive (…)` | The registry was found and `.vote` works; only the world lookup failed. Set `GlobalEnabled = false` to silence, or report your pack. |

The second is a partial-success state by design: individual rewards keep working even
when the global poller cannot enumerate players.

## Trade-off vs. a pack-specific JAR

| | Universal | Pack-specific |
|---|---|---|
| Works on unlisted forks | ✅ | ❌ |
| Survives pack refactors | ✅ | Only if method names hold |
| Errors surface | At boot, in the log | At compile time |
| Global reward on odd world APIs | May degrade | Guaranteed |

If your pack **is** in the matrix, prefer its dedicated JAR.
