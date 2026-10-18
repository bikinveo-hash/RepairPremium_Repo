> Source history below is retained for context. For this repository, use
> `MIGRATION_REPAIR.md` and the adapted runner; standalone Moviebox is not replaced.

# Four independent Moviebox H5 playback ports

Reference: Moviebox at `4a4d272f96424a8ab340b8a63a3a44d63d69d0b8`.

Each target module owns `*MovieboxEngine`, `*MovieboxPlayback`, and
`*MovieboxMapping` in its own package. Plugin registration attaches only that
module's context and registers the original TMDB MainAPI. Context/identity
preferences and in-memory sessions are separate. No production source is
included from another provider.

The H5 flow, scoped media interceptor, stream schemas/validation, audio filtering,
captions, signed-policy handling and verified Reacher/House of the Dragon paths
are ported without behavior changes. Unreachable old mobile playback profiles,
Moviebox catalog UI, plugin registration and episode display enrichment are not
copied. Existing TMDB catalogs, metadata, episode stills and indices remain in
the original providers. The optional alternative-title payload is additive;
legacy LinkData and XtreamLinkData remain readable.

Mapping uses exact normalized title aliases, release year and explicit movie/TV
kind from authoritative same-ID detail, rather than rejecting sparse search rows.
It gathers up to eight staged queries (twenty native results per query) and merges
Moviebox's public server-rendered catalog discovery. A failed optional alias does
not discard validated candidates from another query. The public catalog fallback
also works when the mobile session is unavailable; it is Moviebox metadata, never
an alternate media engine. Only inert `__NUXT_DATA__` JSON references are decoded;
no scripts are executed, no browser cookies or credentials are copied.

Ambiguous subject IDs collapse only when the server explicitly declares their
membership in the same `dubs` family, marks exactly one original, and that original
passes independent title/year/type verification. Unrelated same-title identities
remain ambiguous. Native metadata is enriched with a same-ID public detailPath
and declared dubs, then passed as request-local H5 metadata. The original=true dub entry's declared ISO language is preserved in that
context so Korean/Japanese original audio is not mistaken for an unsupported
dub. No new title-specific subject IDs or slugs are hardcoded in the adapter. The playback flow differs from the reference
only by this verified metadata context bridge; its transport/auth/audio/validation
behavior and standalone Moviebox production source stay unchanged.

TV display suffixes such as S1-S3 may identify the series only when the requested
season is inside that range; exact season/maxEp server checks still apply. Movies
use 0/0. Seasons and episodes are never renumbered. Unknown detail years/types,
unrelated editions and unsupported season numbering still fail closed.

Observed Insidious evidence: TMDB 1291595 has release date 2026-08-19; the public
Moviebox page declares subject 8252531803681080504, release date 2026-08-21,
path `insidious-out-of-the-further-06560hmGCP9`, and seven dub IDs plus the marked
original. Sanitized metadata and a minimal equivalent Nuxt reference table are in
`fixtures/insidious-public-*`. These are catalog observations, not live media proof.

Every API request is bounded; cancellation propagates. Mapping plus playback
has a 150-second overall budget. Media transport keeps the reference connect,
read and call limits. No credentials, response bodies or complete signed media
URLs are written to diagnostics.

## Verification

```sh
python3 AdiDrakor/tests/run_migration.py
# Optional live probe, separate from fixture/device proof:
python3 AdiDrakor/tests/probe_insidious_catalog.py
python3 -m unittest discover -s Moviebox/tests/diagnostics -p 'test_*.py'
./gradlew :AdiDrakor:assembleRelease :AdiFilmSemi:assembleRelease :AdiXtream:assembleRelease :Adicinemax21:assembleRelease
./gradlew makePluginsJson
python3 AdiDrakor/tests/verify_artifacts.py /path/to/distribution /path/to/previous/plugins.json
```

