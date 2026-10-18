# Spider-Man recovery V4: identity and media are separate gates

Baseline: main `2c31f13068aef47a4c0dd25d791a98716f53cac4`.
Only AdiDrakor, AdiFilmSemi, AdiXtream and Adicinemax21 change. Moviebox is read-only.
No title/subject/path exception is introduced in production.

## Proven software defects and fixes

1. `potential` and final `verify` required a TMDB title alias even when Moviebox's
   localized title was absent from TMDB. Candidate collection now admits a bounded
   same-kind/year neighborhood for requests with external identity. Acceptance is
   separate: an exact nonconflicting external ID, or exact type/year plus at least
   three selected cast names, agreeing runtime, and a plot fingerprint of six
   meaningful words/40% overlap, beating known competing works by 20 percentage
   points. These thresholds are calibrated by the real distinct Spider-Man
   metadata and wrong-franchise fixtures; they are not an accuracy guarantee.
   A shared cast alone never permits a different title. Decorated trailer/full-movie/edition titles cannot use that cross-language shortcut. Unsupported declared audio conflicting with the selected original language and gross runtime conflicts also reject before an external-ID acceptance. Unknown-year/foreign-type
   candidates still need their authoritative detail and existing identity gates.
2. Every manifest used to trigger a 4KiB sample followed by a second complete GET,
   even when the sample was already complete. A complete manifest now supplies
   the validation input directly. Partial Content-Range/truncated documents still
   require a bounded complete manifest request; initialization/media checks remain.
3. Candidate probes ran in bursts; aliases could request the same CDN again after
   429. A request-local session serializes media requests, caches identical scoped
   samples for at most five seconds, and blocks a rate-limited origin until the
   full Retry-After seconds/date deadline. Without that header, five-second cooldown
   applies. Suppressed requests do not sleep, rotate a proxy or change credentials.
4. Validated callbacks waited for other candidates. Each validated link now emits
   immediately; distinct audio/codec/format/quality choices remain, and redundant
   servers are skipped only after that group validates. Caption handling remains.
5. Opaque transport failures are replaced by typed sanitized events. A single
   ordinary GET fallback is allowed only after HTTP 416, never after 429, 426 or
   authentication rejection. Bytes, manifests and segments must still validate.

Signed URLs are preserved, TLS verification is enabled, and the existing scoped
interceptor, parser, audio policy, HLS/DASH checks and media headers remain intact.
No API bearer is allowed in the new media transport. Redirects strip out-of-scope
credentials and reject HTTPS downgrade, credential URLs and excessive hops.
Explicit expired URL epochs are rejected before transport or sample-cache reuse.

## Differential evidence and limits

On 2026-10-17 UTC, a fresh H5 movie request for public subject
6026412232966389904/path spider-man-brand-new-day-Aea8ARY1bb7 returned resources.
A direct video request using the reference media headers returned HTTP 429 both
with Range bytes=0-4095 and with an ordinary bounded GET. Both replies were nginx
text/html, 587 bytes, with the same sanitized body digest `0dd4b9984480` and a
"too many" marker. No Retry-After was supplied. Thus this experiment does not
support a Range-only cause, a bad language query, or acceptance of that HTML as
video. It also cannot identify why the CDN rate-limits this network.

Prior alias HTTP 426 observations remain unexplained; do not assert a protocol
cause without evidence. Production diagnostics now record actual protocol/status.
The optional Kotlin probe runs the new production parser/headers/policy candidate
selection and transport. Its output distinguishes H5 resources from actual media
validation. Read-only reference Moviebox behavior remains covered by its original
suite. No current-device network capture is available: the native app may use different
resources. Stored APKs were inspected separately as recorded below. Recording playback is not proof that these H5 URLs work on Android.

## Reproduce

```sh
python3 AdiDrakor/tests/run_migration.py
# Optional live network, separate from fixtures; never logs credentials/media URLs:
python3 AdiDrakor/tests/run_live_media.py 6026412232966389904 spider-man-brand-new-day-Aea8ARY1bb7
```

