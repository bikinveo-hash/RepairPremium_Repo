# Reacher: APK contract audit and unresolved identity (2026-10-16)

Baseline main: `9d33848994275635a0e12acf0d81253f4fe927be`, plugin 15.
Initial scope: investigation and a separate diagnostic kit. The later **Recovery implementation**
section records the version 16 Reacher-only H5 context fix. Earlier findings below are retained
as history. Live plugin device playback is still not claimed.

## Artifacts and evidence limits

- New APK SHA-256: `40f6cad43ef3c80de2a50fb82351f6b810d36f0555ed15a5e9ee4c11305cb0cb`.
  Binary manifest: `com.community.oneroom`, `4.0.03.0930.04`, code `50020133`, eight DEX files.
  Androguard 4.1.4 confirms application `Abdullah.hook`, JAR signer subject `CN=IN`, v2/v3
  signatures present. The certificate name does not establish the app's origin or safety.
- JADX 1.5.3 and DEX instruction disassembly were used. The DEX class index contains 61,991
  definitions. Some network/bootstrap/activity methods have invalid/protected instruction
  payloads and could not be reliably decompiled; readable DTOs and the concrete coroutine
  methods below were inspected. This is not a claim that every APK method was recovered.
- `Abdullah.hook` extends the application and intercepts PackageManager/signing information;
  its signer helper supplies replacement signature data. **No hook, signature-spoofing logic,
  modified entitlement handling or installer impersonation is copied into the provider/kit.**
  APK was not installed or executed; no personal account/session was used. Findings are
  explicitly about the supplied Mod artifact, not verified official runtime behavior.
- Earlier official APK: `4.0.03.0922.03` / `50020131`, SHA-256
  `d219ac4d31c33c865bffdc9ebdcf26798ca3f508fd33fcb5858b9904c5cc8364`. It exposes four shell
  classes and protected payload. It cannot verify that each readable Mod business method
  is identical to an official build. Previous audit remains in `tests/README.md`.
- `585232.mp4`: duration 214.96 seconds. Around 176 seconds, search shows separate entries
  `Reacher [Indonesian] S1-…`, `Reacher S1-S4`, and `Reacher` with different artwork.
  Around 179 seconds the Indonesian detail displays year 2022, Season 1, eight episodes,
  followed by loading. No subject/resource ID, API response or successful Reacher playback
  is visible. Title/artwork similarity is not used to link the third entry to Reacher (2022).

## Recovered call chains (static, not a Reacher runtime trace)

