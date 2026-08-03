# L2jFrozen — L2Topzone Vote Reward

Java **8** • Interlude.

Copy `common/*.java` into `gameserver/head-src/com/l2topzone/` (or anywhere on classpath, e.g. `gameserver/head-src/l2topzone/`). Place `packs/frozen/VoteRewardManager.java` next to it under `l2topzone/frozen/`.

> **Note**: Frozen ships pre-Java 8 idioms in places. The integration uses only Java 8 syntax and the standard library.

Hook in `gameserver/head-src/com/l2jfrozen/gameserver/GameServer.java`:

```java
l2topzone.frozen.VoteRewardManager.getInstance();
```

## HWID

Vanilla Frozen does **not** expose HWID. The manager logs a one-line warning at boot and continues without HWID checks. If your fork added HWID via `client.getHWid()`, it is auto-detected through reflection.

Voiced command: `.vote` (12h cooldown).
