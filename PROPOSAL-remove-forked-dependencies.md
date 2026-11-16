# Proposal: Switch from FireMasterK Forks to Upstream TeamNewPipe Dependencies

## Status

Implemented in this pull request.

The initial implementation used NewPipeExtractor `v0.26.3`. It was
subsequently updated to `v0.27.1`, together with the small compatibility
changes required by the breaking changes in NewPipeExtractor `v0.27.0`.

## Summary

Piped-Backend currently depends on personal forks of two libraries maintained by Kavin (FireMasterK):

```gradle
implementation 'com.github.FireMasterK:NewPipeExtractor:c83884eb5d...'
implementation 'com.github.FireMasterK:nanojson:a507525e549a...'
```

This document proposes switching to the official upstream repositories:

```gradle
implementation 'com.github.TeamNewPipe:NewPipeExtractor:v0.27.1'
implementation 'com.github.TeamNewPipe:nanojson:c7a6c1c08d16b6d5ecded34758e6415e07be2166'
```

## The Problem

The current setup creates a **single-person bottleneck**:

- Both dependencies are pinned to commit SHAs on Kavin's personal forks.
- Renovate is explicitly disabled for these deps (`renovate.json` has `"enabled": false` for `com.github.firemasterk.*` and `ignoreDeps` for the extractor).
- Any breakage requires Kavin to update his fork, rebase on upstream, and push — before anyone else can bump the hash.
- The fork `dev` branch is currently **96 commits behind** upstream `TeamNewPipe/NewPipeExtractor:dev`.
- Piped misses upstream improvements (visionOS client fallback, lockup extractor fixes, livestream handling, SoundCloud fixes, etc.)

## Why v0.27.1 Is Required

Updating beyond `v0.26.3` is not merely a version bump. NewPipeExtractor
`v0.27.0` contains the fix for a comment-avatar extraction problem that was
observed on a self-hosted instance in November 2026:

- Both the official Piped-Backend image (using the FireMasterK extractor) and
  a build using upstream NewPipeExtractor `v0.26.3` returned
  `"thumbnail": null` for every sampled comment — 20 out of 20 comments on
  several unrelated videos.
- Piped-Backend maps an empty extractor avatar list to `null` in
  `URLUtils.getLastThumbnail()`.
- The Piped web frontend tolerates a missing avatar and still displays the
  comment text, but LibreTube models the field as a non-null Kotlin
  `String`. One `null` avatar therefore caused the whole
  comments page to fail kotlinx.serialization parsing, resulting in a blank
  comments sheet even though the API returned HTTP 200 with comments.
- YouTube has been A/B testing a comment payload without the usual avatar
  object. The old YouTube EUVM comment extractor required that object, so
  `getUploaderAvatars()` returned an empty list.