| Stage | Concrete code location | Verified behavior |
| --- | --- | --- |
| Detail season preload | `moviedetail.preload.MovieDetailResourcesSeasonLoader.h` → `$loadDataFromService$1.AnonymousClass1.invokeSuspend` | Calls `qy.a.c(vg.a.a(), loader.subjectId, continuation)`; only code `0` emits `ResourcesSeasonList`, otherwise null. No episode offset or alternate ID is synthesized. |
| Dubbing metadata | `downloader.viewmodel.DownloadResourcesDetectorViewModel.t(subjectId)` → `$getResourceAllLangInfo$1.AnonymousClass1.invokeSuspend` | Calls `qy.a.b(host, subjectId, continuation)`; code `0` emits `DubsInfoData` to the audio/resource LiveData. This is a resource/download UI path, not proof that every native playback first calls dub-info. |
| Dub identity | `moviedetailapi.bean.DubsInfo`, `DubsInfoData`; `downloader.widget.DownloadResolutionTabView.setAudioItemClickListener` | `subjectId`, `lanName`, `lanCode`, `original`; transient UI flags `isSelected`, `isSubtitle`; list `dubs`. A caller can select a server-declared audio subject. Exact Reacher IDs are server data, not constants in these DTOs. The protected upstream detail/UI path cannot establish a particular Reacher mapping. |
| Resource preload | `MovieDetailDownloadListLoader$loadDataFromService$1.AnonymousClass1.invokeSuspend` | Invokes `qy.a.g` through default-argument helper with page `1`, perPage `10`, resolution `0`; mask `3824` supplies all `0`, start/end `1/1`, pagerMode `0`, se `1`, epFrom/epTo `1/1`. A preload is not proof of media availability. |
| Selected resource query | `DownloadResourcesDetectorViewModel.j` → `$getDownloadList$1.AnonymousClass1.invokeSuspend` | Carries the selected subject, se, epFrom/epTo, page, positions and resolution to the same resource API. Does not justify treating all returned download URLs as streams. |
| Episode identity | `videodetail.VideoDetailViewModel$fetchEpisodeMediaList$1` and `VideoDetailMediaSource` | Movie uses `0/0`. Serial uses returned `ResourcesSeason.se`; nonempty `allEp` is comma-separated exact indices. When empty, episode indices are generated from `1..maxEp`. Those are declarations, not a successful media check. No Reacher-specific season shift was found. |
| Native player request | `VideoDetailViewModel.v(mediaSource)` → `$getPlayInfo$2.invokeSuspend` → `videodetail.b.b` | Cache key is exact `subjectId-se-ep`. GET play-info takes exactly subjectId/se/ep plus dynamic host. No detailPath, codec flags or H5 request occurs in this native method. Local authorized downloads are a distinct branch using `DownloadBean.path`, not an API media fallback to copy blindly. |
| Response → player | `videodetail.bean.VideoDetailStreamList`, `VideoDetailStream`; `videodetail.util.StreamFloatManager.o` | Uses `streams`, selects by format/resolution, carries nonempty `signCookie` as Cookie, builds media data source from the returned URL, then setDataSource/prepare/play. Format mapping explicitly separates DASH, HLS and DEFAULT. This shared path is not evidence it ran for Reacher. |
| External play URL | `Subject.isJumpBrowser/isJumpWebview`, `PlayUrl`, `PlayUrlType`, `DownloadPanelForYouFootView` | `PLAY_URL_WEB` opens a browser; `PLAY_URL_ANDROID` opens an app webview, with family-mode gating. These enum values are navigation types, not H5-vs-mobile media format selectors. No such URL was recovered for Reacher. |

`ResourceDetectors`: type `1` means collection; type `0` with nonempty resolutionList means
multi-resolution; type `0` with empty/null resolutionList means single resource. Fields include
resourceLink/downloadUrl/resourceId/postId/extSubtitle. `DownloadItem` separately carries
resourceId, se, ep, linkType, url and resolution. Neither model alone proves playback identity.

## Retrofit contracts actually inspected

`qy.a` (classes8.dex):

| GET path under `/wefeed-mobile-bff/subject-api/` | Annotated parameters | Response |
| --- | --- | --- |
| `get` | host, subjectId | BaseDto&lt;Subject&gt; |
| `dub-info` | host, subjectId | BaseDto&lt;DubsInfoData&gt; |
| `season-info` | host, subjectId | BaseDto&lt;ResourcesSeasonList&gt; |
| `resource` | host, subjectId, page, perPage, all, startPosition, endPosition, pagerMode, resolution, se, epFrom, epTo | BaseDto&lt;DownloadListBean&gt; |
| `resource-position` | host, subjectId, resourceId, failUrl, failCode, resourceNum | BaseDto&lt;DownloadListBean&gt; |

`videodetail.b` (classes7.dex) independently declares `play-info(subjectId,se,ep,host)` and
`season-info(subjectId,host)`. The coroutine bytecode was checked for the season loader's
exact subject propagation (`qy.a.c`), beyond searching endpoint strings.

Dynamic `host` originates in `vg.a.a()` → `CacheIpPool.g()`. The latter and portions of the
network/interceptor layer are protected/invalid in static decoding. Therefore final wire host,
header/session transformations and official runtime parity remain UNKNOWN; the `host` argument
is not speculatively copied as a CDN selector or a server query override.
No H5 `detailPath` can be reconstructed from the native play-info method, which does not use it.

## Reacher identity matrix before any proposed production edit

The complete matrix is [diagnostics/reacher_identity_matrix.csv](diagnostics/reacher_identity_matrix.csv).
UNKNOWN means there is no server/runtime evidence. The seed ID is supplied by the provider
URL; it has not been proven to be the original member of a dub family.

