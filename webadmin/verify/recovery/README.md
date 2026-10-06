# Recovery editor interaction fixture

Build with `node webadmin/verify/recovery/build.mjs`, serve this directory on localhost port 8770, then run `node webadmin/verify/recovery/run.mjs`. Supply `SENTINEL_PLAYWRIGHT_PACKAGE=/absolute/path/to/package.json` if Playwright lives in another installed package. The runner uses real React/editor components and controlled API responses; it closes Chromium in finally. Close the owned HTTP server after tests. Generated bundle is ignored, results/screenshots are retained under evidence. No live engine/database is used by this browser fixture.
