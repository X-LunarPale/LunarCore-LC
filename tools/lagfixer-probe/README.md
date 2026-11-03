# LagFixer isolated-server probe

Diagnostic JavaPlugin source only; intentionally excluded from Gradle/settings and production jars. Compile with Java 25 against the existing Paper 26.3 API plus its API dependencies; no NMS compile dependency. Package compiled `com/lunarboreal/probe/LagFixerProbe.class` and `src/main/resources/plugin.yml` at jar root. Place only on a disposable isolated server with LunarCore. Do not install on production: seed writes an 11x11 stone platform near spawn, and cleanup removes newly spawned nearby entities.

No credentials or LBR private APIs are needed. `depend: [LunarCore]` ensures startup ordering. The probe uses Bukkit UUID lookup and read-only reflection of real CraftEntity/NMS goal lists, never reflects into module classes or assumes classloader sharing.

Console protocol (external controller waits for log markers):

1. Start with mob-ai and vehicle **disabled** and all other entity cleanup groups disabled. Configure mob-ai so CUSTOM-spawned COW is allowed (`spawn_reasons: []`, `list_mode: false`, no COW in list), `animals.breed.enabled: true`, `animals.tempt.enabled: true`. Set `pathfinder.list_mode: true`, `pathfinder.list: []` for a narrow goal-replacement test, `collides: true`, `silent: false`. vehicle `minecart.enabled: true`.
2. `lagfixerprobe seed` creates two adult cows and a regular empty minecart and saves UUIDs and original goal priority/class pairs. Require `SEED` and `ASSERT baseline minecart vanilla`, no `FAIL`.
3. Externally enable both groups through the existing performance Web/console controls and reload the performance module if necessary. Do not use a full server `/reload`. Wait several seconds for bounded chunk scan.
4. `lagfixerprobe optimized`: require `PASS optimized UUIDs=...`; assertions check same UUID lookup, exactly one live instance per UUID, real ReducedMinecart subclass and both reduced AI goals.
5. `lagfixerprobe breed`; wait 2–5 seconds, then `lagfixerprobe bred`. Require `BIRTH child=...` and positive birth assertion, proving real offspring and uncancelled EntityBreedEvent.
6. Externally disable/reload the performance module (both groups false). Wait for close or a few ticks.
7. `lagfixerprobe restored`: require `PASS restored UUIDs=...`, vanilla minecart class, original cows' goal classes/priorities, UUID uniqueness, `ASSERT restored entity scheduler accepts task`, then `PASS restored scheduler task executed`. A UUID-preserving but retired entity fails the scheduler check.
8. `lagfixerprobe cleanup`; stop server cleanly. Cleanup leaves the isolated stone platform.

Logs append to `plugins/LagFixerProbe/probe.log`; server output mirrors lines prefixed `LAGFIXER_PROBE`. Controller should fail on any `LAGFIXER_PROBE FAIL`, missing expected marker, unexpected entity disappearance, duplicate UUID, or JVM exception, and archive this log together with server latest.log. Individual command exceptions are caught/logged so the external controller can collect all evidence before stopping.

Boundaries: this initial probe verifies actual goal installation/restoration and breeding, not player lure behavior (requires a real player holding food), vehicle payload types (intentionally excluded by implementation), or world-unload cancellation ordering. Add passenger riding/velocity and entity-unload tests as separate runs after baseline succeeds. Avoid other plugins manipulating the probe cows/goals; such changes intentionally make exact restoration assertions fail.
