import org.graalvm.polyglot.*;
import org.graalvm.polyglot.proxy.*;
import java.nio.file.*;
import java.util.*;

/**
 * Feasibility spike: boot Postman's REAL postman-sandbox browser bootcode inside GraalJS
 * (the runtime ReVoman already embeds), drive it through the uvm bridge protocol from Java,
 * and verify pm.test / pm.expect / pm.environment.set actually work — proving Approach A.
 *
 * Single-threaded + in-process, so we skip uvm's worker/postMessage/Flatted transport:
 *  - host->guest: capture the guest `bridge` Value before boot deletes the global, call bridge.emit(...) directly
 *  - guest->host: guest bridge.dispatch(...) -> __uvm_emit(FlattedString) -> __java_emit captures it
 */
public class Spike {

    // minimal single-threaded event loop (GraalJS has no timers of its own)
    static final class EventLoop {
        final ArrayDeque<Runnable> ready = new ArrayDeque<>();
        final PriorityQueue<long[]> timers = new PriorityQueue<>(Comparator.comparingLong(a -> a[0]));
        final Map<Long, Runnable> timerFns = new HashMap<>();
        long seq = 1, virtualNow = 0;

        long schedule(Runnable r, long delayMs) {
            long id = seq++;
            if (delayMs <= 0) ready.add(() -> { timerFns.remove(id); r.run(); });
            else { timers.add(new long[]{virtualNow + delayMs, id}); timerFns.put(id, r); }
            return id;
        }
        void clear(long id) { timerFns.remove(id); }
        void run() {
            int guard = 0;
            while (true) {
                if (++guard > 5_000_000) throw new RuntimeException("event loop runaway");
                if (!ready.isEmpty()) { ready.poll().run(); continue; }
                if (!timers.isEmpty()) {
                    long[] t = timers.poll();
                    Runnable r = timerFns.remove(t[1]);
                    if (r != null) { virtualNow = Math.max(virtualNow, t[0]); r.run(); }
                    continue;
                }
                break;
            }
        }
    }