| Entry | Known ID | Season/episode | Linkage/resource evidence |
| --- | --- | --- | --- |
| Initial provider Reacher (2022) | 3620534210064175096 | S1E1, comparison S1E2 | New dub-info and season-info denied with 403; original/dub IDs, actual path and media unknown. |
| Reacher [Indonesian] (2022), video detail | UNKNOWN | S1 has eight displayed episodes | UI label is not an explicit dub-info relationship or a playable resource. |
| Reacher S1-S4, video search entry | UNKNOWN | UNKNOWN | Compilation/season relationship unproven. |
| Third Reacher search entry | UNKNOWN | UNKNOWN | Different artwork; not equated with the target. |
| English audio member | UNKNOWN | S1E1 requested target | No server-declared mapping recovered. |

## New HTTP evidence; old failing H5 probes were not repeated

Hypothesis tested: dedicated dub-info/season metadata may expose a mapped Reacher subject
that the current inline-dubs H5 resolver never sees. Using the existing provider modern
signature/profile, exact ID `3620534210064175096`:

- GET `dub-info?subjectId=…`: HTTP 403 / code 403, `Service not available in current region`.
- GET `season-info?subjectId=…`: same explicit regional denial.
- No host/region/proxy/restriction workaround was attempted. With metadata access denied,
  resource/media availability cannot be inferred. A native play-info retry was not justified.
- Kit smoke test used the existing catalog guest profile on api3: bootstrap also returned
  HTTP/code 403. Kit created its sanitized ZIP and stopped, without probing other hosts.

Real sanitized responses are in [diagnostics/observed_metadata_denials.json](diagnostics/observed_metadata_denials.json).
Previous seven-alias empty H5 and H5 TARGET_NOT_FOUND observations are preserved in the
baseline README; no identical seven-host scan was rerun. Region denial proves this request
cannot access metadata here; it does not prove that Reacher resources do not exist elsewhere.

## Cause classification and implementation decision

A (identity), B (path), C (episode mapping): UNKNOWN for this Reacher ID. Generic APK code
retains exact episode indices and supplies no alternative ID/path. D (separate dubbing API):
its existence and consumer are proved statically, but the Reacher response is blocked, so
its omission is not established as Reacher's root cause. E (different media path): native and
external-navigation paths exist, but no valid Reacher resource from either was obtained.
F (no resource for a legitimate session): UNKNOWN; session metadata is denied in this environment.

**Root cause remains UNRESOLVED. FIX_IMPLEMENTED: NO production playback patch.**
Only the audit and a tested, separate diagnostic kit are added. No speculative adapter,
hardcoded alternative subject, episode substitution, notice, validator relaxation or source
reactivation is shipped. Build/plugin version stays 15 because packaged source is unchanged.

## Next evidence and validation

Use [diagnostics/README.md](diagnostics/README.md) and the kit on the user's own connection.
Needed: exact Share/detail links for original/Indonesian/S1-S4 entries; server dub-info mapping;
season metadata and resource item IDs for the exact selected S1E1; comparison S1E2 when declared;
and sanitized plugin logs showing metadata path/endpoint status and validated candidate counts
for Reacher and one working control. The kit never claims other-app HTTPS capture. If the
official app has a permitted diagnostic export, only sanitized identity/status/schema data is
needed, not credentials, raw traffic, signed URLs or a bypass of TLS pinning.

Baseline 29 schema/media + 20 provider/local HTTP tests and real CloudStream API production
compilation: PASS. 13 diagnostic tests: PASS, covering privacy, real denial fixtures, subject/dub relationships,
exact episode/resource association, bounded denial handling, and source hash integrity.
These synthetic tests and successful kit execution are not Reacher playback/device tests.
Android/official-app runtime capture and live Reacher media validation remain unavailable.

## Follow-up: actual phone evidence and diagnostic V2

New owner-supplied archive `Reacher_Diagnostic_20261016_193849_50894b.zip` was read;
its on-device-sanitized distinct responses are preserved in
`diagnostics/phone_reacher_metadata_20261016.json`. It contains 18 request records with
10 distinct endpoint/subject/episode response contexts. No user session token was supplied.

