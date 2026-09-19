package hevel.skin

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class HevelApiClient(private val rawHost: String) {
    private val base = (if (rawHost.startsWith("http")) rawHost else "http://$rawHost").trimEnd('/')

    data class Response(val code: Int, val body: String) { val ok get() = code in 200..299 }

    fun status() = request("GET", "/api/skins/status")
    fun index() = request("GET", "/api/skins/index")

    fun manifest(parentId: String, variant: SkinVariant) = request("GET", HevelApi.manifestPath(parentId, variant))

    fun postManifest(parentId: String, variant: SkinVariant, json: String) =
        request("POST", HevelApi.manifestPath(parentId, variant), json, "application/json")

    fun postIndex(json: String) = request("POST", "/api/skins/index", json, "application/json")

    fun activate(parentId: String, variant: SkinVariant) = form(
        "/api/skins/activate",
        mapOf(
            "bank" to if (variant.isBase) "SKN" else "LZR",
            "skin" to HevelApi.wireId(parentId, variant),
            "parent" to parentId,
            "variant" to if (variant.isBase) "BASE" else "ALT",
            "alt" to if (variant.isBase) "" else variant.id
        )
    )

    fun activateDefault() = form(
        "/api/skins/activate",
        mapOf("bank" to "SKN", "skin" to "", "mode" to "DEFAULT")
    )

    fun deleteParent(parent: SkinParent): Response {
        val variants = listOf(parent.base) + parent.alts
        var last = Response(200, "{\"ok\":true}")
        variants.forEach { variant ->
            val response = form("/api/skins/delete", mapOf("skin" to HevelApi.wireId(parent.id, variant)))
            if (!response.ok && response.code != 404) return response
            last = response
        }
        return if (last.code == 404) Response(200, "{\"ok\":true,\"alreadyMissing\":true}") else last
    }

    fun deleteVariant(parentId: String, variant: SkinVariant) =
        form("/api/skins/delete", mapOf("skin" to HevelApi.wireId(parentId, variant)))

    fun clearSlot(parentId: String, variant: SkinVariant, slot: SkinSlot) =
        form("/api/skins/clear", mapOf("skin" to HevelApi.wireId(parentId, variant), "slot" to slot.wireName))

    fun exitLoader() = form("/api/loader/exit", emptyMap())

    fun configureSharedWifi(ssid: String, pass: String) =
        form("/api/network", mapOf("mode" to "STA", "ssid" to ssid, "pass" to pass))

    fun upload(parentId: String, variant: SkinVariant, slot: SkinSlot, kind: String, filename: String, mime: String, bytes: ByteArray): Response {
        val url = URL(base + HevelApi.uploadPath(parentId, variant, slot, kind))
        val c = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 2500
            readTimeout = 30000
            useCaches = false
            doOutput = true
            setRequestProperty("Content-Type", mime)
            setRequestProperty("Connection", "close")
            setRequestProperty("X-Hevel-Filename", filename)
            setFixedLengthStreamingMode(bytes.size)
        }
        c.outputStream.use { out -> out.write(bytes) }
        return read(c)
    }

    private fun form(path: String, values: Map<String, String>): Response {
        val body = values.entries.joinToString("&") { "${enc(it.key)}=${enc(it.value)}" }
        return request("POST", path, body, "application/x-www-form-urlencoded")
    }

    private fun request(method: String, path: String, body: String? = null, type: String = "text/plain"): Response {
        val c = (URL(base + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 2500
            readTimeout = 8000
            useCaches = false
            setRequestProperty("Connection", "close")
        }
        if (body != null) {
            val payload = body.toByteArray(StandardCharsets.UTF_8)
            c.doOutput = true
            c.setRequestProperty("Content-Type", type)
            c.setFixedLengthStreamingMode(payload.size)
            c.outputStream.use { it.write(payload) }
        }
        return read(c)
    }

    private fun read(c: HttpURLConnection): Response {
        val code = c.responseCode
        val stream = if (code in 200..299) c.inputStream else c.errorStream
        val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
        c.disconnect()
        return Response(code, body)
    }

    private fun enc(v: String) = URLEncoder.encode(v, StandardCharsets.UTF_8.name())
}
