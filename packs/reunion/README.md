# L2jReunion — L2Topzone Vote Reward

Java **8** • Interlude / High Five (`l2r.gameserver` namespace).

Copy `common/*.java` and `packs/reunion/VoteRewardManager.java` into your gameserver `java/` source root preserving the `l2topzone/` and `l2topzone/reunion/` package structure.

Hook in `GameServer.java`:

```java
l2topzone.reunion.VoteRewardManager.getInstance();
```

Voiced command: `.vote` (12h cooldown). HWID is reflection-probed (`client.getHWid()`); skipped silently if unavailable.
