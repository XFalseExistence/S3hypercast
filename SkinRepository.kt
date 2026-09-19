package hevel.skin

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class SkinRepository(context: Context) {
    private val prefs = context.getSharedPreferences("hevel_skin_library", Context.MODE_PRIVATE)

    fun all(): MutableList<SkinParent> {
        val raw = prefs.getString("library", null)
        val root = if (raw.isNullOrBlank()) migrateLegacyRoot() else runCatching { JSONObject(raw) }.getOrElse { migrateLegacyRoot() }
        val schema = root.optInt("schema", 1)
        val normalizedRoot = if (schema < 2) migrateSchema1(root) else root
        val parents = parseParents(normalizedRoot.optJSONArray("parents") ?: JSONArray())
        val normalized = normalize(parents)
        if (raw.isNullOrBlank() || schema < 2) save(normalized)
        return normalized
    }

    fun get(parentId: String): SkinParent? = all().firstOrNull { it.id == parentId }

    fun getVariant(parentId: String, variantId: String): SkinVariant? = get(parentId)?.variant(variantId)

    fun save(parents: List<SkinParent>) {
        val arr = JSONArray()
        normalize(parents.toMutableList()).forEach { parent -> arr.put(parentJson(parent)) }
        val root = JSONObject().put("schema", 2).put("parents", arr)
        prefs.edit().putString("library", root.toString()).apply()
    }

    fun createParent(name: String): SkinParent {
        val list = all()
        val parent = SkinParent.new(name, list.size)
        list += parent
        save(list)
        return parent
    }

    fun createAlt(parentId: String, name: String): SkinVariant? {
        val list = all()
        val i = list.indexOfFirst { it.id == parentId }
        if (i < 0) return null
        val parent = list[i]
        val alt = SkinVariant.newAlt(name, parent.alts.size)
        list[i] = parent.copy(alts = parent.alts + alt)
        save(list)
        return alt
    }

    fun updateParent(parent: SkinParent) {
        val list = all()
        val i = list.indexOfFirst { it.id == parent.id }
        if (i >= 0) list[i] = parent else list += parent
        save(list)
    }

    fun updateVariant(parentId: String, variant: SkinVariant) {
        val list = all()
        val i = list.indexOfFirst { it.id == parentId }
        if (i < 0) return
        val parent = list[i]
        list[i] = if (variant.isBase || variant.id == parent.base.id) {
            parent.copy(base = variant.copy(isBase = true, order = 0))
        } else {
            val alts = parent.alts.toMutableList()
            val ai = alts.indexOfFirst { it.id == variant.id }
            if (ai >= 0) alts[ai] = variant.copy(isBase = false) else alts += variant.copy(isBase = false)
            parent.copy(alts = alts)
        }
        save(list)
    }

    fun renameParent(parentId: String, name: String) { get(parentId)?.let { updateParent(it.rename(name)) } }

    fun renameAlt(parentId: String, altId: String, name: String) {
        val alt = getVariant(parentId, altId) ?: return
        if (!alt.isBase) updateVariant(parentId, alt.rename(name))
    }

    fun duplicateParent(parentId: String): SkinParent? {
        val source = get(parentId) ?: return null
        val clone = SkinParent.new(source.name + " Copy", all().size).copy(
            base = source.base.copy(id = freshBaseId(), slotUris = source.base.slotUris),
            alts = source.alts.mapIndexed { index, alt ->
                SkinVariant.newAlt(alt.name, index).copy(slotUris = alt.slotUris)
            },
            rememberedAltId = null
        )
        val list = all(); list += clone; save(list)
        return clone
    }

    fun duplicateAlt(parentId: String, altId: String): SkinVariant? {
        val parent = get(parentId) ?: return null
        val source = parent.alts.firstOrNull { it.id == altId } ?: return null
        val clone = SkinVariant.newAlt(source.name + " Copy", parent.alts.size).copy(slotUris = source.slotUris)
        updateParent(parent.copy(alts = parent.alts + clone))
        return clone
    }

    fun moveAlt(altId: String, fromParentId: String, toParentId: String) {
        if (fromParentId == toParentId) return
        val list = all()
        val fromIndex = list.indexOfFirst { it.id == fromParentId }
        val toIndex = list.indexOfFirst { it.id == toParentId }
        if (fromIndex < 0 || toIndex < 0) return
        val source = list[fromIndex]
        val alt = source.alts.firstOrNull { it.id == altId } ?: return
        val fromAlts = source.alts.filterNot { it.id == altId }.mapIndexed { i, v -> v.copy(order = i) }
        list[fromIndex] = source.copy(
            alts = fromAlts,
            rememberedAltId = source.rememberedAltId.takeUnless { it == altId }
        )
        val target = list[toIndex]
        list[toIndex] = target.copy(alts = target.alts + alt.copy(order = target.alts.size))
        save(list)
    }

    fun deleteParent(parentId: String) { save(all().filterNot { it.id == parentId }) }

    fun deleteAlt(parentId: String, altId: String) {
        val parent = get(parentId) ?: return
        val alts = parent.alts.filterNot { it.id == altId }.mapIndexed { i, v -> v.copy(order = i) }
        updateParent(parent.copy(alts = alts, rememberedAltId = parent.rememberedAltId.takeUnless { it == altId }))
    }

    fun reorderParent(id: String, delta: Int) {
        val list = all().sortedBy { it.order }.toMutableList()
        val i = list.indexOfFirst { it.id == id }
        if (i < 0) return
        val j = (i + delta).coerceIn(0, list.lastIndex)
        if (i == j) return
        val item = list.removeAt(i)
        list.add(j, item)
        save(list.mapIndexed { n, p -> p.copy(order = n) })
    }

    fun reorderParentBefore(id: String, targetId: String) {
        val list = all().sortedBy { it.order }.toMutableList()
        val from = list.indexOfFirst { it.id == id }
        if (from < 0) return
        val item = list.removeAt(from)
        val to = list.indexOfFirst { it.id == targetId }
        if (to < 0) return
        list.add(to, item)
        save(list.mapIndexed { n, p -> p.copy(order = n) })
    }

    fun reorderAlt(parentId: String, altId: String, delta: Int) {
        val parent = get(parentId) ?: return
        val alts = parent.alts.sortedBy { it.order }.toMutableList()
        val i = alts.indexOfFirst { it.id == altId }
        if (i < 0) return
        val j = (i + delta).coerceIn(0, alts.lastIndex)
        if (i == j) return
        val item = alts.removeAt(i)
        alts.add(j, item)
        updateParent(parent.copy(alts = alts.mapIndexed { n, v -> v.copy(order = n) }))
    }

    fun setRememberedAlt(parentId: String, altId: String?) {
        val parent = get(parentId) ?: return
        val valid = altId?.takeIf { id -> parent.alts.any { it.id == id } }
        updateParent(parent.copy(rememberedAltId = valid))
    }

    fun indexJson(): String {
        val parents = JSONArray()
        all().sortedBy { it.order }.forEach { parent ->
            val alts = JSONArray()
            parent.alts.sortedBy { it.order }.forEach { alt ->
                alts.put(JSONObject().put("id", alt.id).put("name", alt.name).put("order", alt.order))
            }
            parents.put(
                JSONObject()
                    .put("id", parent.id)
                    .put("name", parent.name)
                    .put("order", parent.order)
                    .put("baseId", parent.base.id)
                    .put("rememberedAltId", parent.rememberedAltId ?: JSONObject.NULL)
                    .put("alts", alts)
            )
        }
        val lzr = JSONArray()
        val skn = JSONArray()
        all().sortedBy { it.order }.forEach { parent ->
            skn.put(parent.id)
            parent.alts.sortedBy { it.order }.forEach { alt ->
                lzr.put(HevelApi.wireId(parent.id, alt))
            }
        }
        return JSONObject()
            .put("schema", 1)
            .put("lzr", lzr)
            .put("skn", skn)
            .put("hierarchySchema", 2)
            .put("parents", parents)
            .toString()
    }

    private fun migrateLegacyRoot(): JSONObject {
        val oldPacks = JSONArray(prefs.getString("packs", "[]"))
        val parents = JSONArray()
        for (i in 0 until oldPacks.length()) {
            val old = oldPacks.getJSONObject(i)
            val id = old.optString("id").ifBlank { "P_MIGRATED_$i" }
            val slots = old.optJSONObject("slots") ?: JSONObject()
            val base = JSONObject()
                .put("id", "BASE_$id")
                .put("name", "Base")
                .put("order", 0)
                .put("isBase", true)
                .put("slots", slots)
            parents.put(
                JSONObject()
                    .put("id", id)
                    .put("name", old.optString("name", "Migrated Skin"))
                    .put("order", i)
                    .put("legacyBank", old.optString("bank", "SKN"))
                    .put("base", base)
                    .put("alts", JSONArray())
            )
        }
        return JSONObject().put("schema", 2).put("parents", parents)
    }

    private fun migrateSchema1(root: JSONObject): JSONObject {
        val parents = root.optJSONArray("parents")
        if (parents != null) return JSONObject().put("schema", 2).put("parents", parents)
        return migrateLegacyRoot()
    }

    private fun parseParents(arr: JSONArray): MutableList<SkinParent> {
        val out = mutableListOf<SkinParent>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val baseObject = o.optJSONObject("base") ?: JSONObject()
            val parentId = o.optString("id").ifBlank { "P_RECOVERED_$i" }
            val base = parseVariant(baseObject, true, "BASE_$parentId")
            val alts = mutableListOf<SkinVariant>()
            val aa = o.optJSONArray("alts") ?: JSONArray()
            for (j in 0 until aa.length()) aa.optJSONObject(j)?.let { alts += parseVariant(it, false, "ALT_${parentId}_$j") }
            out += SkinParent(
                parentId,
                o.optString("name", "Skin"),
                o.optInt("order", i),
                base,
                alts,
                o.optString("rememberedAltId").takeIf { it.isNotBlank() && it != "null" }
            )
        }
        return out
    }

    private fun parseVariant(o: JSONObject, isBase: Boolean, fallbackId: String): SkinVariant {
        val slots = mutableMapOf<SkinSlot, String>()
        val so = o.optJSONObject("slots") ?: JSONObject()
        SkinSlot.entries.forEach { slot ->
            so.optString(slot.wireName).takeIf { it.isNotBlank() }?.let { slots[slot] = it }
        }
        return SkinVariant(
            o.optString("id").ifBlank { fallbackId },
            if (isBase) "Base" else o.optString("name", "ALT"),
            if (isBase) 0 else o.optInt("order", 0),
            slots,
            isBase
        )
    }

    private fun parentJson(parent: SkinParent): JSONObject {
        val alts = JSONArray()
        parent.alts.sortedBy { it.order }.forEach { alts.put(variantJson(it)) }
        return JSONObject()
            .put("id", parent.id)
            .put("name", parent.name)
            .put("order", parent.order)
            .put("rememberedAltId", parent.rememberedAltId ?: JSONObject.NULL)
            .put("base", variantJson(parent.base.copy(isBase = true, order = 0)))
            .put("alts", alts)
    }

    private fun variantJson(variant: SkinVariant): JSONObject {
        val slots = JSONObject()
        variant.slotUris.forEach { (slot, uri) -> slots.put(slot.wireName, uri) }
        return JSONObject()
            .put("id", variant.id)
            .put("name", variant.name)
            .put("order", variant.order)
            .put("isBase", variant.isBase)
            .put("slots", slots)
    }

    private fun normalize(list: MutableList<SkinParent>): MutableList<SkinParent> =
        list.sortedBy { it.order }.mapIndexed { index, parent ->
            parent.copy(
                order = index,
                base = parent.base.copy(name = "Base", order = 0, isBase = true),
                alts = parent.alts.sortedBy { it.order }.mapIndexed { ai, alt -> alt.copy(order = ai, isBase = false) }
            )
        }.toMutableList()

    private fun freshBaseId(): String = SkinVariant.newBase().id
}