- Bootstrap HTTP 200/code 0, guest session available: the execution-region 403 limitation
  does not apply to this phone run. Both subject metadata and dub-info succeeded.
- Explicit server mapping: original `3620534210064175096`, Indonesian
  `4149489342181954296`; both title/year metadata identify Reacher (2022).
  No separate English category appears in this returned dub list. That does not establish
  the actual original stream language or absence of all English media.
- Original season metadata declares S1–S4; Indonesian declares S1–S2, each maxEp 8.
  allEp is empty. These are declared indices, not validated media resources.
- Both get responses have hasResource=true and one resourceDetectors entry. The first kit
  saved only its count, so the resourceId/link structure remains unknown. playUrl has
  PLAY_URL_WEB but an empty URL; it supplies no external playback destination.
- Resource requests for S1E1/S1E2 returned HTTP/code 407. The first kit deliberately omitted
  raw error messages, so neither the meaning of 407 nor an entitlement/version/signature
  cause can be asserted from that archive. It is not assumed to be proxy authentication.
- The supplied control ID is the original member of the same Reacher dub family; the
  archive is a useful edition comparison but does not establish a working unrelated control.

A targeted new H5 test used only the newly proven Indonesian ID, S1E1: official H5 detail
returned 404; H5 play HTTP 200/code 0 with empty streams/dash/hls and hasResource=false.
The old original-subject seven-host scan was not repeated. No production adapter is justified.

The first diagnostic kit signed multi-parameter resource requests in insertion order, while
the verified playback helper canonicalizes keys alphabetically. V2 sorts the wire query, so
both legacy wire signing and sorted playback canonicalization agree for these plain resource
parameters. A reflection call to the actual compiled Moviebox Kotlin helper gave the same
fixed-timestamp signature as V2 for the Reacher Indonesian resource query. **This proves
protocol alignment of the corrected kit, not that query ordering caused the phone's 407.**
The provider already had sorted modern playback signing and is unchanged.

V2 also classifies error messages without saving them, records resource detector identities
without signed URLs, caches duplicate requests, stops episode retries on 407, and optionally
checks the APK's native play-info contract read-only for exact Reacher S1E1. It makes no media
requests and supplies no CloudStream links. Notices and explicit response identity mismatches
are recorded; media validation/device playback remain NOT_TESTED. All changes are test/audit
tooling; packaged Kotlin, sources, audio filter and plugin version 15 remain unchanged.

Validation: 20 diagnostic tests PASS, including the real phone fixture, sorted signing,
407 handling, native-diagnostic scope, identity mismatch and nested URL/cookie suppression.
Next evidence: a V2 ZIP from the same phone connection, to establish the 407 error category
and obtain sanitized resource/native response structure under the corrected query ordering.

The phone evidence now rules out treating the initial ID as an unproven original ID: the
server explicitly marks it original. It also returns the same dub family inline in get,
so adding dub-info alone is not demonstrated to fix the production resolver. The next
unknown is actual resource/native media under a valid request contract, not a guessed title
replacement. hasResource=true still does not establish playable media.

## Follow-up V2 phone results and serialized resource contract

MAIN_BEFORE: `f581c0a21130b83b0bcd5a73d5853b0a33146822`.
Archive `Reacher_Diagnostic_20261016_195727_5689bf.zip` is preserved as sanitized fixture
`diagnostics/phone_reacher_native_20261016.json` with its SHA-256. No media URL or token was
supplied. The original and Indonesian metadata, dub-info, seasons and all four exact episode
resource requests now returned HTTP 200/code 0. The prior 407 is no longer observed after
V2 sorted signing; this is not a controlled proof that sorting alone caused that change.

Original S1E1 native play-info returned MP4 IDs `689473467628014120` and
`7667675295478346328`; Indonesian S1E1 returned MP4 ID `3834879483056636352`.
URLs had no query, no signed cookie and did not match the existing known-notice patterns.
No media bytes were fetched. Identity was REQUEST_CONTEXT_ONLY: the native response does
not echo the full subject/se/ep. The APK likewise attaches requested se/ep after parsing.
This is useful native-path evidence, but not proof that these URLs contain Reacher episodes.

