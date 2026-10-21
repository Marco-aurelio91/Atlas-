package com.unyx.atlas.chatcrypto

import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.security.PrivateKey
import java.security.PublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.KeyAgreement

/** A device's chat identity key pair (X25519 / Curve25519), separate from the account root key
 * (`core/security`) — see `docs/ATLAS_CHAT_DESENHO_CRIPTOGRAFICO.md` §3: different trust domain,
 * different lifecycle (this changes on every device switch; the account key survives one via
 * recovery). Only the public key ever leaves this class/device. */
class ChatIdentityKeyPair private constructor(
    val privateKey: PrivateKey,
    val publicKeyBytes: ByteArray,
) {
    /** SHA-256 of the raw public key, formatted as space-separated hex groups — the safety-number
     *  style fingerprint two people compare presencially after pairing (§4). Not secret; this is
     *  exactly what the plan's "verificação presencial" step shows on screen. */
    fun publicKeyFingerprint(): String = fingerprintOf(publicKeyBytes)

    /** PKCS8-encoded private key, for `chat:storage`'s `ChatIdentityStore` to persist — this
     *  identity must survive an app restart (it's what the device re-pairs and decrypts past
     *  messages with), unlike per-conversation ratchet state, which is allowed to be ephemeral if
     *  it's lost (just forces a re-pairing). Only ever write this to a Keystore-encrypted store,
     *  same sensitivity as the account root key. */
    fun privateKeyBytes(): ByteArray = privateKey.encoded

    companion object {
        private const val ALGORITHM = "XDH"
        private const val CURVE = "X25519"

        fun generate(): ChatIdentityKeyPair {
            ChatCryptoProvider.install()
            val generator = try {
                KeyPairGenerator.getInstance(CURVE)
            } catch (_: NoSuchAlgorithmException) {
                KeyPairGenerator.getInstance(ALGORITHM)
            }
            val pair = generator.generateKeyPair()
            return ChatIdentityKeyPair(pair.private, pair.public.encoded)
        }

        /** Rebuilds an identity from what [privateKeyBytes] and the public key produced earlier —
         *  the counterpart `chat:storage` calls after loading both from its encrypted store. */
        fun restore(privateKeyBytes: ByteArray, publicKeyBytes: ByteArray): ChatIdentityKeyPair {
            ChatCryptoProvider.install()
            val keyFactory = try {
                KeyFactory.getInstance(CURVE)
            } catch (_: NoSuchAlgorithmException) {
                KeyFactory.getInstance(ALGORITHM)
            }
            val privateKey = keyFactory.generatePrivate(PKCS8EncodedKeySpec(privateKeyBytes))
            return ChatIdentityKeyPair(privateKey, publicKeyBytes)
        }

        fun fingerprintOf(publicKeyBytes: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(publicKeyBytes)
            return digest.joinToString(" ") { String.format("%02x", it) }
        }
    }
}

/** The X25519 key agreement itself — [theirPublicKeyBytes] is the X.509-encoded public key
 *  received from the other side's invite/pairing card (`docs/ATLAS_CHAT_Plano_Completo...` §8.2). */
fun ChatIdentityKeyPair.agree(theirPublicKeyBytes: ByteArray): ByteArray {
    ChatCryptoProvider.install()
    val keyFactory = try {
        KeyFactory.getInstance("X25519")
    } catch (_: NoSuchAlgorithmException) {
        KeyFactory.getInstance("XDH")
    }
    val theirPublicKey: PublicKey = keyFactory.generatePublic(X509EncodedKeySpec(theirPublicKeyBytes))
    val agreement = KeyAgreement.getInstance("XDH")
    agreement.init(privateKey)
    agreement.doPhase(theirPublicKey, true)
    return agreement.generateSecret()
}
