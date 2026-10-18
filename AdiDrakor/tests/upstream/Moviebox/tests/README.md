# Moviebox H5 playback regression

Run `python Moviebox/tests/run_regression.py` from the repository root with Java 17 and Python 3.
The runner compiles production against the real CloudStream SDK **without shims**, then separately
runs the JVM fixtures. Optional `CLOUDSTREAM_JAR` selects the Gradle SDK jar; `MOVIEBOX_TEST_CACHE`
selects a test-only dependency cache. Android/app/link-builder shims are never packaged.
Fixtures are sanitized and do not establish live service or Android playback success.

## Current behavior — plugin version 15

- Media uses **H5 only**: `subject/play` with the exact subjectId, season, episode, detailPath,
  streamSignType and codec flags. No requests to mobile `play-info`, `play-info/v2`, or media
  extraction from native resourceDetectors remain in the resolver.
- Mobile authentication, detail metadata and caption endpoints remain necessary to resolve
  H5 detailPath/server-declared dubbing and preserve subtitles. They are not media sources.
- Metadata tries the modern profile, the established catalog profile if needed, then public
  H5 detail for the **same subject ID**. No title search/fuzzy match or episode substitution.
  An explicit detailPath takes precedence over a stale detailUrl. Flat mobile and nested H5
  subject metadata are checked for the exact identity.
- All reference H5 routing aliases remain eligible if earlier aliases fail or return no usable
  streams, even after another audio subject succeeds. The prior two-alias cap is removed;
  a successful alias is preferred for later audio subjects. The first usable alias supplies
  all its DASH/HLS/direct choices; aliases are not invented as separate video servers.
- Playback audio has an explicit ORIGINAL/INDONESIAN/ENGLISH/UNSUPPORTED whitelist.
  The selected subject remains the default/original source. Only explicitly labelled original,
  Indonesian or English entries from `dubs[]` are requested; unknown/unlabelled dubs are skipped.
  Matching is exact after removing whole-word dub/audio decoration, never substring matching.
  APK codes `in_id`/`ind` and `en`/`eng`, and Indonesian/English display aliases are accepted.
