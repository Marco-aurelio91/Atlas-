package com.unyx.atlas.chatcrypto

import org.conscrypt.Conscrypt
import java.security.Security

/**
 * Installs Conscrypt (Google's own JCA provider, Apache-2.0, BoringSSL-backed — see
 * `docs/ATLAS_CHAT_DESENHO_CRIPTOGRAFICO.md` §2) so `KeyAgreement.getInstance("XDH")`
 * (X25519) works on every supported device. The platform's built-in provider only guarantees
 * XDH from API 31 on; this module's `minSdk` is 30 (same as the rest of the app), so without
 * this, X25519 would simply be unavailable on an API 30 device.
 *
 * Idempotent and safe to call from multiple places (app startup, each test class) — installing
 * twice is a no-op, not an error. Call once, early, before any [ChatIdentity] or [ChatSession]
 * use — the Android app Hilt module, and each JVM test's `@Before`, should both call this.
 */
object ChatCryptoProvider {
    private const val NAME = "Conscrypt"

    @Volatile
    private var installed = false

    @Synchronized
    fun install() {
        if (installed) return
        if (Security.getProvider(NAME) == null) {
            // Inserted ahead of the platform provider specifically for XDH/X25519 — everything
            // else (AES-GCM, HMAC) keeps using whatever provider already handles it; Conscrypt
            // fully implementing those too is a bonus, not something this module depends on.
            Security.insertProviderAt(Conscrypt.newProvider(), 1)
        }
        installed = true
    }
}