The runner first executes the unchanged Moviebox regression suite. It then
compiles each production module separately against the actual CloudStream SDK,
checks byte parity of the H5 flow/interceptor and playback helper, and replays
the original playback regression/integration fixtures against every local port.
Additional mapping tests cover identity, ambiguity, missing seasons, cancellation,
timeout and the actual target MainAPI-to-H5 path using a local HTTP media server.
Test sources/dependencies are never packaged into the plugins.

Fixture success does not prove live service or Android playback. In this
execution environment the real Moviebox authentication request returned HTTP 403
with `Service not available in current region`; no geographic workaround was
used. Device playback, Android UI and buffering require device verification.

The distribution workflow verifies all previously listed plugin identities are
retained and checks the four target manifests plus standalone Moviebox, DEX,
versions, byte sizes and SHA-256 hashes before publication. It requires the
existing builds branch and performs a normal append-only push, never an orphan
branch recreation or force push. The root Gradle configuration is unchanged.

## Identity-aware staged discovery (recording 585560.mp4)

The recording shows Spider-Man: Brand New Day returning no links while other
selected titles play. Its public catalog declares subject 6026412232966389904,
path `spider-man-brand-new-day-Aea8ARY1bb7`, movie type and year 2026. Public H5
returned one DASH and three direct candidates in this environment. This does
not establish that every CDN resource validates or that Android playback passes.

The MainAPI now carries the selected TMDB/IMDb identity, synopsis, runtime and
principal cast in optional playback payload fields. Old payloads still retain
their selected TMDB ID. Matching checks the authoritative TMDB search for
same-title/same-year works: one verified work can proceed; ambiguous works need
an exact external-ID anchor, two matching cast names, or a distinguishing plot
fingerprint (with runtime support for shorter summaries). Conflicting IDs are
always rejected. Truncated TMDB result sets never establish uniqueness.
Release-year differences may be accepted only with an exact external-ID match.

Discovery uses staged exact/original/alternative titles, punctuation variants
and an optional franchise prefix, up to eight queries. Broader queries never
relax the final selected-work checks. Every result in the current query is
validated; a trusted primary match can return without waiting for slow unused
aliases. Sparse native/public metadata is merged without losing usable cast or
synopsis fields. Temporary detail failures may retry once; contradictions stay
rejected. Movie/TV kind and exact server season/episode checks remain in force.

The added fixtures retain only public catalog metadata for the recorded title.
Tests include the actual MainAPI-to-H5 subject/path contract, same-title/year
work ambiguity, external-ID conflicts, wrong stories, typed work IDs, search
truncation, sparse metadata, bounded retry and skipped unnecessary aliases.

Alternative-title-only matches require corroboration even when the primary-title
TMDB search has one result, preventing cross-film alias collisions.

## Smart source finder V2 (recording 585571.mp4)

The recording shows Spider-Man failing after a long spinner and House of the
Dragon S1E1 failing. Device logs are unavailable, so the recording alone cannot
identify the exact failing HTTP request. The code audit reproduced these gaps:
mobile authentication gated discovery; native pagination was ignored; multiple
original editions were treated as unrelated; TV required mobile season-info even
when public detail contained authoritative resource.seasons. Public detail also
places principal cast in the owning detail wrapper's stars array, not necessarily
inside subject.staffList. These fields are now decoded only through that same
subject/resource wrapper. Recommendation rows cannot supply another subject's
season proof.

The independent local engines now implement the same staged algorithm. There
are at most twelve query-page operations, three native pages per query, thirty-two
candidate IDs and two detail attempts per ID; detail prefetch concurrency is three
(up to native/public pairs). Primary public discovery, mobile authentication and
selected-TMDB metadata run concurrently. Discovery has a 35-second deadline and
the entire resolution a 90-second deadline. These are ceilings, not measured
Android loading times. Caller cancellation propagates.

The planner prioritizes primary/original titles, normalized punctuation, then
more distinctive official aliases, followed by a franchise discovery query.
Pagination is followed only when the native response explicitly advertises it;
no undocumented pagination is invented for public SSR. Results are deduplicated
by subject ID and enriched with same-ID detail. Failure of one query/backend
never grants playback permission or substitutes another title.

