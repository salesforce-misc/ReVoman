# pm-sandbox feasibility spike (2026-06-04)

Standalone Java proof that Postman's real `postman-sandbox` browser `bootcode` boots inside
ReVoman's embedded GraalJS and that `pm.test`/`pm.expect`/`pm.environment.set` work via the
`uvm` bridge protocol driven from Java. Result: SPIKE PASS.

## Reproduce

```bash
# 1. get the resolved bundle + bridge-client from npm
cd /tmp && mkdir pm-probe && cd pm-probe && npm init -y && npm i postman-sandbox postman-collection
node -e "require('./node_modules/postman-sandbox/.cache/bootcode.browser.js')((e,c)=>require('fs').writeFileSync('/tmp/pm-spike/res/bootcode.js',c))"
node -e "require('fs').writeFileSync('/tmp/pm-spike/res/bridge-client.js', require('./node_modules/uvm/lib/bridge-client')())"

# 2. compile + run against the project's GraalJS jars (25.0.3)
CP=$(find ~/.gradle/caches -path '*org.graalvm*' -name '*.jar' ! -name '*-sources.jar' | tr '\n' ':')
javac -cp "$CP" Spike.java && java -cp ".:$CP" Spike
```

Expected tail: `SPIKE PASS ✅ — Postman's REAL pm API runs under embedded GraalJS`.

## Learnings folded into the design

- Browser-global shims needed under GraalJS: timers (`setTimeout`/`setImmediate`/`setInterval`/
  `clear*`), `queueMicrotask`, `Blob`/`File`/`FileReader`/`FormData`, `atob`/`btoa`.
  (`crypto`/`TextEncoder` already native in GraalJS 25.)
- `recreatingTheUniverse()` wipes globals → host helper fns must be closure-captured.
- Single-threaded in-process → skip uvm worker/postMessage; capture guest `bridge` before boot
  deletes it; call `bridge.emit(...)` with `ProxyObject`/`ProxyArray`.
- Guest→host dispatch payloads are Flatted (circular-safe JSON).
- execute payload shape: `execute(id, {listen, script:{exec:[...]}}, {environment:{values:[]}}, {timeout, cursor})`.
