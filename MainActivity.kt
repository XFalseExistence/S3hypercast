package hevel.skin

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.DragEvent
import android.view.View
import android.widget.*
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private lateinit var repo: SkinRepository
    private lateinit var host: EditText
    private lateinit var listBox: LinearLayout
    private val selectedParents = mutableSetOf<String>()
    private val selectedVariants = mutableSetOf<String>()
    private val expandedParents = mutableSetOf<String>()
    private val io = Executors.newSingleThreadExecutor()
    private var hotspotCallback: ConnectivityManager.NetworkCallback? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repo = SkinRepository(this)
        buildUi()
    }

    override fun onResume() {
        super.onResume()
        if (::listBox.isInitialized) renderLibrary()
    }

    override fun onDestroy() {
        super.onDestroy()
        io.shutdownNow()
        releaseHotspotBinding()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
            setBackgroundColor(Color.rgb(7, 12, 16))
        }
        root.addView(TextView(this).apply {
            text = "HEVEL // SKN + ALT LOADER  S3_v0001.9"
            textSize = 22f
            setTextColor(Color.CYAN)
        })
        root.addView(TextView(this).apply {
            text = "SKN = parent skin   •   ALT = nested version"
            setTextColor(Color.LTGRAY)
        })
        host = EditText(this).apply {
            setText(getPreferences(MODE_PRIVATE).getString("host", "192.168.4.1"))
            hint = "S3 host / IP"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
        }
        root.addView(host)
        root.addView(row(
            button("HOTSPOT") { connectHotspot() },
            button("SHARED WIFI") { chooseSharedWifiIp() },
            button("S3 WIFI") { configureWifi() }
        ))
        root.addView(row(
            button("CREATE SKN") { createParent() },
            button("LOAD SELECTED") { loadSelected() },
            button("LOAD ALL SKINS") { loadAll() }
        ))
        root.addView(row(
            button("REFRESH") { renderLibrary() },
            button("RESET DEFAULT") { network("default", { api().activateDefault() }) },
            button("EXIT LOADER") { network("exit loader", { api().exitLoader() }) }
        ))
        listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(this).apply { addView(listBox) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        renderLibrary()
    }

    private fun renderLibrary() {
        getPreferences(MODE_PRIVATE).edit().putString("host", host.text.toString().trim()).apply()
        listBox.removeAllViews()
        val parents = repo.all().sortedBy { it.order }
        if (parents.isEmpty()) {
            listBox.addView(TextView(this).apply { text = "No SKNs yet. Tap CREATE SKN."; setTextColor(Color.GRAY); setPadding(0, 24, 0, 0) })
            return
        }
        parents.forEach { parent -> listBox.addView(renderParentCard(parent)) }
    }

    private fun renderParentCard(parent: SkinParent): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(10, 10, 10, 10)
            setBackgroundColor(Color.rgb(18, 27, 34))
            tag = parent.id
        }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val check = CheckBox(this).apply {
            text = "${parent.name}  •  ${parent.base.slotUris.size}/6 Base  •  ${parent.alts.size} ALT"
            isChecked = selectedParents.contains(parent.id)
            setTextColor(Color.WHITE)
            setOnCheckedChangeListener { _, checked ->
                if (checked) selectedParents += parent.id else selectedParents -= parent.id
            }
        }
        head.addView(check, LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(button(if (expandedParents.contains(parent.id)) "▴" else "▾") {
            if (!expandedParents.add(parent.id)) expandedParents.remove(parent.id)
            renderLibrary()
        })
        box.addView(head)
        box.addView(previews(parent.base))
        box.addView(row(
            button("EDIT BASE") { openEditor(parent, parent.base) },
            button("LOAD SKN") { loadParent(parent) },
            button("ACTIVATE BASE") { activate(parent, parent.base) }
        ))
        box.addView(row(
            button("+ ADD ALT") { addAlt(parent) },
            button("↑") { repo.reorderParent(parent.id, -1); renderLibrary() },
            button("↓") { repo.reorderParent(parent.id, 1); renderLibrary() },
            button("MORE") { parentMenu(parent) }
        ))

        if (expandedParents.contains(parent.id)) {
            parent.alts.sortedBy { it.order }.forEach { alt -> box.addView(renderAltCard(parent, alt)) }
        }

        box.setOnLongClickListener {
            if (Build.VERSION.SDK_INT >= 24) {
                box.startDragAndDrop(ClipData.newPlainText("parent", parent.id), View.DragShadowBuilder(box), parent.id, 0)
            }
            true
        }
        box.setOnDragListener { _, event ->
            if (event.action == DragEvent.ACTION_DROP) {
                (event.localState as? String)?.let { repo.reorderParentBefore(it, parent.id) }
                renderLibrary()
            }
            true
        }
        return box
    }

    private fun renderAltCard(parent: SkinParent, alt: SkinVariant): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 8, 8, 8)
            setBackgroundColor(Color.rgb(11, 20, 27))
        }
        val key = variantKey(parent.id, alt.id)
        box.addView(CheckBox(this).apply {
            text = "ALT // ${alt.name}  •  ${alt.slotUris.size}/6"
            isChecked = selectedVariants.contains(key)
            setTextColor(Color.rgb(130, 255, 170))
            setOnCheckedChangeListener { _, checked -> if (checked) selectedVariants += key else selectedVariants -= key }
        })
        box.addView(previews(alt))
        box.addView(row(
            button("EDIT") { openEditor(parent, alt) },
            button("LOAD ALT") { loadVariant(parent, alt) },
            button("ACTIVATE") { activate(parent, alt) }
        ))
        box.addView(row(
            button("↑") { repo.reorderAlt(parent.id, alt.id, -1); renderLibrary() },
            button("↓") { repo.reorderAlt(parent.id, alt.id, 1); renderLibrary() },
            button("MORE") { altMenu(parent, alt) }
        ))
        return box
    }

    private fun previews(variant: SkinVariant): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        SkinSlot.entries.forEach { slot ->
            addView(ImageView(this@MainActivity).apply {
                layoutParams = LinearLayout.LayoutParams(0, 54, 1f)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                setBackgroundColor(Color.rgb(9, 15, 19))
                variant.slotUris[slot]?.let { runCatching { setImageURI(android.net.Uri.parse(it)) } }
                contentDescription = slot.wireName
            })
        }
    }

    private fun createParent() {
        val input = EditText(this).apply { hint = "SKN name" }
        AlertDialog.Builder(this)
            .setTitle("CREATE SKN")
            .setView(input)
            .setPositiveButton("CREATE") { _, _ ->
                val p = repo.createParent(input.text.toString())
                expandedParents += p.id
                renderLibrary()
            }
            .setNegativeButton("CANCEL", null)
            .show()
    }

    private fun addAlt(parent: SkinParent) {
        val input = EditText(this).apply { hint = "ALT name, e.g. Laser" }
        AlertDialog.Builder(this)
            .setTitle("ADD ALT // ${parent.name}")
            .setView(input)
            .setPositiveButton("ADD") { _, _ -> repo.createAlt(parent.id, input.text.toString()); expandedParents += parent.id; renderLibrary() }
            .setNegativeButton("CANCEL", null)
            .show()
    }

    private fun parentMenu(parent: SkinParent) {
        AlertDialog.Builder(this).setTitle(parent.name)
            .setItems(arrayOf("RENAME", "DUPLICATE SKN", "DELETE", "DELETE S3 + LOCAL")) { _, which ->
                when (which) {
                    0 -> renameParent(parent)
                    1 -> { repo.duplicateParent(parent.id); renderLibrary() }
                    2 -> { repo.deleteParent(parent.id); selectedParents.remove(parent.id); renderLibrary() }
                    3 -> network("delete ${parent.name}", { api().deleteParent(parent) }) {
                        repo.deleteParent(parent.id); selectedParents.remove(parent.id); renderLibrary()
                    }
                }
            }.show()
    }

    private fun altMenu(parent: SkinParent, alt: SkinVariant) {
        AlertDialog.Builder(this).setTitle("${parent.name} / ${alt.name}")
            .setItems(arrayOf("RENAME", "DUPLICATE ALT", "MOVE ALT", "DELETE", "DELETE S3 + LOCAL")) { _, which ->
                when (which) {
                    0 -> renameAlt(parent, alt)
                    1 -> { repo.duplicateAlt(parent.id, alt.id); renderLibrary() }
                    2 -> moveAlt(parent, alt)
                    3 -> { repo.deleteAlt(parent.id, alt.id); selectedVariants.remove(variantKey(parent.id, alt.id)); renderLibrary() }
                    4 -> network("delete ALT ${alt.name}", { api().deleteVariant(parent.id, alt) }) {
                        repo.deleteAlt(parent.id, alt.id); selectedVariants.remove(variantKey(parent.id, alt.id)); renderLibrary()
                    }
                }
            }.show()
    }

    private fun renameParent(parent: SkinParent) {
        val input = EditText(this).apply { setText(parent.name) }
        AlertDialog.Builder(this).setTitle("RENAME SKN").setView(input)
            .setPositiveButton("SAVE") { _, _ -> repo.renameParent(parent.id, input.text.toString()); renderLibrary() }
            .setNegativeButton("CANCEL", null).show()
    }

    private fun renameAlt(parent: SkinParent, alt: SkinVariant) {
        val input = EditText(this).apply { setText(alt.name) }
        AlertDialog.Builder(this).setTitle("RENAME ALT").setView(input)
            .setPositiveButton("SAVE") { _, _ -> repo.renameAlt(parent.id, alt.id, input.text.toString()); renderLibrary() }
            .setNegativeButton("CANCEL", null).show()
    }

    private fun moveAlt(parent: SkinParent, alt: SkinVariant) {
        val targets = repo.all().filter { it.id != parent.id }.sortedBy { it.order }
        if (targets.isEmpty()) { toast("Create another SKN first"); return }
        AlertDialog.Builder(this).setTitle("MOVE ALT // ${alt.name}")
            .setItems(targets.map { it.name }.toTypedArray()) { _, which ->
                repo.moveAlt(alt.id, parent.id, targets[which].id)
                selectedVariants.remove(variantKey(parent.id, alt.id))
                expandedParents += targets[which].id
                renderLibrary()
            }.show()
    }

    private fun openEditor(parent: SkinParent, variant: SkinVariant) {
        startActivity(Intent(this, SkinEditorActivity::class.java)
            .putExtra("parent_id", parent.id)
            .putExtra("variant_id", variant.id)
            .putExtra("host", host.text.toString()))
    }

    private fun loadSelected() {
        val parents = repo.all()
        val chosenParents = parents.filter { selectedParents.contains(it.id) }
        val chosenVariants = mutableListOf<Pair<SkinParent, SkinVariant>>()
        parents.filterNot { selectedParents.contains(it.id) }.forEach { parent ->
            parent.alts.forEach { alt ->
                if (selectedVariants.contains(variantKey(parent.id, alt.id))) chosenVariants += parent to alt
            }
        }
        if (chosenParents.isEmpty() && chosenVariants.isEmpty()) { toast("Select a SKN or ALT"); return }
        io.execute {
            try {
                chosenParents.forEach { loadParentBlocking(it) }
                chosenVariants.forEach { (p, v) -> loadVariantBlocking(p, v) }
                check(api().postIndex(repo.indexJson()).ok)
                runOnUiThread { toast("Loaded selected") }
            } catch (t: Throwable) { runOnUiThread { toast("LOAD failed: ${t.message}") } }
        }
    }

    private fun loadAll() {
        val parents = repo.all()
        io.execute {
            try {
                parents.forEach { loadParentBlocking(it) }
                check(api().postIndex(repo.indexJson()).ok)
                runOnUiThread { toast("Loaded ${parents.size} SKN(s)") }
            } catch (t: Throwable) { runOnUiThread { toast("LOAD ALL failed: ${t.message}") } }
        }
    }

    private fun loadParent(parent: SkinParent) {
        io.execute {
            try {
                loadParentBlocking(parent)
                check(api().postIndex(repo.indexJson()).ok)
                runOnUiThread { toast("Loaded SKN ${parent.name}") }
            } catch (t: Throwable) { runOnUiThread { toast("LOAD SKN failed: ${t.message}") } }
        }
    }

    private fun loadVariant(parent: SkinParent, variant: SkinVariant) {
        io.execute {
            try {
                loadVariantBlocking(parent, variant)
                check(api().postIndex(repo.indexJson()).ok)
                runOnUiThread { toast("Loaded ALT ${variant.name}") }
            } catch (t: Throwable) { runOnUiThread { toast("LOAD ALT failed: ${t.message}") } }
        }
    }

    private fun loadParentBlocking(parent: SkinParent) {
        loadVariantBlocking(parent, parent.base)
        parent.alts.sortedBy { it.order }.forEach { loadVariantBlocking(parent, it) }
    }

    private fun loadVariantBlocking(parent: SkinParent, variant: SkinVariant) {
        val a = api()
        val remote = a.manifest(parent.id, variant)
        val old = if (remote.ok) SkinTransfer.remoteHashes(remote.body) else emptyMap()
        val hashes = mutableMapOf<String, Pair<String, String>>()
        old.keys.mapNotNull { SkinSlot.fromWireName(it) }
            .filter { !variant.slotUris.containsKey(it) }
            .forEach { check(a.clearSlot(parent.id, variant, it).ok) }
        variant.slotUris.forEach { (slot, uri) ->
            val payload = SkinTransfer.buildPayload(contentResolver, uri)
            val prev = old[slot.wireName]
            if (prev?.first != payload.sourceSha) check(a.upload(parent.id, variant, slot, "source", "${slot.wireName}.png", "image/png", payload.source).ok)
            if (prev?.second != payload.renderSha) check(a.upload(parent.id, variant, slot, "render", "${slot.wireName}.hvs", "application/octet-stream", payload.render).ok)
            hashes[slot.wireName] = payload.sourceSha to payload.renderSha
        }
        check(a.postManifest(parent.id, variant, SkinTransfer.manifest(parent, variant, hashes)).ok)
    }

    private fun activate(parent: SkinParent, variant: SkinVariant) {
        io.execute {
            val response = runCatching { api().activate(parent.id, variant) }.getOrNull()
            runOnUiThread {
                if (response?.ok == true) {
                    if (variant.isBase) repo.setRememberedAlt(parent.id, null) else repo.setRememberedAlt(parent.id, variant.id)
                    toast("Activated ${parent.name} / ${variant.name}")
                } else {
                    AlertDialog.Builder(this).setTitle("LOAD & ACTIVATE")
                        .setMessage("${parent.name} / ${variant.name} is not installed yet.")
                        .setPositiveButton("LOAD & ACTIVATE") { _, _ ->
                            io.execute {
                                try {
                                    loadVariantBlocking(parent, variant)
                                    check(api().postIndex(repo.indexJson()).ok)
                                    check(api().activate(parent.id, variant).ok)
                                    if (variant.isBase) repo.setRememberedAlt(parent.id, null) else repo.setRememberedAlt(parent.id, variant.id)
                                    runOnUiThread { toast("Activated ${parent.name} / ${variant.name}") }
                                } catch (t: Throwable) { runOnUiThread { toast(t.message ?: "failed") } }
                            }
                        }.setNegativeButton("CANCEL", null).show()
                }
            }
        }
    }

    private fun connectHotspot() {
        host.setText("192.168.4.1")
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES), 204); return
        }
        if (Build.VERSION.SDK_INT in 29..32 && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 204); return
        }
        if (Build.VERSION.SDK_INT < 29) { startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)); return }
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        hotspotCallback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        val spec = WifiNetworkSpecifier.Builder().setSsid("HEVEL_SKIN_LOADER").setWpa2Passphrase("hevelskins").build()
        val request = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).setNetworkSpecifier(spec).build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { cm.bindProcessToNetwork(network); runOnUiThread { toast("HOTSPOT connected"); checkConnection() } }
            override fun onUnavailable() { runOnUiThread { toast("Hotspot connection cancelled") } }
        }
        hotspotCallback = cb
        cm.requestNetwork(request, cb)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 204 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) connectHotspot()
    }

    private fun releaseHotspotBinding() {
        if (!::repo.isInitialized) return
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        cm.bindProcessToNetwork(null)
        hotspotCallback?.let { cb -> runCatching { cm.unregisterNetworkCallback(cb) } }
        hotspotCallback = null
    }

    private fun chooseSharedWifiIp() {
        releaseHotspotBinding()
        val prefs = getPreferences(MODE_PRIVATE)
        val remembered = prefs.getString("shared_ip", "") ?: ""
        val current = host.text.toString().trim()
        val initial = when {
            remembered.isNotBlank() -> remembered
            current.isNotBlank() && current != "192.168.4.1" -> current
            else -> ""
        }
        val input = EditText(this).apply {
            hint = "S3 IP, e.g. 192.168.1.42"
            setText(initial)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
        }
        AlertDialog.Builder(this)
            .setTitle("SHARED WIFI IP")
            .setMessage("Enter the IP printed by the S3 after it joins your shared Wi-Fi network.")
            .setView(input)
            .setPositiveButton("CONNECT") { _, _ ->
                val ip = input.text.toString().trim()
                if (ip.isBlank()) toast("Enter the S3 IP") else {
                    prefs.edit().putString("shared_ip", ip).apply(); host.setText(ip); checkConnection()
                }
            }
            .setNegativeButton("CANCEL", null).show()
    }

    private fun checkConnection() = network("connection", { api().status() })

    private fun configureWifi() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val ssid = EditText(this).apply { hint = "Wi-Fi SSID" }
        val pass = EditText(this).apply { hint = "Wi-Fi password" }
        box.addView(ssid); box.addView(pass)
        AlertDialog.Builder(this).setTitle("SHARED WIFI")
            .setMessage("While connected to HEVEL_SKIN_LOADER, send the S3 your shared Wi-Fi credentials.")
            .setView(box)
            .setPositiveButton("SEND") { _, _ -> network("wifi config", { api().configureSharedWifi(ssid.text.toString(), pass.text.toString()) }) }
            .setNegativeButton("CANCEL", null).show()
    }

    private fun api() = HevelApiClient(host.text.toString().trim().ifBlank { "192.168.4.1" })

    private fun network(label: String, call: () -> HevelApiClient.Response, onOk: (() -> Unit)? = null) {
        io.execute {
            try {
                val r = call()
                runOnUiThread { if (r.ok) { toast("$label OK"); onOk?.invoke() } else toast("$label HTTP ${r.code}") }
            } catch (t: Throwable) { runOnUiThread { toast("$label: ${t.message}") } }
        }
    }

    private fun variantKey(parentId: String, variantId: String) = "$parentId:$variantId"
    private fun button(label: String, action: () -> Unit) = Button(this).apply { text = label; setOnClickListener { action() } }
    private fun row(vararg views: View) = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; views.forEach { addView(it, LinearLayout.LayoutParams(0, -2, 1f)) } }
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}
