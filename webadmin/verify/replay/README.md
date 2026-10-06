# Activity replay interaction fixture

Actual Sentinel MonitorEditor and ActivityReplayPanel use React with controlled public host/API adapters and approximate host CSS. This is not live-engine or actual-host visual validation. No private host module is imported. Playwright dependency is supplied explicitly from an existing local installation; no host repository is changed.

From webadmin, run `node verify/replay/build.mjs`. Serve this directory on127.0.0.1:8772, then run `SENTINEL_PLAYWRIGHT_PACKAGE=/path/to/package.json node verify/replay/run.mjs`. Stop the owned server afterward. Browser cleanup runs in finally. Generated bundle is ignored; screenshots and JSON results are evidence, not runtime binaries. Host date-formatting adapter is deliberately minimal, so screenshot timestamp appearance is not a production formatting claim.

Scenarios cover inline invalid bounds, captured payload, duplicate click, pending field lock, API error/draft retention, stale draft response, action-time revoked permission, malformed response rejection, valid comparison/unknown runs and evidence, edited input result invalidation, unsupported mode, page errors and responsive overflow.