- H5 stream `lanName`/`lan`/`language` are checked before media validation and callback. Missing
  stream language inherits only its proven selected-original or whitelisted-dub context.
  A non-English original language is retained as Asli when it matches same-subject metadata
  (including the selected ID's entry in `dubs[]`); contradictory/unsupported languages are rejected.
  Unknown dub metadata is never turned into Asli. Captions have **no audio language filter**.
- Labels use `MovieBox • Asli • Video • H.264`, `MovieBox • Indonesia • DASH • H.265`, etc. Language and codec names
  are normalized, repeated "dub Audio" removed, and quality is left to CloudStream's display.
  Choices are ordered by audio group, descending quality and compatible codec preference.
  Distinct CDN alternatives with otherwise identical labels receive a server suffix.
- Each signed request is validated independently. After validation only, signature rotations
  for the same resource/audio are deduplicated. The actual signed URL is never rewritten;
  content selectors and unknown token parameters remain significant. Explicit quality and codec
  also remain part of the identity, preserving variants sharing a media URL.
- Existing GET/Range bounds, manifest/segment checks, signed Cookie/token scoping, DRM rejection
  and both caption endpoints are preserved. Caption failure never invalidates usable video.

## Reacher investigation — 2026-10-16

Input: subjectId `3620534210064175096`, **season 1, episode 1**, matching the supplied device video.
The `/detail?id=…` URL is a provider identifier, not a browser playback page; the video records
HTTP 404 when it is opened in the browser. The video also shows an App Upgrade Notice from the
old sources, H5 playback of other titles, repeated quality text, and verbose audio labels.

Live H5 requests were made once to each reference alias with the same ID/episode and an empty
path when metadata was unavailable. All seven returned HTTP 200/code 0 with:

```json
{"streams":[],"dash":[],"hls":[],"hasResource":false,"vipLocked":false}
```

Aliases checked: h5-api.aoneroom.com, 123moviesfree.club, movie-box.co, movieboxonline.net,
sflix.film, netnaija.film and movieboxhd.net. Public H5 detail by this exact subject ID returned
HTTP 404 / `TARGET_NOT_FOUND` / `subject not found`. The mobile detail request from the execution
region returned HTTP 403 / `Service not available in current region`.

These observations do **not** prove permanent unavailability or availability in every region,
season, or audio variant. They establish that those exact requests supplied no H5 media to
emit. The provider must return no source rather than an upgrade notice or an unrelated title.
The resolver now checks all eligible aliases and metadata profiles; real device retesting is
still needed to establish whether supported-region metadata yields additional resources.
No restriction override headers, public proxies, paywall or DRM bypass were added.

## Validation

- 29 schema/media regressions: PASS (the previous 22 plus seven audio/quality/subtitle cases).
- 20 provider/crypto/local HTTP integration scenarios: PASS (the previous 17 plus three cases).
  The new cases cover skipped dub requests, callback filtering, all three audio categories,
  original Korean audio, DASH/HLS/direct choices, quality preservation, unrestricted subtitles,
  and exact Reacher identity/path/episode through 403/404/API errors/mismatch/notice responses.
- Production compilation against real CloudStream/NiceHttp/OkHttp APIs: PASS.
- Local Gradle `:Moviebox:assembleRelease`: BLOCKED before build while downloading Gradle
  (`Network is unreachable` in the Java wrapper); no local Android SDK/device is installed.
  Production Android packaging is verified separately by the commit's GitHub Build workflow.
- Device playback of this version: NOT_TESTED.

Tests cover absence of v2/native requests, movie 0/0 and serial 1/1 or 2/4, failed bearer,
independent captions, later H5 aliases after an existing success, metadata profile/H5 fallback,
notice rejection, signed manifest/segment headers, cross-host credential stripping, bounded
Range reads, expired signature rotation, clean labels and the observed empty Reacher response.

## Reference

The original H5/auth/policy/caption port was checked against `classes.dex` from the attached
`MovieBoxProvider.cs3`, manifest **36**, using JADX and DEX instruction disassembly.
Attachment SHA-256: `742a29f499669c954b8993f283ce3b45b562f29bf9156486e2bb6e36e871ac9b`.
That reference also contained sports playPath/playSource branches; none were ported.
The current cleanup baseline is `3c2677ac7dfd472a68552606314017758fd67591`.
Catalog, search, posters, details, episode listing, recommendations, trailer and other providers
are unchanged by this cleanup.

## Official APK audit — 2026-10-16

Attachment: `moviebox-v-4.0.03.0922.03.apk`.
SHA-256: `d219ac4d31c33c865bffdc9ebdcf26798ca3f508fd33fcb5858b9904c5cc8364`.
Binary manifest decoded with Androguard 4.1.4: package `com.community.oneroom`,
version **4.0.03.0922.03**, versionCode **50020131**, entry application `s.h.e.l.l.S`,
component factory `s.h.e.l.l.A`, splash `com.transsion.subroom.activity.SplashActivity`.
The official package/version differs from the existing regional metadata profile. That
observation alone does not establish a playback fault and the working profile is unchanged.

Actual DEX instruction disassembly, not only filename/string inspection, found only four
class definitions: `s.h.e.l.l.A`, `.C`, `.N`, `.S` (193 strings, 106 method references).
`A.instantiateApplication` delegates class loading through native `N.al`; `S.onCreate`
invokes native `N.ra` before delegating to `com.transsion.subroom.app.SubRoomApp`.
The DEX map is at offset 13700 despite a 64,064,740-byte file. JNI loader assets include
`assets/ijm_lib/*/libexec.so`, `libexecmain.so`, and `assets/IJMDal.Data`; script/config
assets are also protected. The accessible DEX contains no business playback methods,
Reacher mapping, H5/native playback endpoint or request-building implementation.
Resource routeMap names movie/stream-detail activities; names do not reveal their logic.
Player/codec libraries likewise do not prove an extra server route.

`assets/sub_def_la.json` was read: `en` / `eng` / English and `in_id` / `ind` / Indonesia
are verified language identifiers. This is a language dictionary, not proof of an audio
subject mapping or a playback API contract. It is used only to normalize language codes.
No conclusion about hidden playback requests, host selection, season offsets, preflight
calls or alternate Reacher subject IDs can be drawn from the loader stub.
There is no adb, emulator, Android SDK or attached Android runtime in this environment.
Consequently no official-app network capture or runtime unprotection was performed.

The new screen recording `585219.mp4` (499.39 seconds) shows Take Charge of My Heart playing
with Asli/Indonesia/Inggris and other dub choices, then a Reacher search followed by loading.
It does not expose app HTTP requests or prove an alternate Reacher subject/path/episode.

### Bounded live comparison

A separate anonymous metadata probe used the manifest's actual package/version with the
existing signature algorithm, without a borrowed device/session or regional override.
This is **not** a captured official-app request and cannot establish runtime parity.
Results from this environment on 2026-10-16:

- Mobile exact-subject metadata: HTTP 403, `Service not available in current region`.
- Official H5 exact-subject detail: HTTP 404, `subject not found` / `TARGET_NOT_FOUND`.
- Official H5 play, exact ID `3620534210064175096`, S1E1, empty unavailable detailPath:
  HTTP 200 / code 0; empty `streams`, `dash`, `hls`; `hasResource=false`, `limited=false`,
  `limitedCode=""`, `vipLocked=false`, `shareUnlocked=false`; codecs hevc/h264.

The APK profile comparison did not recover metadata or prove a valid detailPath. These
responses establish only that the tested requests returned no media. Reacher's underlying
service/runtime cause remains **UNRESOLVED**, and Reacher playback/device testing is BLOCKED.
No Reacher-specific server/subject/episode replacement or native/v2 reactivation is shipped.
The synthetic Reacher tests exercise failure safety, not a claimed fix or live playback.
Logs now distinguish unavailable detailPath and parsed/audio-filtered/validated counts without
logging response bodies, signed URLs, credentials or cookies. A supported official-app runtime
capture remains necessary to compare the hidden business request contract accurately.

## Follow-up APK 50020133 / Reacher-only audit

See [REACHER_APK_CONTRACT.md](REACHER_APK_CONTRACT.md) for recovered call chains,
identity matrix, new endpoint denials and the separate no-root diagnostic kit.
No production playback or audio/subtitle behavior is changed by that follow-up.

Phone follow-up: server original/Indonesian mapping is now proved. See the audit follow-up
and diagnostic V3 for the new HTTP 200/native candidates, APK JSON field correction,
and bounded media validation. V2 resource zeros are not evidence of absent episodes.

V3 phone result: all ten tested native/resource candidate contexts share one 824,720-byte,
22.708-second MP4. The media check rejected it; no native/resource production adapter is added.
See the audit's V3 follow-up and real sanitized fixture. Metadata availability is not playback.

## Reacher version 16: verified Share context

The new Share redirects supply exact original/Indonesian detail paths. Controlled real H5
requests show the missing title path in Referer causes empty sources; correct query path alone
is insufficient. Version 16 uses those two server-verified paths only when mobile detail
metadata is blank, preserving explicit paths and every other subject's behavior. No native/v2
source, header/validator change or audio/subtitle change is introduced.

32 schema/media regressions, 23 provider/local HTTP integrations and real CloudStream SDK
production compilation PASS. Real H5 API candidates are restored for S1E1/S1E2. CDN 429/timeouts
blocked live media validation here; plugin Android playback remains NOT_TESTED. See the audit
and sanitized `diagnostics/reacher_share_evidence_20261016.json`.

## Episode cards — Moviebox 17

Owner confirmed Reacher playback works on version 16. This change adds episode display
metadata only. `MovieboxPlayback.kt`, playback/auth/header/subtitle code, episode payloads,
season/episode indices and plugin registration stay identical to version 16. No AdiXtream
application change is made: its existing EpisodeAdapter selects the card layout when an
episode has a poster or description.

`MovieboxEpisodes` fills thumbnail, episode title, overview, rating and aired date through
the existing TMDB service configuration. It requires an unambiguous exact TV title/year
match (including original names), then joins metadata by exact season and episode number.
Explicit terminal audio/season labels can be removed from the search title. It never adds,
removes, reorders or changes the playback data of Moviebox's episode list. Future external
air dates are omitted so an existing episode is not marked upcoming. Movie details and the
existing trailer resolver are unchanged.

When metadata is unavailable, ambiguous, timed out or missing a still, the current series
cover supplies the episode image. The usual Episode N label remains when no matching title
exists. Images use TMDB's HTTPS w300 still endpoint; Moviebox API auth/media cookies are not
used. Lookups use one TV search and one request per existing season, at most three concurrent
season requests, with a six-second overall optional budget. Failures leave the detail page
and its original episodes available; cancellation of the parent load is respected.

Validation: 18 metadata/parser/provider fixture tests, baseline 32 playback regressions,
23 provider/local HTTP tests and real CloudStream SDK production compilation PASS. The old
44 diagnostic/evidence tests also PASS. Metadata fixtures cover exact identity, wrong years,
ambiguous titles, Unicode names, duplicate/wrong season items, stable playback data, image
fallbacks, HTTP errors, bounded concurrency, cancellation, movie exclusion and future dates.

Real HTTP evidence in `episode_cards_reacher_observed.json`: exact Reacher (2022) TMDB match
108978; S1 and S2 each return eight episodes. Sample titles/stills match their season/episode;
the S1E1 thumbnail returned HTTP 206, image/jpeg, with a valid JPEG prefix in a 1024-byte
bounded sample. Credentials and API request URLs are omitted. Android rendering on the
owner's installed app is NOT_TESTED here; the app already supports this card layout.


## House of the Dragon — Moviebox 18

The owner's four Share URLs redirect to four distinct exact subject/path pairs. They are
not merged into one title, used as substitutes for each other, or treated as proof of a
cross-edition dubbing relationship:

| Share | Subject | Server-issued path |
| --- | --- | --- |
| q3XeoVCyta2 | 6890674365592471456 | house-of-the-dragon-indonesian-2vLI2nqm1d8 |
| YA5Jg23jdc | 5373384118887662624 | house-of-the-dragon-uTatZHG9Wo6 |
| 8uHrrrN9Nw8 | 7721864815710718808 | house-of-the-dragon-G7LGtmOdqc9 |
| EmzlSBQbV59 | 3135126230249492760 | house-of-the-dragon-kkZZ8pvUAJ3 |

Real read-only HTTP controls on 2026-10-16 used the existing provider's H5 headers,
unchanged subject, S1E1 and codecs. For all four IDs, empty title context returned HTTP 200,
code 0, hasResource=false and no streams. Supplying that same ID's Share path in the query
and Referer returned hasResource=true: respectively 1/3/4/1 MP4 candidates and 1/1/2/1 DASH
candidates, with two HLS candidates additionally on 7721864815710718808. S1E2 also returned
resources for each exact ID. These are API candidates, not validated playback claims.

The additional 5373384118887662624 cross-control isolates the failure: correct query path
with empty Referer path still returns nothing; empty query path with correct Referer path
returns three MP4 and one DASH candidate. Thus missing Referer title context can suppress
existing media independently of the episode number. The production fix supplies only the
four server-issued paths when metadata paths are unavailable. Explicit API paths retain
precedence, mismatched metadata identities remain blocked, and no episode or subject is
replaced. There is no claim that catalog entries with similar names are interchangeable.

Production code changes are four fallback entries plus comments in MovieboxPlayback and
the version increment. MovieboxProvider, episode cards, Reacher's mappings, global H5 hosts,
headers, media validator, audio whitelist, subtitles, codec/quality and deduplication are
unchanged. Future server-provided paths still override these fallbacks.

Validation: 34 schema/media regressions, 25 actual-provider/local-HTTP integrations,
18 episode metadata tests, 44 diagnostic tests, and production compilation against the
real CloudStream SDK PASS. Added fixtures exercise all four selected identities at S1E1
and S1E2, case preservation, explicit metadata precedence, wrong identity/episode rejection,
and unavailable metadata without cross-edition fallback. Local media is synthetic.

See diagnostics/house_dragon_share_evidence_20261016.json for sanitized real evidence.
A separate unchanged-production-parser/validator probe parsed eight candidates for
7721864815710718808. Representative DASH/HLS/MP4 requests were blocked by CDN 429 or
timeout; the Indonesian-entry API call timed out in that probe. No live media passed
validation here and fixed-plugin device playback is NOT_TESTED. Validators remain intact;
this release repairs the proven missing-context failure, not an asserted CDN restriction.
The owner's recordings demonstrate existing app playback/one plugin no-link entry, but
do not prove playback of this new build. No cookies, tokens or signed URLs are stored.
