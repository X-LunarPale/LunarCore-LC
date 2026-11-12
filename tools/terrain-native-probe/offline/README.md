# Offline Saved-Chunk Inspection

`TerrainRegionRead.java` reads existing Paper 26.3 region files without starting a server. It uses Paper's `RegionFileVersion`, `NbtIo`, and `SimpleBitStorage` for compression, NBT, palettes, and heightmaps. Region files are opened with `RandomAccessFile(..., "r")`; the tool does not write to the save.

Save and flush the target chunks before inspection, and avoid concurrent writes to their region files. The arguments are the region directory, minimum Y, total height, and one or more `label:x:z[:floorY]` targets. The optional floor Y adds the exact block states at that Y and the two blocks above it.

Build the classpath from the matching Paper runtime JAR and its libraries. This example writes compiled classes into the repository's ignored artifacts directory:

```powershell
$probeBuild = Join-Path (Get-Location) 'artifacts/terrain-native/region-reader-classes'
New-Item -ItemType Directory -Force -Path $probeBuild | Out-Null
$probeClasspath = @($probeBuild, 'E:/LC-Test-26.3/versions/26.3/paper-26.3.jar') +
    @(Get-ChildItem -LiteralPath 'E:/LC-Test-26.3/libraries' -Filter '*.jar' -Recurse |
        Where-Object { $_.Name -notmatch '^log4j-slf4j2-impl-' } |
        ForEach-Object { $_.FullName })
& 'D:/dsh work/_tools/jdk-25.0.4.1+1/bin/javac.exe' '-encoding' 'UTF-8' '-cp' ($probeClasspath -join ';') '-d' $probeBuild 'tools/terrain-native-probe/offline/TerrainRegionRead.java'
& 'D:/dsh work/_tools/jdk-25.0.4.1+1/bin/java.exe' '-cp' ($probeClasspath -join ';') 'TerrainRegionRead' 'E:/LC-Test-26.3/world/dimensions/lc_terrain/worldtext/region' '-64' '1024' 'spawn_candidate:-29791:-30831:473'
```

The JSON reports chunk status, target-column surface and biome, heightmaps, block and biome totals, section palettes, and water, ice, lichen, lantern, and chain observations. Missing or invalid chunks appear as per-target errors. Check for errors and `minecraft:full` before treating a result as evidence of completed generation.

Counts cover the entire containing chunk. Surface means the highest non-air block, so leaves and thin snow count. The water-with-roof predicate only checks for a non-air, non-water, non-lava block at least four blocks above in the same column. Neither this predicate nor a decoration's presence proves its generating feature or the surrounding terrain shape.