Both get detailUrl values were empty. Original collection detector ID is
`7685587439406445144`, Indonesian is `2889787666332115640`; each is type 1 (collection),
not an episode resource ID. PLAY_URL_WEB still has no URL, so it cannot supply playback.

Further DTO inspection found a diagnostic bug: in
`com.transsnet.downloader.bean.DownloadListBean` (classes8.dex), `items` has
`@SerializedName("list")`, and resolutionList has `@SerializedName("collectionResolutions")`.
In `com.transsion.moviedetailapi.DownloadItem`, `url` has
`@SerializedName("resourceLink")`, and `extSubtitle` has `@SerializedName("extCaptions")`.
V2 sanitized only `items`/`url`; its zero exact resource counts cannot establish that the
actual resource responses lacked episodes. The raw bodies were never saved, so this cannot
be reconstructed from V2. No production H5 parser is implicated by this diagnostic-only bug.

V3 fixes those wire names, reports missing arrays as UNKNOWN, and adds an optional bounded
MP4 HTTP/container check, enabled in the Termux wrapper. Exact Reacher S1E1 and declared
S1E2 are compared without substitution; resource media checks require exact subject/se/ep.
Numeric native/resource duration, size and resolution metadata are now sanitized too.
Credentials/URLs remain in RAM, API bearer is never sent to media, cookies stay on their
initial origin, and private/non-HTTPS destinations, access markers and known notices stop.
12 unique URL+cookie candidates / 36 requests / 64 KiB per response / 150-second start budget
bound the media probe. It reads MP4 headers, movie duration and track types, not full movies.
It detects identical URLs across episode/audio contexts without exporting their values.
A container PASS explicitly leaves decode/device playback NOT_TESTED.

Current cause classification: A identity is proved by server; B detailUrl is empty, with no
server-provided alternative path; C no episode offset established; D dub-info matches inline
get and is not sufficient to fix H5; E native candidates exist, actual media validity remains
UNKNOWN; F cannot conclude that the service has no resources. Reacher production recovery
remains BLOCKED pending bounded media evidence, then actual player verification. No native
adapter is shipped speculatively. Production version 15 and all source/audio/subtitle/validator
behavior remain unchanged. The kit and fixtures are the only implementation changes.

Validation for V3: 36 diagnostic tests PASS (including sanitized real phone fixture and
synthetic MP4/Range/redirect/privacy/identity cases); baseline 29 schema/media regressions
and 20 provider/local HTTP integration tests PASS; production Kotlin compilation against
actual CloudStream SDK PASS. These are not official-APK runtime or device playback tests.

## V3 phone media evidence: candidate URLs are not episodes

MAIN_BEFORE: `bfb6268e2d5788c66bc50e821820c6feb58377e2`.
Owner-supplied `Reacher_Diagnostic_20261016_202731_a7bb39.zip` was inspected. A compact
allowlisted fixture with archive SHA-256 is in `diagnostics/phone_reacher_media_20261016.json`.
The existing catalog guest session succeeded on the owner's phone; all eight native/resource
requests for the two proven subjects and S1E1/S1E2 returned HTTP 200/code 0.

| Subject / episode | Native candidate IDs | Exact resource item ID | Delivered media |
| --- | --- | --- | --- |
| Original S1E1 | 689473467628014120, 7667675295478346328 | 8792116150390911208 | Same short MP4 |
| Original S1E2 | 1189724869987108256, 2747395945552924568 | 2342961483996361232 | Same short MP4 |
| Indonesian S1E1 | 3834879483056636352 | 2889787666332115640 | Same short MP4 |
| Indonesian S1E2 | 8932954261240039928 | 4488565534048641720 | Same short MP4 |

The corrected `list`/`resourceLink` reader establishes exact subject/season/episode resource
metadata. Each resource response listed ten items spanning S1E1–S2E2 even when a single episode
was requested; exact item filtering was therefore necessary. Nothing was substituted for the
selected episode. The Indonesian detector ID also appears as its exact S1E1 resource item ID;
ID equality alone therefore cannot classify collection versus episode context. Returned se/ep
fields remain essential. This does not establish availability of later episodes' media.

