package de.asdfghasdfgh.sokuseki

import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Base64
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

data class SendResult(
    val success: Boolean,
    val retryable: Boolean,
    val code: Int,
    val message: String,
    val body: String = "",
)

/**
 * Minimal OwnTracks HTTP client. It deliberately depends only on the platform:
 * no OkHttp, no Google Play Services, no MQTT library.
 */
class HttpSender {

    fun send(config: TrackConfig, payload: String): SendResult {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(config.url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                doOutput = true
                useCaches = false
                instanceFollowRedirects = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "Sokuseki/${BuildConfig.VERSION_NAME} (Android)")
                // Home Assistant's HTTP mode identifies the tracker through these
                // headers; without X-Limit-U it throws the location away.
                setRequestProperty("X-Limit-U", config.user)
                setRequestProperty("X-Limit-D", config.device)
                if (config.password.isNotEmpty()) {
                    val token = Base64.getEncoder()
                        .encodeToString("${config.user}:${config.password}".toByteArray(Charsets.UTF_8))
                    setRequestProperty("Authorization", "Basic $token")
                }
                if (config.trustAllCerts) {
                    allowAllCertificates(this)
                }
            }

            connection.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }

            val code = connection.responseCode
            val body = readBody(connection, code)
            val success = code in 200..299
            val retryable = !success && (code == 401 || code == 403 || code == 408 ||
                code == 429 || code >= 500)
            SendResult(
                success = success,
                retryable = retryable,
                code = code,
                message = if (success) "HTTP $code" else "HTTP $code${body.takeIf { it.isNotBlank() }?.let { ": ${it.take(160)}" }.orEmpty()}",
                body = body.take(300),
            )
        } catch (e: SSLException) {
            SendResult(
                success = false,
                retryable = true,
                code = -1,
                message = "TLS-Fehler: ${e.message ?: e.javaClass.simpleName}",
            )
        } catch (e: SocketTimeoutException) {
            SendResult(false, true, -1, "Zeitüberschreitung")
        } catch (e: Exception) {
            SendResult(
                success = false,
                retryable = true,
                code = -1,
                message = "Netzwerkfehler: ${e.message ?: e.javaClass.simpleName}",
            )
        } finally {
            connection?.disconnect()
        }
    }

    private fun readBody(connection: HttpURLConnection, code: Int): String {
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        return stream?.bufferedReader(Charsets.UTF_8)?.use { reader ->
            val buffer = CharArray(1024)
            val builder = StringBuilder()
            while (builder.length < 4096) {
                val read = reader.read(buffer)
                if (read < 0) break
                builder.append(buffer, 0, read)
            }
            builder.toString()
        }.orEmpty()
    }

    private fun allowAllCertificates(connection: HttpURLConnection) {
        if (connection !is HttpsURLConnection) return
        val trustManager = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val context = SSLContext.getInstance("TLS")
        context.init(null, arrayOf<TrustManager>(trustManager), SecureRandom())
        connection.sslSocketFactory = context.socketFactory
        connection.hostnameVerifier = HostnameVerifier { _, _ -> true }
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 20_000
        private const val READ_TIMEOUT_MS = 25_000
    }
}
