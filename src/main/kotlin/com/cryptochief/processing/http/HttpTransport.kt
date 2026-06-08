package com.cryptochief.processing.http

import com.cryptochief.processing.ApiException
import com.cryptochief.processing.DecodeException
import com.cryptochief.processing.ErrorCode
import com.cryptochief.processing.NetworkException
import com.cryptochief.processing.Options
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationException
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.IOException
import java.util.concurrent.TimeUnit

private val APPLICATION_JSON = "application/json".toMediaType()
private const val HEADER_MERCHANT = "Merchant"
private const val HEADER_SIGNATURE = "Signature"

internal class HttpTransport(
    private val options: Options,
    httpClient: OkHttpClient? = options.httpClient,
) {
    private val log: Logger = LoggerFactory.getLogger("com.cryptochief.processing")

    val http: OkHttpClient = httpClient ?: defaultClient(options)
    val json: Json = CanonicalJson.json

    suspend fun <Req, Resp> send(
        path: String,
        requestSerializer: SerializationStrategy<Req>,
        responseSerializer: DeserializationStrategy<Resp>,
        body: Req,
    ): Resp {
        val canonical = canonicaliseRequest(requestSerializer, body)
        val raw = sendRaw(path, canonical)
        if (raw.isEmpty()) {
            throw DecodeException("cryptochief: empty response body from $path")
        }
        return decodeResponse(path, responseSerializer, raw)
    }

    private fun <Req> canonicaliseRequest(serializer: SerializationStrategy<Req>, body: Req): ByteArray {
        val element = json.encodeToJsonElement(serializer, body)
        return CanonicalJson.encode(element)
    }

    private fun <Resp> decodeResponse(
        path: String,
        serializer: DeserializationStrategy<Resp>,
        raw: ByteArray,
    ): Resp = try {
        json.decodeFromString(serializer, raw.toString(Charsets.UTF_8))
    } catch (e: SerializationException) {
        throw DecodeException("cryptochief: decode $path response: ${e.message}", e)
    }

    private suspend fun sendRaw(path: String, canonical: ByteArray): ByteArray = withContext(Dispatchers.IO) {
        val signature = RequestSigner.sign(canonical, options.apiKey)
        val url = options.baseUrl + path
        val attempts = options.maxRetries + 1
        var lastException: RuntimeException? = null

        for (attempt in 0 until attempts) {
            if (attempt > 0) {
                val backoffMs = Backoff.delay(
                    attempt = attempt,
                    base = options.initialRetryDelay,
                    max = options.maxRetryDelay,
                ).toMillis()
                log.debug("cryptochief retry attempt={} delay={}ms path={}", attempt, backoffMs, path)
                delay(backoffMs)
            }

            val request = Request.Builder()
                .url(url)
                .post(canonical.toRequestBody(APPLICATION_JSON))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("User-Agent", options.userAgent)
                .header(HEADER_MERCHANT, options.merchantId)
                .header(HEADER_SIGNATURE, signature)
                .build()

            val response: Response = try {
                http.newCall(request).awaitResponse()
            } catch (e: IOException) {
                val netErr = NetworkException("cryptochief: request failed: ${e.message}", e)
                lastException = netErr
                if (attempt + 1 < attempts) continue else throw netErr
            }

            val status: Int
            val bytes: ByteArray
            try {
                status = response.code
                bytes = response.body?.bytes() ?: ByteArray(0)
            } catch (e: IOException) {
                response.closeQuietly()
                throw NetworkException("cryptochief: read response body: ${e.message}", e)
            } finally {
                response.closeQuietly()
            }
            log.debug("cryptochief response path={} status={} bytes={}", path, status, bytes.size)

            if (status in 200..299) return@withContext bytes

            val apiErr = parseApiError(status, bytes)
            if (status >= 500 && attempt + 1 < attempts) {
                lastException = apiErr
                continue
            }
            throw apiErr
        }
        throw lastException ?: NetworkException("cryptochief: retry budget exhausted")
    }

    private fun parseApiError(status: Int, body: ByteArray): ApiException {
        val text = body.toString(Charsets.UTF_8)
        var code: String? = null
        var message: String? = null
        try {
            val element = json.parseToJsonElement(text)
            val obj = element as? JsonObject
            if (obj != null) {
                val errorField = (obj["error"] as? JsonPrimitive)?.contentOrNull
                val msgField = (obj["msg"] as? JsonPrimitive)?.contentOrNull
                when {
                    msgField.isNullOrEmpty() || msgField == errorField -> {
                        code = errorField
                        message = null
                    }
                    errorField.isNullOrEmpty() || errorField == ErrorCode.SERVICE_ERROR -> {
                        code = msgField
                        message = null
                    }
                    else -> {
                        code = errorField
                        message = msgField
                    }
                }
            }
        } catch (_: SerializationException) {
        }
        val finalCode = code?.ifEmpty { null } ?: "HTTP_$status"
        val finalMessage = message ?: finalCode
        return ApiException(
            code = finalCode,
            status = status,
            description = finalMessage,
            raw = text.truncate(8 * 1024),
        )
    }

    private fun String.truncate(max: Int): String =
        if (length <= max) this else substring(0, max) + "…"

    private fun Response.closeQuietly() {
        try { close() } catch (_: Throwable) { }
    }

    private companion object {
        fun defaultClient(options: Options): OkHttpClient = OkHttpClient.Builder()
            .callTimeout(options.requestTimeout)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(options.requestTimeout)
            .writeTimeout(20, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}

internal suspend fun Call.awaitResponse(): Response = suspendCancellableCoroutine { cont ->
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (cont.isCancelled) return
            cont.resumeWith(Result.failure(e))
        }

        override fun onResponse(call: Call, response: Response) {
            cont.resumeWith(Result.success(response))
        }
    })
    cont.invokeOnCancellation {
        try { cancel() } catch (_: Throwable) { }
    }
}
