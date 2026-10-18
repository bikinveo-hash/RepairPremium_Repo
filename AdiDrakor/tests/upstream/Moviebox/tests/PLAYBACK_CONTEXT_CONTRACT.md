# Selected Moviebox H5 context recovery

Baseline main: c24b45a1e3178f52338e43580eea5c6143925654, Moviebox 18.

The owner supplied `585825.mp4` and `https://v.moviebox.ph/aHRXMoDIRt9`.
An Android User-Agent GET returned a first-party Share redirect declaring:

- subjectId: 6026412232966389904
- title: Spider-Man: Brand New Day (the app displays its localized title)
- detailPath: spider-man-brand-new-day-Aea8ARY1bb7
- movie selection: 0/0, subjectType 1

Share tracking/user query parameters were removed from the retained fixture.
The public server-rendered catalog independently returned the same ID and path.
A separate Hindi/full-movie search result has another ID; it is never used as a
substitute for this selected subject.

A live same-ID H5 comparison reproduced the context failure: HTTP 200/code 0
with blank detailPath returned dash=0, hls=0, streams=0. Supplying the exact
server path returned dash=1, hls=0, streams=3. This is response-level evidence,
not proof that the CDN validates or that the updated Android plugin plays.
The recording shows playback in the separate MovieBox app, not the new plugin.

Previously EpData discarded title/detailPath obtained during detail loading.
The playback metadata endpoints may omit that path or be unavailable. Moviebox
now carries these optional fields in each movie/episode payload, preserving its
existing subject ID, type and exact season/episode. Detail responses declaring a
different subject ID are rejected before building the payload.

When the playback metadata already supplies a valid H5 path, it keeps priority.
A generic native `/detail?id=...` URL is not treated as the slug `detail`; URL
host and declared ID are checked before path extraction. When it
is blank, the new context helper first uses the selected same-ID payload path.
Otherwise it reads the public catalog using the native or saved selected title
and accepts only an exact subjectId row. It does not infer a slug from the title,
search for alternate films, change IDs, use another dub's selected context, or
request another episode. Missing identity/context or conflicting paths fail
closed. No title-specific IDs or paths are hardcoded in production.

Only inert Nuxt JSON subjectId/detailPath/detailUrl references are decoded. The
public metadata request receives a web User-Agent and first-party Referer, with
no API bearer or media cookies. Inputs and the number of decoded table entries
are bounded. A lookup has a six-second budget and preserves caller cancellation.
Successful ID-scoped navigation paths are cached for sixty seconds with a
64-entry limit. No signed media URLs or credentials enter this cache.

The H5 parser, validation, signing, media header/interceptor scope, audio policy
and subtitles are unchanged. Reacher and House of the Dragon context fixes are
retained. Old four-field EpData remains readable; saved title or native metadata
can recover its context. An old payload with neither title nor usable metadata
cannot safely discover a missing path; reopening the title creates the new
payload. No media is fabricated for that case.

`PlaybackContextRegression.kt` tests the captured ID/path, wrong/ambiguous IDs,
public lookup errors, malformed references, caching, cancellation, timeout,
actual detail-to-playback context propagation, 0/0 movie semantics, exact 2/4
series semantics, and validated callbacks through a local HTTP media fixture.
The existing playback and episode metadata regressions are retained.

The four Adi production engines are unchanged. Their parity harness removes
only the explicitly asserted standalone context hook when comparing core H5
flow, just as it already normalizes their independent metadata bridge. It
continues asserting full parser/interceptor/transport parity and runs all four
mapping/integration suites. The new standalone context tests are not copied into
the independent Adi plugins.

Verification:

```sh
python3 Moviebox/tests/run_regression.py
python3 AdiDrakor/tests/run_migration.py
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s Moviebox/tests/diagnostics -p 'test_*.py'
bash gradlew :Moviebox:assembleRelease
bash gradlew makePluginsJson
```

Fixture PASS, live H5 response PASS, validated live CDN media and Android device
playback must be reported separately. A fixture is never device playback proof.

## Bilingual follow-up

Native/original/saved titles may now differ by language. Context recovery tries
at most three nonempty distinct server/saved titles under the same six-second
deadline and still accepts only the exact subject ID. Public catalog search also
augments native search for localized queries. See
`AdiDrakor/tests/BILINGUAL_DISCOVERY_CONTRACT.md` for fresh observations and limits.
