# Proposal: Switch from FireMasterK Forks to Upstream TeamNewPipe Dependencies

## Summary

Piped-Backend currently depends on personal forks of two libraries maintained by Kavin (FireMasterK):

```gradle
implementation 'com.github.FireMasterK:NewPipeExtractor:c83884eb5d...'
implementation 'com.github.FireMasterK:nanojson:a507525e549a...'
```

This document proposes switching to the official upstream repositories:

```gradle
implementation 'com.github.TeamNewPipe:NewPipeExtractor:v0.26.3'
implementation 'com.github.TeamNewPipe:nanojson:c7a6c1c08d16b6d5ecded34758e6415e07be2166'
```

## The Problem

The current setup creates a **single-person bottleneck**:

- Both dependencies are pinned to commit SHAs on Kavin's personal forks.
- Renovate is explicitly disabled for these deps (`renovate.json` has `"enabled": false` for `com.github.firemasterk.*` and `ignoreDeps` for the extractor).
- Any breakage requires Kavin to update his fork, rebase on upstream, and push — before anyone else can bump the hash.
- The fork `dev` branch is currently **96 commits behind** upstream `TeamNewPipe/NewPipeExtractor:dev`.
- Piped misses upstream improvements (visionOS client fallback, lockup extractor fixes, livestream handling, SoundCloud fixes, etc.)

## What the Fork Actually Changes

The pinned extractor commit (`c83884eb5d`) carries **3 functional patches** on top of upstream:

### Patch 1: Dynamic Itag Inference (`93e4a1c`)

Adds an overloaded `ItagItem.getItag(int, int, int, String, String)` that dynamically constructs `ItagItem` objects from YouTube format metadata (mimeType, fps, qualityLabel) instead of relying on the hardcoded itag list.

**Impact on Piped-Backend**: None. Piped-Backend never calls this method directly. It only iterates streams the extractor returns via standard APIs (`stream.getItag()`, `stream.getItagItem()`). With upstream, if YouTube introduces a new itag before it's added to the hardcoded list, that particular stream would be skipped — but all known/common formats work.

### Patch 2: Like-Count for Videos with No Likes (`cabd55e`)

The fork returns `0` when the like count can't be parsed. Upstream throws a `ParsingException`.

**Impact on Piped-Backend**: None. The extractor's own `StreamInfo` class already wraps `getLikeCount()` in a try-catch:

```java
try {
    streamInfo.setLikeCount(extractor.getLikeCount());
} catch (final Exception e) {
    streamInfo.addError(e);
}
```

If upstream throws, the error is silently caught, and `likeCount` defaults to `-1` (meaning "unavailable"). Piped-Backend passes this through as-is. The difference is cosmetic: fork shows `0`, upstream shows `-1`.

### Patch 3: LazyString / nanojson Switch (`215b591`)

Switches the nanojson dependency _inside_ the extractor from `TeamNewPipe:nanojson` to `FireMasterK:nanojson`, and bumps Java toolchain to 21.

**Impact on Piped-Backend**: None. This commit only exists to align the extractor with Kavin's nanojson fork. When using upstream extractor, upstream nanojson is used automatically. Piped-Backend uses standard nanojson APIs (`JsonObject`, `JsonWriter`, `JsonParser`, `JsonParserException`) that are identical in both versions.

## Why This is Safe: NewPipe Proves It

**NewPipe** — the Android app with millions of users — uses upstream `TeamNewPipe/NewPipeExtractor` directly. It works without any of these fork patches. Every YouTube video, livestream, comment section, and channel that NewPipe handles goes through the same upstream code we'd be switching to.

## Code-Level Verification

A search of the entire Piped-Backend source code (`src/`) confirms:

| Fork-specific API | Called by Piped-Backend? |
|---|---|
| `ItagItem.getItag(int, int, int, String, String)` | No |
| Any `FireMasterK/nanojson`-specific method | No |
| Any fork-only class or interface | No |

Piped-Backend uses only standard extractor APIs: `StreamInfo.getInfo()`, `stream.getItag()`, `stream.getItagItem()`, `info.getLikeCount()`, `stream.getContent()`, etc. — all present and unchanged in upstream.

## What We Gain by Switching

1. **40+ upstream commits** currently missing: visionOS client fallback, improved lockup extraction, better livestream/HLS handling, SoundCloud fixes, dependency bumps, protobuf-based audio track parsing, and more.
2. **Renovate can auto-track updates** — no manual hash bumping required.
3. **No single-person bottleneck** — upstream has multiple active contributors and a release cadence.
4. **Latest release** (v0.26.3, June 9 2026) is well-tested by the NewPipe user base.

## The Change

### `build.gradle` (2 lines)

```diff
-    implementation 'com.github.FireMasterK:NewPipeExtractor:c83884eb5d2e9f077348acec3a2d9e9dc920ae91'
-    implementation 'com.github.FireMasterK:nanojson:a507525e549a836c3a8b6ab7090dca38e92942ef'
+    implementation 'com.github.TeamNewPipe:NewPipeExtractor:v0.26.3'
+    implementation 'com.github.TeamNewPipe:nanojson:c7a6c1c08d16b6d5ecded34758e6415e07be2166'
```

### `renovate.json` (remove fork-specific overrides)

```diff
-    {
-      "matchPackagePrefixes": [
-        "com.github.firemasterk."
-      ],
-      "groupName": "Personal Forks",
-      "enabled": false
-    }
   ],
-  "ignoreDeps": [
-    "com.github.FireMasterK.NewPipeExtractor:NewPipeExtractor"
-  ]
+  "ignoreDeps": []
```

## Emergency Fallback Plan

If YouTube ever breaks something and upstream is slow to respond:

1. Fork `TeamNewPipe/NewPipeExtractor` under **TeamPiped** (org-level, not personal).
2. Cherry-pick or push a hotfix.
3. Temporarily point the dependency to `TeamPiped` fork.
4. Switch back to upstream once they merge the fix.

This keeps the default path on upstream while preserving the ability to hotfix when needed — without depending on any single person's bandwidth.

## Testing

This change has been validated by:
- Building the project with upstream dependency substitution (Gradle `dependencyInsight` + `compileJava`).
- Confirming zero compile errors.
- Deploying a test build to a self-hosted instance.
