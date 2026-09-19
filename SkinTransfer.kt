package hevel.skin

import android.content.ContentResolver
import android.graphics.BitmapFactory
import android.net.Uri
import org.json.JSONObject

object SkinTransfer {
    data class SlotPayload(val source: ByteArray, val render: ByteArray, val sourceSha: String, val renderSha: String)

    fun buildPayload(resolver: ContentResolver, uriString: String): SlotPayload {
        val uri = Uri.parse(uriString)
        val source = resolver.openInputStream(uri)!!.use { it.readBytes() }
        val bitmap = BitmapFactory.decodeByteArray(source, 0, source.size) ?: error("PNG decode failed")
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val render = Rgb565aEncoder.encodeTrimmed(bitmap.width, bitmap.height, pixels).bytes
        bitmap.recycle()
        return SlotPayload(source, render, Hashing.sha256(source), Hashing.sha256(render))
    }

    fun remoteHashes(json: String): Map<String, Pair<String, String>> {
        if (json.isBlank()) return emptyMap()
        val root = JSONObject(json)
        val slots = root.optJSONObject("slots") ?: return emptyMap()
        val out = mutableMapOf<String, Pair<String, String>>()
        slots.keys().forEach { key ->
            val value = slots.optJSONObject(key) ?: return@forEach
            out[key] = value.optString("sourceSha") to value.optString("renderSha")
        }
        return out
    }

    fun manifest(parent: SkinParent, variant: SkinVariant, hashes: Map<String, Pair<String, String>>): String {
        val slots = JSONObject()
        hashes.forEach { (key, value) ->
            slots.put(key, JSONObject().put("sourceSha", value.first).put("renderSha", value.second))
        }
        return JSONObject()
            .put("schema", 2)
            .put("parentId", parent.id)
            .put("parentName", parent.name)
            .put("wireId", HevelApi.wireId(parent.id, variant))
            .put("variantId", variant.id)
            .put("variant", if (variant.isBase) "BASE" else "ALT")
            .put("altId", if (variant.isBase) JSONObject.NULL else variant.id)
            .put("name", variant.name)
            .put("order", variant.order)
            .put("slots", slots)
            .toString()
    }
}