**All ten checked candidate contexts use exactly the same URL+cookie tuple**, not just a
matching filename or checksum. The diagnostic cache labels this tuple media-1 and consequently
performed only one media request. HTTPS GET Range returned 206, video/mp4, total 824,720 bytes;
65,536 bytes were read. The MP4 movie header reports **22.708 seconds**, with video/audio tracks.
Sample SHA-256: `f57afe0d01707491b0247582f14bdf9f23cf858c2f9c2d801cf8bdd1a98e88c2`.
All ten associations are FAIL / SHORT_MEDIA_NOT_VERIFIED_EPISODE. No actual episode was decoded
or played. The shared sample evidence is not ten independent CDN fetches.

Native metadata itself says duration 23 and size 824720. Resource metadata instead advertises
3345 seconds for S1E1 and 3276 seconds for S1E2, while resource URLs resolve to the identical
22.708-second asset and size 824720. Thus catalog duration, hasResource, code 0, stream/resource
IDs, format MP4 and HTTP success cannot establish that the delivered content is the episode.
The initial URL blacklist did not recognize this asset (`known_notice=false`); the duration
check correctly rejected it. No URL, cookie, bearer, key or media bytes were included in the
owner's export or this fixture.

### What is established and what remains unknown

- The initial subject is the server's original Reacher entry; Indonesian is explicitly linked.
  There is no evidence for a title/episode offset fix or alternate proven English subject.
- The tested H5 contexts previously yielded no media and mobile detailUrl remains empty.
- The tested native/resource path returns a shared short replacement instead of episode media
  under the diagnostic's existing provider catalog guest profile. **It is not a viable recovery
  source and will not be re-enabled.** The result rules out the proposed native/resource
  adapter for these observed responses; it does not rule out every authorized official session.
- This is consistent with a notice asset, but its visual/audio message was not captured. Calling
  it specifically App Upgrade Notice, paywall, region block, bad signature or expired session
  would go beyond the evidence. HTTP/code succeeded; the precise substitution trigger is UNKNOWN.
- The diagnostic profile is explicitly not official APK 50020133 runtime parity. It cannot prove
  what a current unmodified official application sends or plays. The supplied Mod's loader/hook
  is not a contract to copy. No identity borrowing, entitlement/region bypass or security change
  is introduced to force a different result.

No further identical probe or broad host/episode scan is justified. The remaining useful device
evidence is whether a current **unmodified official app** actually plays this exact Reacher S1E1
beyond the initial loading/notice, and the Share/detail URL of that selected edition. If it plays,
request-profile/resource-context comparison requires a service-provided permitted diagnostic
export or sanitized logs from an authorized debug environment; a screen recording alone cannot
reveal HTTPS headers. Do not send tokens, cookies, signed video URLs or raw private traffic.
If it shows a short message, its exact text/screenshots can identify the service's stated reason.

Production playback recovery remains BLOCKED; no speculative provider patch or new kit is
shipped. Only sanitized evidence, its regression tests and the identity matrix/audit change.
Plugin version remains 15; packaged Kotlin, H5 sources, audio whitelist, subtitles and all other
providers remain byte-for-byte unchanged. This is an investigation result, not a playback fix.

## Recovery implementation: server Share context restores H5 resources

MAIN_BEFORE: `466d5db7bb49593aa7f15a6513ee66135c1d980e`. Production version: **16**.
This later evidence supersedes the earlier conclusion that no production patch was justified.
The previous native/resource short replacement remains rejected; it is not re-enabled.

New owner video `585430.mp4` (84.136678 seconds, SHA-256
`588e5463009586ef80710a6eb2ac20a30d20c75c891cf21352a8ddd33f835c4b`) visibly shows moving
Reacher scenes under original and Indonesian selections. Around 80 seconds the player displays
`Reacher S01 E01`, position `17:59`, duration `55:45`, and `1080P`. This is evidence of playback
in the recorded MovieBox application, not plugin playback or a full 55-minute viewing test.
The app's package/version and unmodified official origin are not visible and are not asserted.

