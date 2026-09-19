package hevel.skin

import java.io.ByteArrayOutputStream
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID

enum class SkinSlot(val wireName: String) {
    THREE_D_TOP("3D_TOP"),
    TWO_D_TOP("2D_TOP"),
    THREE_D_TACT("3D_TACT"),
    TWO_D_TACT("2D_TACT"),
    THREE_D_REAR("3D_REAR"),
    TWO_D_REAR("2D_REAR");

    companion object {
        fun fromWireName(value: String): SkinSlot? = entries.firstOrNull { it.wireName == value }
    }
}

private fun stableId(prefix: String): String = prefix + UUID.randomUUID().toString().replace("-", "").take(12).uppercase()

data class SkinVariant(
    val id: String,
    val name: String,
    val order: Int,
    val slotUris: Map<SkinSlot, String> = emptyMap(),
    val isBase: Boolean = false
) {
    fun rename(newName: String) = copy(name = newName.trim().ifBlank { name })
    fun withSlot(slot: SkinSlot, uri: String?) = copy(
        slotUris = slotUris.toMutableMap().apply {
            if (uri.isNullOrBlank()) remove(slot) else put(slot, uri)
        }
    )

    companion object {
        fun newBase(): SkinVariant = SkinVariant(stableId("B"), "Base", 0, isBase = true)
        fun newAlt(name: String, order: Int): SkinVariant =
            SkinVariant(stableId("A"), name.trim().ifBlank { "New ALT" }, order, isBase = false)
    }
}

data class SkinParent(
    val id: String,
    val name: String,
    val order: Int,
    val base: SkinVariant,
    val alts: List<SkinVariant> = emptyList(),
    val rememberedAltId: String? = null
) {
    fun rename(newName: String) = copy(name = newName.trim().ifBlank { name })
    fun activeVariant(altId: String?): SkinVariant = alts.firstOrNull { it.id == altId } ?: base
    fun variant(id: String): SkinVariant? = if (base.id == id) base else alts.firstOrNull { it.id == id }

    companion object {
        fun new(name: String, order: Int): SkinParent =
            SkinParent(stableId("P"), name.trim().ifBlank { "New Skin" }, order, SkinVariant.newBase())
    }
}

data class EncodedRaster(val width: Int, val height: Int, val bytes: ByteArray)

object Rgb565aEncoder {
    fun encodeTrimmed(width: Int, height: Int, argb: IntArray): EncodedRaster {
        require(width > 0 && height > 0 && argb.size >= width * height)
        var minX = width
        var minY = height
        var maxX = -1
        var maxY = -1
        for (y in 0 until height) for (x in 0 until width) {
            if ((argb[y * width + x] ushr 24) != 0) {
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
            }
        }
        if (maxX < minX || maxY < minY) {
            minX = 0; minY = 0; maxX = 0; maxY = 0
        }
        val outW = maxX - minX + 1
        val outH = maxY - minY + 1
        val payloadSize = outW * outH * 3
        val out = ByteArrayOutputStream(16 + payloadSize)
        out.write(byteArrayOf('H'.code.toByte(), 'S'.code.toByte(), 'K'.code.toByte(), '1'.code.toByte()))
        writeU16LE(out, outW)
        writeU16LE(out, outH)
        out.write(1)
        out.write(byteArrayOf(0, 0, 0))
        writeU32LE(out, payloadSize)
        for (y in minY..maxY) for (x in minX..maxX) {
            val c = argb[y * width + x]
            val a = (c ushr 24) and 0xFF
            val r = (c ushr 16) and 0xFF
            val g = (c ushr 8) and 0xFF
            val b = c and 0xFF
            val rgb565 = ((r shr 3) shl 11) or ((g shr 2) shl 5) or (b shr 3)
            out.write(rgb565 and 0xFF)
            out.write((rgb565 ushr 8) and 0xFF)
            out.write(a)
        }
        return EncodedRaster(outW, outH, out.toByteArray())
    }

    private fun writeU16LE(out: ByteArrayOutputStream, value: Int) {
        out.write(value and 0xFF); out.write((value ushr 8) and 0xFF)
    }
    private fun writeU32LE(out: ByteArrayOutputStream, value: Int) {
        out.write(value and 0xFF); out.write((value ushr 8) and 0xFF)
        out.write((value ushr 16) and 0xFF); out.write((value ushr 24) and 0xFF)
    }
}

object Hashing {
    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }
}

object SyncPlanner {
    fun changedSlots(localHashes: Map<String, String>, remoteHashes: Map<String, String>): List<String> =
        localHashes.keys.sorted().filter { remoteHashes[it] != localHashes[it] }
}

object HevelApi {
    fun wireId(parentId: String, variant: SkinVariant): String =
        if (variant.isBase) parentId else "${parentId}_ALT_${variant.id}"

    fun uploadPath(parentId: String, variant: SkinVariant, slot: SkinSlot, kind: String): String {
        val q = mutableListOf(
            "skin=${enc(wireId(parentId, variant))}",
            "parent=${enc(parentId)}",
            "variant=${if (variant.isBase) "BASE" else "ALT"}"
        )
        if (!variant.isBase) q += "alt=${enc(variant.id)}"
        q += "slot=${enc(slot.wireName)}"
        q += "kind=${enc(kind)}"
        return "/api/skins/upload?" + q.joinToString("&")
    }

    fun manifestPath(parentId: String, variant: SkinVariant): String {
        val q = mutableListOf(
            "skin=${enc(wireId(parentId, variant))}",
            "parent=${enc(parentId)}",
            "variant=${if (variant.isBase) "BASE" else "ALT"}"
        )
        if (!variant.isBase) q += "alt=${enc(variant.id)}"
        return "/api/skins/manifest?" + q.joinToString("&")
    }

    private fun enc(v: String): String = URLEncoder.encode(v, StandardCharsets.UTF_8.name())
}