The runner still replays every original playback/audio/subtitle/security fixture
against all four modules. Byte equality remains for unchanged parser/security and
interceptor contracts; only the deliberately changed validator/scheduler is tested
behaviorally. Four-provider source parity includes mapping, engine, playback,
transport and diagnostics. The standalone Moviebox production files are untouched.

## Android evidence without adb, root or Termux

1. Install/update one target plugin, then open its settings gear in Extensions.
2. Tap **Bersihkan** to clear old diagnostics, close, and try the selected film once.
3. Return to that plugin's settings gear, tap **Salin log**, and paste the text.

The in-memory ring retains at most 160 typed events. It includes subject/episode,
format/quality/codec/audio, hostname, HTTP status/MIME/protocol, redirects, attempts,
request duration, rate-limit cooldown and validation outcome. No signed URL,
Authorization, cookie value, API key or response body is retained or copied.
This settings UI requires device verification; compilation/fixtures do not prove
Android playback or that the owner's device sees this network's HTTP response.

An existing bilingual regression fixture was corrected to assign the wrong sequel
a distinct subject ID and its own conflicting detail. Previously its discovery
label and the correct movie reused one ID while the detail callback always
returned the correct movie. V4 legitimately verifies that same-ID correction
earlier; the corrected fixture retains the original two-query and no-wrong-work
assertions, without weakening an identity rejection.

## New live transport observation

The aligned production-order probe returned H5 200 over HTTP/2, four parsed
resources and five policy-expanded candidates. None validated. Two actual
requests timed out, two returned HTTP 429 text/html over HTTP/2, and the fifth
candidate was suppressed by the same-origin cooldown (attempt=0, no HTTP
request). No Retry-After header was supplied. This demonstrates real pacing and
continued CDN denial; it does not demonstrate playback recovery. The sanitized
trace is `fixtures/spider-v4-media-observed.json`. One preceding probe timed out
at the H5 endpoint before a later run succeeded; network availability varies.
HTTP 426 was not reproduced by the new Kotlin probe, so its underlying cause
remains unverified. The native app resource and owner-device HTTP status have
not been captured.

## Available native APK boundary

The stored Moviebox.apk was inspected read-only: SHA-256
`8e48e49a59f126b34a4b0b5c9fb05af146a5c9ac612099f07aefe824a60b8460`,
66,619,827 bytes, package com.community.oneroom, version 4.0.03.0922.03 /
50020131. Its three outer DEX files expose only 35 classes, consistent with the
previously documented protected-shell APK. Static strings such as play-info/v2
do not establish the active playback route or recover the protected app code.
This is older than the 50020133 app reported in prior work and is not proof of
the version used in the Spider-Man recording. No runtime hooks, private-token
capture, DRM/TLS bypass, APK modification or guessed mobile-media fallback was
performed. The native Spider-Man resource remains NOT_CAPTURED.

The later stored mod APK was also located and inspected: version
4.0.03.0930.04 / 50020133, package com.community.oneroom, 67,028,684 bytes,
SHA-256 `40f6cad43ef3c80de2a50fb82351f6b810d36f0555ed15a5e9ee4c11305cb0cb`.
Its eight DEX files contain 61,991 classes. classes7.dex declares a Retrofit GET
`/wefeed-mobile-bff/subject-api/play-info` on com.transsion.videodetail.b.b.
This proves a native API capability, not that the recorded Spider-Man playback
used it or shared the H5 URL. No native endpoint is introduced into the four
providers. Exact static facts are in `fixtures/spider-v4-apk-observed.json`;
active native media URL/headers and Android playback remain NOT_CAPTURED.

The fresh H5 schema probe declared hasResource=true, vipLocked=false and
limited=false; all four resources declared 8,700 seconds and vipLocked=false.
The provided credential fields were signCookie and signHeaderKey, already used
by the port. No additional per-resource header map was advertised. These server
metadata statements do not override a failed CDN request or prove video content.
A controlled DASH comparison also returned 429 for Range and no response within
the ordinary-GET deadline; neither experiment demonstrates a Range-only fix.