The supplied public Share URLs return server-issued HTTP 301 locations on Android browser UA:

| Share | Exact subjectId | Server detail path |
| --- | --- | --- |
| https://v.moviebox.ph/kW2VHLAW2ia | 4149489342181954296 | reacher-indonesian-WjrjjPIHwW4 |
| https://v.moviebox.ph/4qq9KlyIys8 | 3620534210064175096 | reacher-e1nw56h5sj4 |

Only id/path/host are retained. Referral uid and tracking query values were discarded.
Desktop following redirects reaches download-app and loses this identity context; the first
301 location supplies the exact server mapping. No slug was guessed from a title, and no
alternate subject or episode was selected.

### Controlled HTTP result and root cause

Existing production H5 UA, x-client-info, request language, host and episode were preserved.
Only the Share-derived title context was varied on the official H5 host for original S1E1:

| detailPath query | Referer title path | HTTP/code | Result |
| --- | --- | --- | --- |
| verified slug | empty | 200 / 0 | no streams, hasResource=false |
| empty | verified slug | 200 / 0 | 3 direct streams + 1 DASH, hasResource=true |
| verified slug | verified slug | 200 / 0 | 3 direct streams + 1 DASH, hasResource=true |

Thus **the missing title context in the H5 Referer is the demonstrated cause of empty H5
responses**; adding the query path alone does not fix it. Both fields were previously derived
from empty mobile detailUrl. Supplying the exact server path lets the existing provider build
its ordinary Referer correctly. Original direct qualities: 360/480/1080 H.264, API duration
3345 seconds; Indonesian: 480/1080 and DASH. No HLS was offered by these observed responses.
Further S1E2 requests for both exact subjects also returned H5 candidates under the same path.

Sanitized real Share redirects, cross-control results, observed stream metadata, video facts
and bounded live media failures are preserved in `diagnostics/reacher_share_evidence_20261016.json`.
No credential, signed URL, raw response, referral identity or media bytes are committed.

### Minimal patch and verification limits

`MovieboxPlayback.detailPath` retains explicit API detailPath/detailUrl precedence. Only if
both are blank, it uses the two exact verified Reacher subject-to-path mappings; mismatched
metadata identity cannot obtain a fallback. The selected subject is passed at the existing
call site, so a failed metadata request does not lose this verified context. Other IDs keep
exactly their previous behavior. These are stable catalog paths, not temporary signed media
URLs. Future nonblank server metadata takes precedence if the catalog path changes.

No source domain, API/mobile session, global header builder, parser, validator, audio filter,
subtitle, codec, deduplication, episode list, catalog UI or other provider is changed. The
existing source code builds both detailPath query and Referer from the corrected value.
No v2/native source is added. Version 16 makes the scoped playback fix available to clients.

Actual production parser/validator code was also exercised over real HTTP using an isolated
CLI harness and the execution environment's normal egress with standard TLS verification.
Media requests returned CDN 429 (587-byte HTML rate-limit responses) and socket timeouts;
all such candidates were correctly rejected. A separate bounded request to the returned
DASH host also got 429. No retry storm, region change, TLS bypass, proxy addition to production
or validator relaxation was used. **Live manifest/segment validation and plugin device playback
remain BLOCKED/NOT_TESTED here. API candidate recovery is proved; plugin playback success is
not yet claimed.** The owner's app video does not remove this test limitation.

Validation: baseline 29 regression + 20 provider/HTTP + 40 diagnostic tests passed before the
patch. After the patch, 32 regression + 23 provider/HTTP tests and actual CloudStream SDK
production Kotlin compilation PASS. New synthetic cases reproduce blank-path failure and
exercise the repaired original/Indonesian S1E1/S1E2, missing metadata, path precedence, identity
mismatch and unaffected unrelated titles. Real evidence tests are separate from playback.
Next device gate: update Moviebox to version 16 in AdiXtream/CloudStream and test the exact
original and Indonesian S1E1/S1E2; verify visible episode, duration and audio. No new diagnostic
kit is required for that playback check.