Selected-TMDB detail is fetched by its exact typed ID, including English synopsis,
cast and official aliases. The UI payload and catalog remain unchanged. Cached
catalog/TMDB metadata lasts thirty seconds in an instance-local bounded map;
identity decisions and signed media URLs are not cached. Authentication success
and failure have short, separate lifetimes to avoid repeated bootstrap requests.

External-ID conflicts, media-type mismatch and different complete titles remain hard rejects.
Movie year differences require an external anchor. A later TV edition year requires
both matching cast and a strong plot fingerprint plus an explicit requested season
in same-subject public metadata; season 1 cannot use that exception. Missing years may be
verified by exact external IDs or independent cast/plot evidence. Normalization
uses Unicode NFKD and preserves full-title meaning. **TMDB title/year uniqueness
is now discovery evidence only, never playback authorization for a selected TMDB
request.** At least an exact external ID, two independently supplied principal
cast names with title/type/year agreement, or a distinguishing content-word plot
fingerprint is required. Plot proof requires eight shared meaningful words and
50% overlap, or four shared words/25% plus agreeing runtime; it must beat known
competing TMDB works by 15 percentage points. Contradictory external IDs always
win over corroboration; a plot pointing to another known work and a grossly
inconsistent movie runtime also reject. These conservative thresholds are covered
by positive and wrong-work fixtures; they are not a universal accuracy guarantee.
Old pure schema test requests without a selected TMDB ID retain the title/year
helper contract; every registered MainAPI requires the real selected TMDB ID.

Multiple IDs collapse through an explicit server-declared dub family only after
the original passes verification. Separate original editions may be attempted
(up to three) only if each independently verifies the selected work through ID,
cast or plot evidence. A same-title/year coincidence cannot create an edition
relationship. Playback stops after the first edition returns validated media. If all currently
verified editions fail H5, discovery continues through remaining queries within
the unused discovery budget. Request-local caches deduplicate search pages,
same-ID detail and season reads; failed media subjects are excluded and every
later edition still requires full verification.

For TV, successful mobile season-info is preferred. An explicit negative cannot
be overridden. When that endpoint is unavailable, the owning public detail's
resource.seasons may prove the exact season/maxEp. H5 is still requested with the
unchanged season and episode and its response/media are validated by the original
port. No first-episode fallback, season renumbering or trailer substitution exists.

H5 transport, signing, stream validation, media header scope, audio and subtitles
remain identical to the reference after removing the additive metadata bridge
and diagnostic observer. The runner asserts that equality and byte parity of the
four algorithms, including diagnostics. Structured FINDER diagnostics report
stage, reason, candidate/verified counts and elapsed milliseconds, without API
keys, tokens, cookies or signed media URLs. H5_NO_STREAM and H5_MEDIA_INVALID
are separated by an observer of the existing parser/validator result.

Tests additionally replay observed public House of the Dragon originals, S1E1,
S2E4, unavailable episodes, missing bearer, pagination ambiguity, missing selected
year, external-ID conflicts, normalized and late official aliases, cache namespace
collisions/expiration, and rejection of title/year-only authorization. The
existing original suites still cover Reacher, DASH/HLS/direct media, expired
signatures, audio, subtitle, scoped credentials and mismatched H5 episodes.
Updated prior success fixtures now provide independently selected metadata;
no negative safety assertion was removed to permit a weaker match.

Optional live response probe (does not validate CDN media or device playback):

```sh
python3 AdiDrakor/tests/probe_finder_v2.py
```

Only public subject metadata is saved in fixtures; no signed media resources or
session data are persisted. Fixture and local HTTP success must be reported
separately from live H5 responses and actual Android playback.

## V4 follow-up

See `SPIDER_V4_CONTRACT.md`. The reference remains read-only. The four local ports
now deliberately differ in request-local sampling/pacing, complete-manifest reuse,
early callbacks and sanitized diagnostics, plus independent cross-language proof.
Parser/header/interceptor/security contracts and all original behavioral fixtures
remain enforced; byte parity is no longer incorrectly required for the changed
validator and scheduler. Current end-to-end deadline remains 90 seconds.