- NewPipeExtractor `v0.27.0` fixes this with [PR #1525](https://github.com/TeamNewPipe/NewPipeExtractor/pull/1525), *Fallback to
  `avatarThumbnailUrl` for comment thumbnails*, and [PR #1526](https://github.com/TeamNewPipe/NewPipeExtractor/pull/1526), which detects
  the comment avatar size dynamically. [`v0.27.1`](https://github.com/TeamNewPipe/NewPipeExtractor/releases/tag/v0.27.1) is the current maintenance
  release on top of [`v0.27.0`](https://github.com/TeamNewPipe/NewPipeExtractor/releases/tag/v0.27.0).

With `v0.27.1`, the same test video returned 20 comments with no `null`
thumbnails, and LibreTube's own `CommentsPage` model successfully parsed the
response.

Merely changing Piped-Backend to emit an empty string for a missing avatar
would have worked around LibreTube's parsing failure, but it would not have
restored the missing avatars. Updating the extractor fixes the source data.

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

## NewPipeExtractor v0.27.x Compatibility

NewPipeExtractor `v0.27.0` includes several breaking changes. They were
checked against this codebase, and the required adaptations are included in
this pull request.

### Java 17 source/target compatibility

Upstream increased its Java source and target compatibility from 11 to 17.

**Impact on Piped-Backend**: None. Piped-Backend already uses Java 21 and
builds/runs with Temurin 21.

### `Description` converted to a record

`Description.getContent()` was replaced by the record accessor
`Description.content()`.

Updated call sites:

- `StreamHandlers.java`: two `Description::getContent` method references
  changed to `Description::content` (comments and comment next-page handlers).
- `CollectionUtils.java`: `metaInfoItem.getContent().content()` and
  `info.getDescription().content()`.
- `PlaylistHandlers.java`: `info.getDescription().content()`.

### Removed PoToken client methods

NewPipeExtractor removed the Android, iOS, and WebEmbed PoToken methods
because PoTokens can no longer be generated for those clients. The
`PoTokenProvider` interface now only exposes `getWebClientPoToken(String)`.

Piped-Backend's `BgPoTokenProvider` contained three overrides for the
removed methods, all of which simply returned `null`:

- `getWebEmbedClientPoToken`
- `getAndroidClientPoToken`
- `getIosClientPoToken`

Those dead overrides were removed. The existing Web client PoToken
implementation, which is the one backed by the BG helper, is unchanged.

A source search found no backend calls to the other APIs removed in
`v0.27.0`, including `forceFetchIosClient`, the Android/iOS user-agent
helpers, the Android/iOS/WebEmbed streaming-URL helpers, or
`InnertubeClientRequestInfo.ofAndroidClient` / `ofIosClient` /
`ofWebEmbeddedPlayerClient`.

### Removed clients and stricter HTTP validation

Also relevant from the `v0.27.0` release notes:

- Broken YouTube clients were removed and client constants updated.
- HTTP response codes are now validated across the extractor.

These changes cannot be validated by compilation alone. For that reason,
streams, comments, comment pagination, trending, search, channels, and
SponsorBlock endpoints were exercised against a running `v0.27.1` backend
before deployment; see **Testing** below.

## What We Gain by Switching

1. **126 upstream commits between `v0.26.3` and `v0.27.1`**, including live-chat comments support, podcasts/courses channel tabs, protobuf and stream-parsing updates, and the comment-avatar fixes described above.
2. **Restored comment avatars and LibreTube comments** on instances whose YouTube comment payload no longer contains the old avatar object.
3. **Renovate can auto-track updates** — no manual hash bumping required.
4. **No single-person bottleneck** — upstream has multiple active contributors and a release cadence.
5. **Latest release** (v0.27.1, 10 Nov 2026) is well-tested by the NewPipe user base. On top of `v0.27.0`, `v0.27.1` fixes SoundCloud `aac_96k` streams and Bandcamp search/client verification, and bumps protobuf and jsoup.

## The Change

### `build.gradle`

```diff
-    implementation 'com.github.FireMasterK:NewPipeExtractor:c83884eb5d2e9f077348acec3a2d9e9dc920ae91'
-    implementation 'com.github.FireMasterK:nanojson:a507525e549a836c3a8b6ab7090dca38e92942ef'
+    implementation 'com.github.TeamNewPipe:NewPipeExtractor:v0.27.1'
+    implementation 'com.github.TeamNewPipe:nanojson:c7a6c1c08d16b6d5ecded34758e6415e07be2166'
```

### Compatibility changes for v0.27.x

```text
StreamHandlers.java    Description::getContent -> Description::content (2 sites)
CollectionUtils.java   getContent().getContent() -> getContent().content()
                       getDescription().getContent() -> getDescription().content()
PlaylistHandlers.java  getDescription().getContent() -> getDescription().content()
BgPoTokenProvider.java remove 3 overrides for PoToken methods removed upstream
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

If a deployed `v0.27.1` build regresses, the operational rollback is to
point the instance back to the official
`docker.io/1337kavin/piped:latest` image and restart the backend service.
No database change is required for this dependency update.

## Testing

This change was validated at three levels:

### 1. Compilation

- `./gradlew compileJava` initially produced exactly eight errors: five
  `Description.getContent()` call sites and three removed
  `PoTokenProvider` overrides.
- After the compatibility changes above, compilation succeeds with no
  remaining references to the removed APIs.
- The GitHub **CI** workflow and the multi-architecture **GHCR Docker
  build** both succeed for the `v0.27.1` commit.

### 2. Running backend comparison (self-hosted instance, ARM64)

A `v0.27.1` image was built and run in parallel with the then-current
official backend, using an equivalent test configuration with background
timers disabled. Results for the same requests:

| Endpoint | Official backend (FireMasterK extractor) | Upstream v0.27.1 |
|---|---|---|
| `GET /comments/qr57Gm67jQY` | 20 comments, **20 null thumbnails** | 20 comments, **0 null thumbnails** |
| `GET /nextpage/comments/qr57Gm67jQY` | — | 20 comments, **0 null thumbnails** |
| `GET /comments/dQw4w9WgXcQ` | — | 20 comments, **0 null thumbnails** |
| `GET /streams/qr57Gm67jQY` | 1 video stream, 0 audio streams, no HLS | **16 video streams, 5 audio streams, HLS present** |
| `GET /streams/dQw4w9WgXcQ` | — | 17 video streams, 5 audio streams, HLS present |
| `GET /trending?region=IN` | 15 items | 15 items |
| `GET /search?q=Be+Smart&filter=videos` | 20 items | 20 items |
| `GET /channel/UCH4BNI0-FOK2dMXoFtViWHw` | 200, channel data present | 200, channel data present |
| `GET /sponsors/{videoId}` | 200 | 200 |

Startup showed no new Liquibase change sets applied (0 run), and no
extractor-related exceptions were logged while exercising the endpoints
above.

### 3. Client-side parsing (LibreTube)

The exact JSON bytes returned by each backend for
`/comments/qr57Gm67jQY` were deserialized with LibreTube v32.1's own
`CommentsPage` model and JSON configuration:

- Official/`v0.26.3` response: **failed** with
  `JsonDecodingException: Expected string literal but 'null' was found at
  path: $.comments[0].thumbnail`.
- `v0.27.1` response: **parsed successfully** with all 20 comments.
