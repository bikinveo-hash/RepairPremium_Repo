# Playback adapters in four independent plugins

`AdiDrakor`, `AdiFilmSemi`, `AdiXtream` and `Adicinemax21` each package their own
`sources/` implementation. The existing Moviebox engine files remain unchanged.
No Idlix/OppaDrama MainAPI or plugin is registered. No sourceSets or runtime
references to another plugin are introduced.

`MediaIdentity` accepts verified IDs or an exact normalized official title/alias
plus release year and media type. Conflicting IDs, remake years, ambiguous matches
and missing identity evidence are rejected. Indonesian and English aliases are
hydrated from translations/alternative titles belonging to the selected TMDB ID.
No automatic translation or fuzzy fallback is used.

Idlix resolves search slugs to its own detail IDs, verifies a series' actual season
and episode ID, then uses play-info, its server countdown, session claim and the
local Majorplay extractor. Access denial/missing gate tokens are skipped; no
WebView challenge bypass is added. Requests requiring a countdown over 30 seconds
are skipped rather than claiming prematurely.

OppaDrama verifies detail metadata before using only that page's movie-version or
episode list. Unnumbered series catalogs represent season one only; missing lists
never fabricate episode one. Base64 mirrors, TurboVIP, Hydrax, FileLions and generic
CloudStream extractors retain the original provider's playback mechanisms.

The copied proxy/transport is private to each module, starts lazily for a mirror
that requires it, binds loopback on a kernel-selected port, limits concurrent
workers, bounds caches and retains the listener while playback can still use it.
It rewrites HLS playlists and preserves the original TS wrapper/Range and Hydrax
multipart/AES-prefix handling. Signed media/config diagnostics are suppressed.
Plugin `beforeUnload()` closes its listener and active sockets and clears transport caches.
The original OppaDrama proxy is untouched.

`MultiSource` launches isolated source tasks, shares metadata only between the two
additional adapters and serializes callbacks. Every URL is emitted once per load,
including repeated Moviebox URL entries with different catalog quality labels.
Headers, referer, type, extractor data and audio tracks are preserved. Only
additional-source links bypass the Moviebox interceptor; the native client handles
their resolver headers and range requests. Timeout jobs cannot emit late results.

## Validation

Run `python3 AdiDrakor/tests/run_regression.py`. It compiles every plugin separately
against the actual SDK, runs the untouched original Moviebox fixtures, replays H5
and mapping/media recovery fixtures against each local engine, and runs adapter
and loopback proxy fixtures for all four packages. Adapter equality and single
MainAPI registration are also checked. Tests are never packaged into plugins.

`MultiSourceRegression.kt.in` covers strict multilingual identity, ambiguity,
episode selection, all eight source-availability combinations, failures, callbacks,
timeouts, parent cancellation, headers, actual Majorplay/TurboVIP/FileLions/Hydrax
resolver paths and subtitles. `ProxyRegression.kt.in` exercises the real loopback
server with deterministic synthetic upstream bytes: HLS master/variant/segment,
TS prefix removal, seek ranges, preview filtering, denied redirects, decrypted
Hydrax MP4 ranges, and listener restart.

The Build workflow runs `writeCacheEntry` only for changed modules and checks
the generated packages with `Moviebox/tests/distribution.py` before publication.
The artifact verifier also checks the new adapters are present in each DEX and no
original source-plugin package is referenced.

## Limits

Fixture and HTTP transport success does not establish Android player success.
Live catalog requests to Idlix and OppaDrama from the implementation environment
returned HTTP 403. No live title playback, device seek/resume, or user-region
availability is claimed. Catalog entries without enough identity/season evidence
are intentionally omitted, even if a potentially related stream exists.
