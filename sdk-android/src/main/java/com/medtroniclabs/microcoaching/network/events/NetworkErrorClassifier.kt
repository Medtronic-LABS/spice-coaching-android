package com.medtroniclabs.microcoaching.network.events

import java.io.InterruptedIOException
import java.net.SocketException
import java.net.UnknownHostException
import java.security.cert.CertPathValidatorException
import java.security.cert.CertificateException
import java.util.Collections
import java.util.IdentityHashMap
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

/**
 * Maps a failed call to its contract `error_type`. Decides on exception types,
 * the cancel flag and whether the call reached the network layer (see
 * [ConnectionProbe]); never on message text, which differs between the JVM and
 * Android and is not something the network log may depend on.
 */
internal object NetworkErrorClassifier {

    fun classify(error: Throwable, connected: Boolean, canceled: Boolean): NetworkErrorType = when {
        canceled -> NetworkErrorType.CANCEL
        isBadCertificate(error) -> NetworkErrorType.BAD_CERTIFICATE
        error is InterruptedIOException ->
            if (connected) NetworkErrorType.RECEIVE_TIMEOUT else NetworkErrorType.CONNECTION_TIMEOUT
        error is UnknownHostException || error is SocketException || error is SSLException ->
            NetworkErrorType.CONNECTION_ERROR
        else -> NetworkErrorType.UNKNOWN
    }

    /**
     * An untrusted chain surfaces as a [CertificateException] cause on the JVM
     * and can surface as a [CertPathValidatorException] cause on Android
     * (Conscrypt), so either marks a handshake failure as a bad certificate.
     */
    private fun isBadCertificate(error: Throwable): Boolean =
        error is SSLPeerUnverifiedException ||
            (error is SSLHandshakeException && causeChainHasCertificateFailure(error))

    private fun causeChainHasCertificateFailure(error: Throwable): Boolean {
        val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        var current: Throwable? = error.cause
        while (current != null && seen.add(current)) {
            if (current is CertificateException || current is CertPathValidatorException) return true
            current = current.cause
        }
        return false
    }
}
