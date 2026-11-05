package com.flipdesk.app.net

import android.annotation.SuppressLint
import android.net.Network
import okhttp3.OkHttpClient
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * Trusts exactly one certificate: the PC's self-signed cert, identified by the SHA-256
 * fingerprint the phone learned from the pairing QR code. No CA is trusted.
 */
@SuppressLint("CustomX509TrustManager") // intentional: exact-certificate pinning, stricter than CA validation
class PinnedTrustManager(private val sha256Hex: String) : X509TrustManager {
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) =
        throw CertificateException("client certificates are not used")

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val leaf = chain?.firstOrNull() ?: throw CertificateException("no server certificate")
        val fp = MessageDigest.getInstance("SHA-256").digest(leaf.encoded).joinToString("") { "%02x".format(it) }
        if (!MessageDigest.isEqual(fp.toByteArray(), sha256Hex.lowercase().toByteArray())) {
            throw CertificateException("certificate fingerprint mismatch")
        }
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

object PinnedClient {
    private val base = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        // A dead link (PC asleep, Wi-Fi gone) is noticed within ~10 s instead of hanging.
        .pingInterval(5, TimeUnit.SECONDS)
        .build()

    /**
     * [network] pins the sockets to the Wi-Fi network, so the PC stays reachable even when
     * Android routes everything else over mobile data (e.g. Wi-Fi without internet).
     */
    fun forFingerprint(fp: String, network: Network? = null): OkHttpClient {
        val tm = PinnedTrustManager(fp)
        val ctx = SSLContext.getInstance("TLS").apply { init(null, arrayOf(tm), SecureRandom()) }
        return base.newBuilder()
            .sslSocketFactory(ctx.socketFactory, tm)
            // The certificate itself is pinned, so the hostname (a LAN IP that may change) is irrelevant.
            .hostnameVerifier { _, _ -> true }
            .apply { if (network != null) socketFactory(network.socketFactory) }
            .build()
    }
}