    public static void main(String[] args) throws Exception {
        String bridgeClient = Files.readString(Path.of("/tmp/pm-spike/res/bridge-client.js"));
        String bootcode = Files.readString(Path.of("/tmp/pm-spike/res/bootcode.js"));

        EventLoop loop = new EventLoop();
        List<String> emits = new ArrayList<>(); // raw Flatted strings emitted guest->host

        try (Context ctx = Context.newBuilder("js")
                .allowExperimentalOptions(true)
                .option("js.esm-eval-returns-exports", "true")
                .option("js.ecmascript-version", "2024")
                .allowHostAccess(HostAccess.ALL)
                .allowHostClassLookup(s -> true)
                .build()) {

            Value bindings = ctx.getBindings("js");

            bindings.putMember("__java_setTimer", (ProxyExecutable) a -> {
                Value fn = a[0]; long delay = a.length > 1 && a[1].fitsInLong() ? a[1].asLong() : 0;
                Value[] extra = a.length > 2 ? Arrays.copyOfRange(a, 2, a.length) : new Value[0];
                return loop.schedule(() -> fn.executeVoid((Object[]) extra), delay);
            });
            bindings.putMember("__java_clearTimer", (ProxyExecutable) a -> {
                if (a.length > 0 && a[0].fitsInLong()) loop.clear(a[0].asLong());
                return null;
            });
            bindings.putMember("__java_emit", (ProxyExecutable) a -> {
                emits.add(a[0].asString());
                return null;
            });
            bindings.putMember("__java_btoa", (ProxyExecutable) a ->
                Base64.getEncoder().encodeToString(a[0].asString().getBytes(java.nio.charset.StandardCharsets.ISO_8859_1)));
            bindings.putMember("__java_atob", (ProxyExecutable) a ->
                new String(Base64.getDecoder().decode(a[0].asString()), java.nio.charset.StandardCharsets.ISO_8859_1));

            // Capture Java host fns in a closure so they survive bootcode's recreatingTheUniverse() global wipe.
            // The bootcode allowlists setTimeout/__uvm_emit etc., but NOT our __java_* helpers — so close over them.
            ctx.eval("js", String.join("\n",
                "(function(jSet, jClear, jEmit, jAtob, jBtoa){",
                "  globalThis.setTimeout = function(fn, d, ...rest){ return jSet(fn, d|0, ...rest); };",
                "  globalThis.clearTimeout = function(id){ return jClear(id); };",
                "  globalThis.setInterval = function(fn, d, ...rest){ return jSet(fn, d|0, ...rest); };",
                "  globalThis.clearInterval = function(id){ return jClear(id); };",
                "  globalThis.setImmediate = function(fn, ...rest){ return jSet(fn, 0, ...rest); };",
                "  globalThis.clearImmediate = function(id){ return jClear(id); };",
                "  globalThis.queueMicrotask = function(fn){ return jSet(fn, 0); };",
                "  globalThis.__uvm_emit = function(argsStr){ jEmit(argsStr); };",
                "  globalThis.__uvm_setTimeout = globalThis.setTimeout;",
                "  globalThis.Blob = globalThis.Blob || function Blob(){};",
                "  globalThis.File = globalThis.File || function File(){};",
                "  globalThis.FileReader = globalThis.FileReader || function FileReader(){};",
                "  globalThis.FormData = globalThis.FormData || function FormData(){};",
                "  globalThis.atob = function(s){ return jAtob(s); };",
                "  globalThis.btoa = function(s){ return jBtoa(s); };",
                "})(__java_setTimer, __java_clearTimer, __java_emit, __java_atob, __java_btoa);",
                bridgeClient
            ));

            // capture the guest bridge BEFORE bootcode deletes the global (closures keep the object alive)
            Value guestBridge = bindings.getMember("bridge");
            if (guestBridge == null || guestBridge.isNull()) { System.out.println("FAIL: no bridge after bridge-client"); return; }

            ctx.eval(Source.newBuilder("js", bootcode, "bootcode.js").build());
            loop.run();
            System.out.println("OK: bootcode booted. emits after boot: " + emits.size());

            // 1) initialize (no templates -> guest dispatches 'initialize' back)
            guestBridge.invokeMember("emit", "initialize", ProxyObject.fromMap(new HashMap<>()));
            loop.run();
            System.out.println("OK: initialized. emits now: " + emits.size());

            // 2) build the execute payload: (id, event, context, options)
            String execId = "spike1";
            String script = String.join("\n",
                "pm.environment.set('spikeKey', 'spikeVal-' + (1+1));",
                "pm.test('one plus one is two', function () { pm.expect(1 + 1).to.eql(2); });",
                "pm.test('env round-trips', function () { pm.expect(pm.environment.get('spikeKey')).to.eql('spikeVal-2'); });",
                "pm.test('intentional failure', function () { pm.expect(true).to.eql(false); });"
            );
            Map<String,Object> evScript = new HashMap<>();
            evScript.put("type", "text/javascript");
            evScript.put("exec", ProxyArray.fromArray((Object[]) new String[]{script}));
            Map<String,Object> event = new HashMap<>();
            event.put("listen", "test");
            event.put("script", ProxyObject.fromMap(evScript));

            Map<String,Object> environment = new HashMap<>();
            environment.put("id", "env1");
            environment.put("values", ProxyArray.fromArray());
            Map<String,Object> context = new HashMap<>();
            context.put("environment", ProxyObject.fromMap(environment));

            Map<String,Object> options = new HashMap<>();
            options.put("timeout", 5000);
            options.put("cursor", ProxyObject.fromMap(new HashMap<>()));

            guestBridge.invokeMember("emit", "execute", execId,
                ProxyObject.fromMap(event), ProxyObject.fromMap(context), ProxyObject.fromMap(options));
            loop.run();

            // 3) inspect emitted Flatted strings (string literals appear verbatim in Flatted output)
            System.out.println("---- EMITTED EVENTS (" + emits.size() + ") ----");
            String all = String.join("\n", emits);
            for (String e : emits) {
                String head = e.length() > 140 ? e.substring(0, 140) + "…" : e;
                System.out.println("  • " + head);
            }
            boolean hasResult   = all.contains("execution.result." + execId);
            boolean hasAssertion= all.contains("execution.assertion");
            boolean test1       = all.contains("one plus one is two");
            boolean test2       = all.contains("env round-trips");
            boolean failTest    = all.contains("intentional failure");
            boolean envMutated  = all.contains("spikeVal-2");

            System.out.println("---- CHECKS ----");
            System.out.println("execution.result dispatched : " + hasResult);
            System.out.println("assertion events present    : " + hasAssertion);
            System.out.println("pm.test #1 name present      : " + test1);
            System.out.println("pm.test #2 name present      : " + test2);
            System.out.println("pm.test #3 (failing) present : " + failTest);
            System.out.println("pm.environment.set value     : " + envMutated);

            boolean pass = hasResult && hasAssertion && test1 && test2 && failTest && envMutated;
            System.out.println(pass
                ? "\nSPIKE PASS ✅ — Postman's REAL pm API runs under embedded GraalJS"
                : "\nSPIKE INCOMPLETE ❌");
        }
    }
}
