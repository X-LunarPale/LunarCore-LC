# Paper port modifications

Paper integration and LunarCore management additions: RayT. Original LunarCore and YUNG project authorship and licenses are retained; this port does not claim authorship of either upstream project.

Original, unmodified sources, licenses and commit manifests: `third-party/yungs/`. Adapted sources and resources in this project are the build inputs. `tools/prepare-yungs-port.py` and `tools/adapt-yungs-26-3.py` are import/migration helpers, not a replacement for the semantic Java adaptations; do not run an import over reviewed changes.

- Replace Fabric/NeoForge registration and Mixin hooks with Paper bootstrap, datapack discovery, per-world generator overlays, checked native accessors and events. No bytecode agent or mod loader is required.
- Keep vanilla codec IDs stable. Custom codecs remain registered regardless of the generation switches so old saves can still load.
- Adapt 26.3 registry codecs, feature interfaces, density sampling, block collections, entity types, predicates and structure-piece serialization. Keep original templates, algorithms and loot behavior where the platform permits.
- Run entity processors on private template copies. Retain enhanced structure terrain adaptations, fortress spawn regions, Pharaoh state and monument tridents.
- Port End fight/spikes/platform/gateway behavior with vanilla network types and persistent per-dimension state. Gateway block entities save with the vanilla type. Apply additional dragon block protection only to converted End worlds, instead of changing the global vanilla immunity tag.
- Encode randomized pot sherds as the 26.3 named-side map without changing RNG order. Remove 40 default-valued Forge-only attribute entries from 20 adapted templates; these otherwise invalidate the vanilla attribute list. Preserve all Minecraft attributes. `TemplateMigration` in the acceptance tools performs this guarded offline migration; untouched originals remain available.
- Add immutable startup policies, revisioned Web controls and audit integration.
- Omit optional integrations requiring other mods. Existing End islands and generated terrain are preserved.
- Bound mineshaft decoration/spawner access to the current chunk. Resolve the 49-block entrance search once against the native base-terrain height sampler instead of reading neighbouring mutable feature chunks outside Paper's ownership radius.

- Port YUNG's Extras and Bridges to the 26.3 `Feature` interface and `worldgen/feature` data. Biome modifiers become per-world additions in `WorldStructures.prepareFeatures`, sorted so `FeatureSorter` sees one order. Extras also removes the vanilla desert well where upstream does.
- Skip bridge candidates that extend past the feature write range; upstream silences the far-chunk error and leaves a cut-off bridge. Read a far bank just outside that range from the noise sampler, not from a chunk that may still be generating. Place bridges with known shapes and no waterlogging pass, because both also read past the template; the templates sit above the water. The `SuppressLogMixin` is not needed.

Validation results and material limits: `docs/en-US/STRUCTURES-VALIDATION.md` and `docs/zh-CN/STRUCTURES-VALIDATION.md`.
