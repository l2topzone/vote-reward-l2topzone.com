# L2jServer / Sunrise — L2Topzone Vote Reward

Java **8** • Interlude / High Five.

Use this variant for builds rooted at `com.l2jserver.gameserver.*`.
For Sunrise builds that use the `l2r.gameserver.*` namespace, use the **Reunion** variant instead — they're API-compatible.

Hook in `GameServer.java`:

```java
l2topzone.l2jserver.VoteRewardManager.getInstance();
```

Voiced command: `.vote` (12h cooldown).
