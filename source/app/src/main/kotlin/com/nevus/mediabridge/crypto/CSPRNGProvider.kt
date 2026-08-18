package com.nevus.mediabridge.crypto

import com.nevus.mediabridge.util.NevusLog
import java.security.NoSuchAlgorithmException
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * Standard CSPRNG mitigation logic.
 *
 * Purpose — provide a single, hardened source of cryptographically-secure random data for the
 * whole app, aligned to NIST SP 800-90A guidance (reseeding, health monitoring, deterministic
 * bootstrap) rather than reaching for `SecureRandom()` in every call site.
 *
 * Design choices:
 *  - **Primary source**: `SecureRandom.getInstanceStrong()`. On Android this maps to
 *    `NativePRNGBlocking`, which reads from `/dev/random` on first use — blocking is fine at
 *    process warm-up but poison for the UI thread, so we always warm up ahead of time.
 *  - **Per-thread instance** via `ThreadLocal` — avoids the internal synchronization inside
 *    `SecureRandom` and removes contention for high-fanout callers (bubble filters, download
 *    integrity hashes, session tokens).
 *  - **Explicit reseed** via [reseed] — mix in fresh entropy on-demand (e.g. after long idle,
 *    after a health alarm from [CSPRNGHealthMonitor]).
 *  - **Health tap** via [tapForHealthMonitor] — a small (~256 byte) sample per burst is fed to
 *    the [com.nevus.mediabridge.audit.LiveStatisticalValidator] for continuous validation.
 *
 * Threading — every public function is safe from any thread; the `ThreadLocal.get()` cost is
 * comparable to a field read after first use.
 */
object CSPRNGProvider {

    private const val TAG = "CSPRNG"

    /** Bytes drawn since process start — for observability + reseed heuristics. */
    private val bytesServed = AtomicLong(0L)

    /** Reseed generation counter — increments on every explicit or scheduled reseed. */
    private val reseedGeneration = AtomicLong(0L)

    /** Optional health monitor; if set, receives a sampled slice of each burst. */
    private val healthMonitor = AtomicReference<CSPRNGHealthMonitor?>(null)

    /**
     * Fallback SecureRandom used only if `getInstanceStrong()` throws — some vendor forks or
     * emulators do not expose it. `SecureRandom()` on Android still resolves to
     * `AndroidOpenSSL` which is CSPRNG-quality; we log the downgrade for auditability.
     */
    private val instanceFactory: () -> SecureRandom = {
        try {
            SecureRandom.getInstanceStrong()
        } catch (t: NoSuchAlgorithmException) {
            NevusLog.w(TAG, "getInstanceStrong unavailable — falling back to SecureRandom()", t)
            SecureRandom()
        }
    }

    private val perThread = object : ThreadLocal<SecureRandom>() {
        override fun initialValue(): SecureRandom = instanceFactory()
    }

    /**
     * Pre-heat the strong RNG on a background thread so the first user-facing call does not
     * block collecting entropy on the UI thread. Idempotent.
     */
    fun warmUp() {
        thread(isDaemon = true, name = "csprng-warmup") {
            try {
                val start = System.nanoTime()
                val warm = perThread.get()!!  // realises the ThreadLocal + entropy gather
                warm.nextBytes(ByteArray(64))
                val ms = (System.nanoTime() - start) / 1_000_000
                NevusLog.i(TAG, "Warm-up finished in ${ms}ms (algo=${warm.algorithm})")
            } catch (t: Throwable) {
                NevusLog.e(TAG, "Warm-up failed", t)
            }
        }
    }

    /** Fill [dest] with cryptographically secure random bytes. */
    fun nextBytes(dest: ByteArray) {
        require(dest.isNotEmpty()) { "dest must be non-empty" }
        val rng = perThread.get()!!
        rng.nextBytes(dest)
        bytesServed.addAndGet(dest.size.toLong())
        healthMonitor.get()?.observeBurst(dest, sampleCap = 256)
    }

    /** Return [size] fresh random bytes. */
    fun nextBytes(size: Int): ByteArray {
        require(size in 1..(1 shl 24)) { "size out of range: $size" }
        val out = ByteArray(size)
        nextBytes(out)
        return out
    }

    /** Uniformly distributed Int on [Int.MIN_VALUE, Int.MAX_VALUE]. */
    fun nextInt(): Int = perThread.get()!!.nextInt().also {
        bytesServed.addAndGet(4)
    }

    /** Uniformly distributed Int on [0, bound). */
    fun nextInt(bound: Int): Int {
        require(bound > 0) { "bound must be > 0" }
        return perThread.get()!!.nextInt(bound).also { bytesServed.addAndGet(4) }
    }

    /** Uniformly distributed Long on the full 64-bit range. */
    fun nextLong(): Long = perThread.get()!!.nextLong().also {
        bytesServed.addAndGet(8)
    }

    /**
     * Mix externally-sourced entropy into the current thread's generator. Use for:
     *  - Post-alarm recovery (health monitor detected a statistical deviation).
     *  - Long-idle wakeup (screen off for hours; entropy pool may be stale).
     *  - After importing user-provided entropy (e.g. keystroke timings, sensor jitter).
     *
     * Never source [extraEntropy] from a low-entropy input like a wall-clock timestamp — that
     * merely creates the appearance of freshness. Use device sensors or another CSPRNG instance.
     */
    fun reseed(extraEntropy: ByteArray) {
        require(extraEntropy.isNotEmpty()) { "extraEntropy must be non-empty" }
        val rng = perThread.get()!!
        rng.setSeed(extraEntropy)  // additive on SecureRandom (does not replace state)
        reseedGeneration.incrementAndGet()
        NevusLog.i(TAG, "Reseeded with ${extraEntropy.size} bytes (gen=${reseedGeneration.get()})")
    }

    /**
     * Trigger a self-reseed by drawing from an independent SecureRandom instance. Cheaper than
     * caller-supplied entropy and safe as a default post-alarm response.
     */
    fun reseedSelf() = reseed(instanceFactory().generateSeed(32))

    /**
     * Attach a health monitor. All subsequent [nextBytes] bursts will feed a bounded sample
     * (max 256 bytes per burst) to the monitor. Passing `null` detaches.
     */
    fun tapForHealthMonitor(monitor: CSPRNGHealthMonitor?) {
        healthMonitor.set(monitor)
        NevusLog.d(TAG, "Health monitor ${if (monitor == null) "detached" else "attached"}")
    }

    /** Snapshot of runtime counters — useful for the audit surface. */
    fun stats(): Stats = Stats(
        bytesServed = bytesServed.get(),
        reseedGeneration = reseedGeneration.get(),
        algorithm = perThread.get()!!.algorithm,
    )

    data class Stats(
        val bytesServed: Long,
        val reseedGeneration: Long,
        val algorithm: String,
    )
}
