package com.cryptochief.processing.ton

import com.cryptochief.processing.NetworkException
import com.cryptochief.processing.http.CanonicalJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

internal class TonRpcClient(
    private val merchantId: String,
    private val baseUrl: String,
    private val http: OkHttpClient,
    private val userAgent: String,
) {
    private val jettonWalletCache = ConcurrentHashMap<String, String>()
    private val jsonMedia = "application/json".toMediaType()
    private val json = CanonicalJson.json

    suspend fun lookupJettonWallet(jettonMaster: String, owner: String): String {
        require(jettonMaster.isNotEmpty()) { "jettonMaster is required" }
        require(owner.isNotEmpty()) { "owner is required" }
        val cacheKey = "$owner|$jettonMaster"
        jettonWalletCache[cacheKey]?.let { return it }

        runCatching { resolveViaRunMethod(jettonMaster, owner) }
            .onSuccess { addr ->
                jettonWalletCache[cacheKey] = addr
                return addr
            }

        val viaIndex = resolveViaIndex(jettonMaster, owner)
        jettonWalletCache[cacheKey] = viaIndex
        return viaIndex
    }

    suspend fun hasJettonWallet(jettonMaster: String, owner: String): Boolean = try {
        val url = buildUrl("/jetton/wallets") {
            it.addQueryParameter("owner_address", owner)
            it.addQueryParameter("jetton_address", jettonMaster)
            it.addQueryParameter("limit", "1")
        }
        val body = get(url)
        val arr = json.parseToJsonElement(body).jsonObject["jetton_wallets"]?.jsonArray
        !arr.isNullOrEmpty()
    } catch (_: Throwable) {
        false
    }

    private suspend fun resolveViaRunMethod(jettonMaster: String, owner: String): String {
        val ownerCell = CellBuilder().storeAddress(TonAddress.parse(owner)).endCell()
        val ownerBoc = Base64.getEncoder().encodeToString(ownerCell.toBoc())

        val payload = buildJsonObject {
            put("address", jettonMaster)
            put("method", "get_wallet_address")
            put("stack", buildJsonArray {
                addJsonObject {
                    put("type", "slice")
                    put("value", ownerBoc)
                }
            })
        }
        val text = post("/runGetMethod", json.encodeToString(JsonObject.serializer(), payload))
        val response = json.parseToJsonElement(text).jsonObject
        val exitCode = response["exit_code"]?.jsonPrimitive?.intOrNull
        if (exitCode != null && exitCode != 0) {
            throw NetworkException("ton/runGetMethod: exit_code=$exitCode")
        }
        val stack = response["stack"]?.jsonArray
            ?: throw NetworkException("ton/runGetMethod: missing stack")
        if (stack.isEmpty()) throw NetworkException("ton/runGetMethod: empty stack")
        val first = stack[0].jsonObject
        val type = first["type"]?.jsonPrimitive?.contentOrNull
        val value = first["value"]?.jsonPrimitive?.contentOrNull
        if (type != "slice" || value.isNullOrEmpty()) {
            throw NetworkException("ton/runGetMethod: unexpected stack[0]")
        }
        return resolveViaIndex(jettonMaster, owner)
    }

    private suspend fun resolveViaIndex(jettonMaster: String, owner: String): String {
        val url = buildUrl("/jetton/wallets") {
            it.addQueryParameter("owner_address", owner)
            it.addQueryParameter("jetton_address", jettonMaster)
            it.addQueryParameter("limit", "1")
        }
        val body = get(url)
        val root = try {
            json.parseToJsonElement(body).jsonObject
        } catch (e: SerializationException) {
            throw NetworkException("ton/jetton-wallets: ${e.message}", e)
        }
        val wallets = root["jetton_wallets"]?.jsonArray ?: JsonArray(emptyList())
        if (wallets.isEmpty()) {
            throw NetworkException(
                "no Jetton wallet for owner $owner on master $jettonMaster",
            )
        }
        val rawAddr = wallets[0].jsonObject["address"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val book = root["address_book"]?.jsonObject
        val friendly = book?.get(rawAddr)?.jsonObject?.get("user_friendly")?.jsonPrimitive?.contentOrNull
        return friendly?.takeIf { it.isNotEmpty() } ?: rawAddr
    }

    private fun buildUrl(path: String, configure: (okhttp3.HttpUrl.Builder) -> Unit): String {
        val base = "${baseUrl.trimEnd('/')}/ton-v3/$merchantId${if (path.startsWith('/')) path else "/$path"}"
        val builder = base.toHttpUrl().newBuilder()
        configure(builder)
        return builder.build().toString()
    }

    private suspend fun get(url: String): String = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("User-Agent", userAgent)
            .get()
            .build()
        execute(req)
    }

    private suspend fun post(path: String, body: String): String = withContext(Dispatchers.IO) {
        val url = "${baseUrl.trimEnd('/')}/ton-v3/$merchantId${if (path.startsWith('/')) path else "/$path"}"
        val req = Request.Builder()
            .url(url)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .header("User-Agent", userAgent)
            .post(body.toByteArray(Charsets.UTF_8).toRequestBody(jsonMedia))
            .build()
        execute(req)
    }

    private fun execute(req: Request): String {
        val response = try {
            http.newCall(req).execute()
        } catch (e: IOException) {
            throw NetworkException("ton: ${req.method} ${req.url}: ${e.message}", e)
        }
        return response.use {
            val text = it.body?.string().orEmpty()
            if (!it.isSuccessful) {
                throw NetworkException("ton: ${req.method} ${req.url}: HTTP ${it.code}: ${text.take(256)}")
            }
            text
        }
    }
}
