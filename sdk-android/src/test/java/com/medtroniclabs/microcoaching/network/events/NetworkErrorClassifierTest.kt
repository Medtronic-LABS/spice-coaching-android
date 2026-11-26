package com.medtroniclabs.microcoaching.network.events

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertificateException
import java.security.cert.CertificateExpiredException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

/** Every row of the spec §7b `error_type` table, in table order. */
class NetworkErrorClassifierTest {

    private fun classify(
        error: Throwable,
        connected: Boolean = false,
        canceled: Boolean = false,
    ): NetworkErrorType = NetworkErrorClassifier.classify(error, connected, canceled)

    @Test
    fun wireNames_matchTheContractVocabulary() {
        assertEquals(
            listOf("connectionTimeout", "connectionError", "receiveTimeout", "badCertificate", "cancel", "unknown"),
            NetworkErrorType.entries.map { it.wireName },
        )
    }

    @Test
    fun canceledCall_isCancel_beforeAnyOtherRule() {
        assertEquals(NetworkErrorType.CANCEL, classify(IOException(), canceled = true))
        assertEquals(NetworkErrorType.CANCEL, classify(SocketTimeoutException(), connected = true, canceled = true))
        assertEquals(NetworkErrorType.CANCEL, classify(SSLPeerUnverifiedException("x"), canceled = true))
        assertEquals(NetworkErrorType.CANCEL, classify(IllegalStateException(), canceled = true))
    }

    @Test
    fun peerUnverified_isBadCertificate() {
        assertEquals(NetworkErrorType.BAD_CERTIFICATE, classify(SSLPeerUnverifiedException("x")))
    }

    @Test
    fun handshakeWithCertificateCause_isBadCertificate() {
        val direct = SSLHandshakeException("x").apply { initCause(CertificateException()) }
        assertEquals(NetworkErrorType.BAD_CERTIFICATE, classify(direct))

        val deep = SSLHandshakeException("x").apply {
            initCause(RuntimeException(CertificateExpiredException()))
        }
        assertEquals(NetworkErrorType.BAD_CERTIFICATE, classify(deep))
    }

    @Test
    fun handshakeWithoutCertificateCause_isConnectionError() {
        assertEquals(NetworkErrorType.CONNECTION_ERROR, classify(SSLHandshakeException("x")))
        val other = SSLHandshakeException("x").apply { initCause(IOException()) }
        assertEquals(NetworkErrorType.CONNECTION_ERROR, classify(other))
    }

    @Test
    fun timeoutNotConnected_isConnectionTimeout() {
        assertEquals(NetworkErrorType.CONNECTION_TIMEOUT, classify(SocketTimeoutException(), connected = false))
        assertEquals(NetworkErrorType.CONNECTION_TIMEOUT, classify(InterruptedIOException(), connected = false))
    }

    @Test
    fun timeoutConnected_isReceiveTimeout() {
        assertEquals(NetworkErrorType.RECEIVE_TIMEOUT, classify(SocketTimeoutException(), connected = true))
        assertEquals(NetworkErrorType.RECEIVE_TIMEOUT, classify(InterruptedIOException(), connected = true))
    }

    @Test
    fun connectFailures_areConnectionError() {
        listOf(
            UnknownHostException(),
            ConnectException(),
            NoRouteToHostException(),
            SocketException(),
            SSLException("x"),
        ).forEach { assertEquals(it.javaClass.simpleName, NetworkErrorType.CONNECTION_ERROR, classify(it)) }
    }

    @Test
    fun connectedFlag_doesNotChangeNonTimeoutRows() {
        assertEquals(NetworkErrorType.CONNECTION_ERROR, classify(ConnectException(), connected = true))
        assertEquals(NetworkErrorType.BAD_CERTIFICATE, classify(SSLPeerUnverifiedException("x"), connected = true))
        assertEquals(NetworkErrorType.UNKNOWN, classify(IOException(), connected = true))
    }

    @Test
    fun anythingElse_isUnknown() {
        assertEquals(NetworkErrorType.UNKNOWN, classify(IOException()))
        assertEquals(NetworkErrorType.UNKNOWN, classify(java.io.EOFException()))
        assertEquals(NetworkErrorType.UNKNOWN, classify(IllegalStateException()))
        assertEquals(NetworkErrorType.UNKNOWN, classify(Error()))
    }

    @Test
    fun cyclicCauseChain_terminates() {
        val a = SSLHandshakeException("a")
        val b = IOException(a)
        a.initCause(b)
        assertEquals(NetworkErrorType.CONNECTION_ERROR, classify(a))
    }

    @Test
    fun messageText_isIgnored() {
        assertEquals(NetworkErrorType.UNKNOWN, classify(IOException("connect timed out")))
        assertEquals(NetworkErrorType.UNKNOWN, classify(IOException("Canceled")))
    }
}
