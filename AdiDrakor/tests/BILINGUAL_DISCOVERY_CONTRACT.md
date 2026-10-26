# English / Indonesian source discovery

Scope: four independent Adi identity adapters plus standalone Moviebox search/context.
The H5 parser, validation, signing, media headers, interceptor, audio policy and episode
indices are unchanged by this update.

## Identity contract

- Fetch `translations` with the existing selected-ID TMDB detail request, without an
  additional translation/search service. Accept metadata only when its ID equals
  the selected TMDB ID.
- Prioritize official English and Indonesian translation titles and ID/US/GB
  alternative titles before lower-priority country aliases within the existing
  twelve-query/page budget. Retain other official aliases for discovery.
- Compare each official synopsis separately; two weak translations cannot add up
  to identity proof. Existing external-ID conflicts, media type/year checks,
  cast/plot thresholds and exact season/episode verification still apply.
- A shared translated title alone cannot authorize playback.
- Standalone Moviebox queries native search and the first-party public catalog
  concurrently (five/six second limits), retaining the returned canonical IDs,
  titles and posters. Query language is not an automatic playback identity alias.
- Missing H5 navigation context may try native/original/saved display titles under
  one six-second total deadline. Only an exact selected subject ID can supply its
  path; no film substitution, inferred slug or translated franchise fallback.

## Observations: 2026-10-17 UTC

Owner recording `585826.mp4` shows Spider-Man failing in Adicinemax21 and MovieBox,
while other titles enter the player. The recording does not expose installed
plugin versions, HTTP errors or source-finder logs.

Live first-party public searches for both `Spider-Man: Brand New Day` and
`Spider-Man: Hari Baru` returned canonical subject `6026412232966389904`, path
`spider-man-brand-new-day-Aea8ARY1bb7`. Thus English discovery was not missing
this subject during this probe. TMDB ID `969681` returned no Indonesian translated
title; the adapter deliberately does not invent one. It does provide an Indonesian
synopsis. The sanitized fixtures preserve separate real TMDB and Moviebox
synopses and five names present in both selected-ID cast lists.

The same-subject H5 movie request (0/0) returned code 0, one DASH and three direct
resources with the proper path; an empty path returned no resources. All four
initial CDN samples returned HTTP 429 using the existing media headers. These
are unavailable samples, not passing media validation or successful playback.
No signed URL, cookie, token or user tracking identifier is retained here.
This environment cannot establish whether the owner's device receives the same
429, nor confirm playback after installation. Do not claim a bilingual search
change resolves a CDN restriction.

A follow-up JVM probe used the actual compiled `MovieboxPlayback.parse`,
`mediaHeaders`, `sample` and `validate` implementations. H5 returned HTTP 200 over
HTTP/2 and four parsed candidates. Validation rejected all four: DASH and one
MP4 produced no transport sample; two MP4 samples returned HTTP 429. No callback
or device playback is asserted. Six other existing H5 aliases also returned one
DASH/three direct resources; their initial Python DASH samples returned HTTP 426.
These are observations from this network, not evidence of the owner's device
HTTP status. The first reproduced failure after correct identity/path discovery
is media acquisition/validation, not missing bilingual title discovery.

## Tests

`python3 AdiDrakor/tests/run_regression.py` compiles all five providers against the
actual CloudStream SDK, checks independent-source parity, and exercises positive,
negative and registered-MainAPI fixtures. New cases cover official bilingual
aliases/synopses, wrong metadata owner, ambiguous alias, ID/year/type conflicts,
wrong episode, query budgets, distinct real Spider-Man metadata, and standalone
localized search/context recovery. Fixtures are identity/transport contracts,
not proof of live device playback.
