# L2jUnity — L2Topzone Vote Reward

Java **11** • Classic / High Five.

Copy `common/*.java` into `gameserver/java/l2topzone/` and `packs/unity/VoteRewardManager.java` into `gameserver/java/l2topzone/unity/`. Place the properties file in `gameserver/config/`.

Hook in `GameServer.java`:

```java
l2topzone.unity.VoteRewardManager.getInstance();
```

Voiced command: `.vote` (12h cooldown).
