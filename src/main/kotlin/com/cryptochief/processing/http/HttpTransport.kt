package com.cryptochief.processing.http

import com.cryptochief.processing.ApiException
import com.cryptochief.processing.DecodeException
import com.cryptochief.processing.ErrorCode
import com.cryptochief.processing.IdempotencyKey
import com.cryptochief.processing.NetworkException
import com.cryptochief.processing.Options
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
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
import kotlinx.serialization.json.longOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
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

internal class HttpTransport(
    private val options: Options,
    httpClient: OkHttpClient? = options.httpClient,
    private val epochSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    private val log: Logger = LoggerFactory.getLogger("com.cryptochief.processing")

    /** Added to the local clock for `X-CC-Timestamp`; set from `server_time`. */
    @Volatile
    private var clockOffsetSeconds: Long = 0

    val http: OkHttpClient = httpClient ?: defaultClient(options)
    val json: Json = SdkJson.instance

    /** `Merchant` value as sent and signed: [Options.merchantId] without leading and trailing spaces and tabs. */
    private val merchant: String = options.merchantId.trim(' ', '\t')

    suspend fun <Req, Resp> send(
        path: String,
        requestSerializer: SerializationStrategy<Req>,
        responseSerializer: DeserializationStrategy<Resp>,
        body: Req,
    ): Resp {
        val raw = request("POST", path, json.encodeToString(requestSerializer, body).toByteArray(Charsets.UTF_8))
        if (raw.isEmpty()) {
            throw DecodeException("cryptochief: empty response body from $path")
        }
        return decodeResponse(path, responseSerializer, raw)
    }

    /** [request], then decodes the response with [responseSerializer]. */
    suspend fun <Resp> request(
        method: String,
        path: String,
        body: ByteArray,
        responseSerializer: DeserializationStrategy<Resp>,
    ): Resp {
        val raw = request(method, path, body)
        if (raw.isEmpty()) {
            throw DecodeException("cryptochief: empty response body from $path")
        }
        return decodeResponse(path, responseSerializer, raw)
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

    /**
     * Sends [body] as is with [method]; the signature covers these bytes, the path with its
     * `%`-sequences decoded and the query as sent.
     */
    suspend fun request(method: String, path: String, body: ByteArray): ByteArray {
        require(method.isNotEmpty()) { "cryptochief: request method is required" }
        require(path.startsWith("/")) { "cryptochief: request path must start with \"/\": $path" }
        val httpMethod = RequestSigner.upperAscii(method)
        require(body.isEmpty() || permitsBody(httpMethod)) {
            "cryptochief: $httpMethod takes no request body"
        }
        val signedPath = RequestSigner.pathToSign(path)
        val idempotencyKey = currentCoroutineContext()[IdempotencyKey]?.value.orEmpty()
        return withContext(Dispatchers.IO) { sendSigned(httpMethod, path, signedPath, body, idempotencyKey) }
    }

    private suspend fun sendSigned(
        method: String,
        path: String,
        signedPath: String,
        body: ByteArray,
        idempotencyKey: String,
    ): ByteArray {
        val url = (options.baseUrl + path).toHttpUrl()
        val query = url.encodedQuery.orEmpty()
        val requestBody = if (body.isEmpty() && !requiresBody(method)) null else body.toRequestBody(APPLICATION_JSON)
        val attempts = options.maxRetries + 1
        var clockCorrected = false
        var attempt = 0

        while (true) {
            val timestamp = (epochSeconds() + clockOffsetSeconds).toString()
            val nonce = RequestSigner.newNonce()
            val hmac = RequestSigner.signHmacV1(
                apiKey = options.apiKey,
                timestamp = timestamp,
                nonce = nonce,
                method = method,
                path = signedPath,
                query = query,
                merchant = merchant,
                idempotencyKey = idempotencyKey,
                body = body,
            )

            val request = Request.Builder()
                .url(url)
                .method(method, requestBody)
                .apply { if (requestBody != null) header("Content-Type", "application/json") }
                .header("Accept", "application/json")
                .header("User-Agent", options.userAgent)
                .header(HEADER_MERCHANT, merchant)
                .header(RequestSigner.HEADER_TIMESTAMP, timestamp)
                .header(RequestSigner.HEADER_NONCE, nonce)
                .header(RequestSigner.HEADER_HMAC_SIGNATURE, RequestSigner.HMAC_V1_SIGNATURE_PREFIX + hmac)
                .apply {
                    if (idempotencyKey.isNotEmpty()) {
                        header(RequestSigner.HEADER_IDEMPOTENCY_KEY, idempotencyKey)
                    }
                }
                .build()

            val response: Response = try {
                http.newCall(request).awaitResponse()
            } catch (e: IOException) {
                val netErr = NetworkException("cryptochief: request failed: ${e.message}", e)
                if (attempt + 1 < attempts) {
                    attempt++
                    backoff(attempt, path)
                    continue
                }
                throw netErr
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

            if (status in 200..299) return bytes

            val parsed = parseError(status, bytes)
            val apiErr = parsed.exception
            if (!clockCorrected && apiErr.code == ErrorCode.SIGNATURE_TIMESTAMP_OUT_OF_RANGE && parsed.serverTime != null) {
                clockCorrected = true
                clockOffsetSeconds = parsed.serverTime - epochSeconds()
                log.debug("cryptochief clock offset={}s path={}", clockOffsetSeconds, path)
                continue
            }
            if (status >= 500 && attempt + 1 < attempts) {
                attempt++
                backoff(attempt, path)
                continue
            }
            throw apiErr
        }
    }

    private suspend fun backoff(attempt: Int, path: String) {
        val backoffMs = Backoff.delay(
            attempt = attempt,
            base = options.initialRetryDelay,
            max = options.maxRetryDelay,
        ).toMillis()
        log.debug("cryptochief retry attempt={} delay={}ms path={}", attempt, backoffMs, path)
        delay(backoffMs)
    }

    private class ParsedError(val exception: ApiException, val serverTime: Long?)

    /**
     * Error body in either envelope:
     * - gateway: `{"ok":false,"error":"<CODE>","msg":"...","server_time":...}`; the code is in
     *   `msg` when `error` is absent or `SERVICE_ERROR`;
     * - white-label platform: `{"data":null,"error":{"status":...,"name":...,"message":...,
     *   "details":{"code":"<CODE>","server_time":...}},"server_time":...}`; the code is
     *   `error.details.code`, else `error.name`.
     *
     * `server_time` is taken from the top level, then from `error.details`.
     */
    private fun parseError(status: Int, body: ByteArray): ParsedError {
        val text = body.toString(Charsets.UTF_8)
        var code: String? = null
        var message: String? = null
        var serverTime: Long? = null
        try {
            val obj = json.parseToJsonElement(text) as? JsonObject
            if (obj != null) {
                val error = obj["error"]
                if (error is JsonObject) {
                    val details = error["details"] as? JsonObject
                    code = details?.string("code")?.ifEmpty { null } ?: error.string("name")
                    message = error.string("message")?.takeIf { it != code }
                    serverTime = obj.number("server_time") ?: details?.number("server_time")
                } else {
                    val errorField = (error as? JsonPrimitive)?.contentOrNull
                    val msgField = (obj["msg"] as? JsonPrimitive)?.contentOrNull
                    when {
                        msgField.isNullOrEmpty() || msgField == errorField -> code = errorField
                        errorField.isNullOrEmpty() || errorField == ErrorCode.SERVICE_ERROR -> code = msgField
                        else -> {
                            code = errorField
                            message = msgField
                        }
                    }
                    serverTime = obj.number("server_time")
                }
            }
        } catch (_: SerializationException) {
        }
        val finalCode = code?.ifEmpty { null } ?: "HTTP_$status"
        val finalMessage = message?.ifEmpty { null } ?: finalCode
        val exception = ApiException(
            code = finalCode,
            status = status,
            description = finalMessage,
            raw = text.truncate(8 * 1024),
        )
        return ParsedError(exception, serverTime)
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private fun JsonObject.number(key: String): Long? =
        (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull

    private fun String.truncate(max: Int): String =
        if (length <= max) this else substring(0, max) + "…"

    private fun Response.closeQuietly() {
        try { close() } catch (_: Throwable) { }
    }

    private companion object {
        /** OkHttp refuses a body on these, and a server would not read one. */
        fun permitsBody(method: String): Boolean = method != "GET" && method != "HEAD"

        /** OkHttp requires a body on these, empty included. */
        fun requiresBody(method: String): Boolean =
            method == "POST" || method == "PUT" || method == "PATCH" || method == "PROPPATCH" || method == "REPORT"

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
