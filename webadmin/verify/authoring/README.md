# Real-component authoring fixture

This uses the production monitor/schedule editors, actual React and Chromium, with a controlled public host boundary and API responses. It does not simulate the engine.

```sh
npm ci --prefix webadmin
node --test webadmin/verify/authoring.test.mjs
node webadmin/verify/authoring/build.mjs
python3 -m http.server 8769 --bind 127.0.0.1 --directory webadmin/verify/authoring
# In another terminal; @playwright/test must be resolvable or supply a package root:
SENTINEL_PLAYWRIGHT_PACKAGE=/absolute/path/to/package.json node webadmin/verify/authoring/run.mjs
```

Close the owned HTTP server after the runner exits. The runner closes Chromium in finally and writes results/screenshots to evidence. The generated bundle is ignored. Controls are exercised in the real DOM with controlled input events; this is not keyboard/assistive technology certification. Fixture CSS is intentionally separate from host tokens; screenshots establish wrapping only. Review matrix and limitations: `docs/authoring/review.md`.
