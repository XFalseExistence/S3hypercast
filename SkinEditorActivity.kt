package hevel.skin

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.*
import java.util.concurrent.Executors

class SkinEditorActivity : Activity() {
    private lateinit var repo: SkinRepository
    private lateinit var parentId: String
    private lateinit var variantId: String
    private lateinit var root: LinearLayout
    private var pending: SkinSlot? = null
    private val io = Executors.newSingleThreadExecutor()
    private lateinit var host: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repo = SkinRepository(this)
        parentId = intent.getStringExtra("parent_id") ?: run { finish(); return }
        variantId = intent.getStringExtra("variant_id") ?: run { finish(); return }
        host = intent.getStringExtra("host") ?: "192.168.4.1"
        render()
    }

    override fun onDestroy() { super.onDestroy(); io.shutdownNow() }

    private fun current(): Pair<SkinParent, SkinVariant>? {
        val parent = repo.get(parentId) ?: return null
        val variant = parent.variant(variantId) ?: return null
        return parent to variant
    }

    private fun render() {
        val (parent, variant) = current() ?: run { finish(); return }
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(16, 16, 16, 16) }
        root.addView(TextView(this).apply {
            text = if (variant.isBase) "${parent.name} / Base" else "${parent.name} / ALT / ${variant.name}"
            textSize = 22f
        })
        SkinSlot.entries.forEach { slot ->
            val uri = variant.slotUris[slot]
            root.addView(TextView(this).apply { text = "${slot.wireName}  ${if (uri == null) "EMPTY" else "READY"}" })
            root.addView(ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(-1, 180)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                uri?.let { runCatching { setImageURI(Uri.parse(it)) } }
            })
            root.addView(row(
                button(if (uri == null) "PICK PNG" else "REPLACE") { pick(slot) },
                button("CLEAR") {
                    val now = current() ?: return@button
                    repo.updateVariant(parentId, now.second.withSlot(slot, null)); render()
                },
                button("LOAD POV") { loadPov(slot) }
            ))
        }
        root.addView(button("DONE") { finish() })
        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun pick(slot: SkinSlot) {
        pending = slot
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/png"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }, 44)
    }

    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (req != 44 || res != RESULT_OK) return
        val uri = data?.data ?: return
        runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        val slot = pending ?: return
        val now = current() ?: return
        repo.updateVariant(parentId, now.second.withSlot(slot, uri.toString()))
        render()
    }

    private fun loadPov(slot: SkinSlot) {
        val (parent, variant) = current() ?: return
        val uri = variant.slotUris[slot] ?: run { toast("Pick a PNG first"); return }
        io.execute {
            try {
                val payload = SkinTransfer.buildPayload(contentResolver, uri)
                val api = HevelApiClient(host)
                check(api.upload(parent.id, variant, slot, "source", slot.wireName + ".png", "image/png", payload.source).ok)
                check(api.upload(parent.id, variant, slot, "render", slot.wireName + ".hvs", "application/octet-stream", payload.render).ok)
                val old = api.manifest(parent.id, variant)
                val hashes = if (old.ok) SkinTransfer.remoteHashes(old.body).toMutableMap() else mutableMapOf()
                hashes[slot.wireName] = payload.sourceSha to payload.renderSha
                check(api.postManifest(parent.id, variant, SkinTransfer.manifest(parent, variant, hashes)).ok)
                check(api.postIndex(repo.indexJson()).ok)
                runOnUiThread { toast("${slot.wireName} loaded") }
            } catch (t: Throwable) { runOnUiThread { toast("LOAD POV failed: ${t.message}") } }
        }
    }

    private fun button(text: String, action: () -> Unit) = Button(this).apply { this.text = text; setOnClickListener { action() } }
    private fun row(vararg views: View) = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; views.forEach { addView(it, LinearLayout.LayoutParams(0, -2, 1f)) } }
    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
}
