package com.dklab.nes

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.hardware.input.InputManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.view.InputDevice
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.concurrent.thread

/**
 * The hub's main menu: assets/hub/menu.html (built from hub/menu.html by
 * tools/hub_assets.py) in a WebView. The page lists the cartridges and calls
 * NesHub.launch(id) when one has gone into the console; the game runs in
 * [MainActivity], and coming back calls window.nesHubReturn() so the page shows the
 * shelf again (and re-reads NesHub.stats() for "continue", play time and records).
 *
 * Also here: favourites, games added from the phone (files/custom/), connected pads,
 * and a backup of all saves to one .zip (and back).
 */
class HubActivity : Activity() {

    private lateinit var web: WebView
    private var inGame = false
    private val ui = android.os.Handler(android.os.Looper.getMainLooper())

    /** While connected: when player 1 starts a game, player 2's hub starts it too. */
    private val netWatch: Runnable = object : Runnable {
        override fun run() {
            val s = Net.session ?: return
            if (s.phase == NetSession.Phase.ENDED) return
            ui.postDelayed(this, 200)
            val st = s.start
            if (s.phase == NetSession.Phase.PLAYING && !s.isHost && s.launched && st != null && !inGame) {
                val have = if (st.gameId.startsWith(MainActivity.CUSTOM_PREFIX)) File(customDir, st.gameId + ".nes").isFile
                    else runCatching { assets.open("games/${st.gameId}.nes").close(); true }.getOrDefault(false)
                if (!have) {
                    if (s.epoch != refusedEpoch) {
                        refusedEpoch = s.epoch
                        js("window.nesHubToast && window.nesHubToast(${JSONObject.quote("Nie masz gry „${st.title}” — gracz 1 musi wybrać inną")})")
                    }
                    return
                }
                // the hub plays the cartridge going in first, then calls netGuestGo()
                if (s.epoch != shownEpoch) {
                    shownEpoch = s.epoch
                    js("window.nesHubNetLaunch ? window.nesHubNetLaunch(${JSONObject.quote(st.gameId)}) : NesHub.netGuestGo()")
                }
            }
        }
    }
    private var shownEpoch = -1

    /** Player 2's game, once the hub's insert animation is over. */
    private fun startGuestGame() {
        val s = Net.session ?: return
        val st = s.start ?: return
        if (inGame || !s.launched || s.isHost) return
        inGame = true
        startActivity(Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_GAME, st.gameId)
            .putExtra(MainActivity.EXTRA_TITLE, st.title).putExtra(MainActivity.EXTRA_NET_GUEST, true))
        @Suppress("DEPRECATION") overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }
    private var refusedEpoch = -1
    private val hubPrefs by lazy { getSharedPreferences("hub", MODE_PRIVATE) }
    private val customDir by lazy { File(filesDir, "custom").apply { mkdirs() } }
    /** Recipes for a cartridge's pictures, by game module (ArtGen.kt). */
    private val artRecipes by lazy { runCatching { JSONObject(assets.open("hub/art.json").use { it.reader().readText() }) }.getOrDefault(JSONObject()) }
    /** The emulator core is one per process: no game starts while art is being made. */
    private val artLock = Any()
    @Volatile private var artBusy = false
    private var backfilled = false

    private val padListener = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(id: Int) = pushPads()
        override fun onInputDeviceRemoved(id: Int) = pushPads()
        override fun onInputDeviceChanged(id: Int) = pushPads()
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this).apply {
            setBackgroundColor(Color.BLACK)
            settings.javaScriptEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.allowFileAccess = false          // assets are served without it
            // The page is dark by design; no forced/algorithmic darkening on top.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) settings.isAlgorithmicDarkeningAllowed = false
            addJavascriptInterface(Bridge(), "NesHub")
            loadUrl("file:///android_asset/hub/menu.html")
        }
        setContentView(web)
        (getSystemService(Context.INPUT_SERVICE) as InputManager).registerInputDeviceListener(padListener, null)
    }

    private fun js(code: String) = runOnUiThread { web.evaluateJavascript(code, null) }

    // ------------------------------------------------------------------ pads --

    private fun padCount(): Int = InputDevice.getDeviceIds().count { id ->
        val d = InputDevice.getDevice(id) ?: return@count false
        val s = d.sources
        !d.isVirtual && (s and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
            s and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK)
    }

    private fun pushPads() = js("window.nesHubPads && window.nesHubPads(${padCount()})")

    // ----------------------------------------------------------------- stats --

    private fun dataUrl(f: File, mime: String): String? =
        if (f.isFile) "data:$mime;base64," + Base64.encodeToString(f.readBytes(), Base64.NO_WRAP) else null

    private fun statsFor(id: String): JSONObject {
        val p = getSharedPreferences("game_$id", MODE_PRIVATE)
        val dir = File(filesDir, id)
        return JSONObject().apply {
            dataUrl(File(dir, "autosave.jpg"), "image/jpeg")?.takeIf { File(dir, "autosave.sav").isFile }?.let { put("shot", it) }
            put("savedAt", p.getLong(MainActivity.KEY_SAVED_AT, 0))
            put("playMs", p.getLong(MainActivity.KEY_PLAY_MS, 0))
            put("best", p.getInt(MainActivity.KEY_BEST, 0))
            put("lastPlayed", p.getLong(MainActivity.KEY_LAST_PLAYED, 0))
            put("fav", hubPrefs.getStringSet("fav", emptySet())!!.contains(id))
            // modifications the player keeps switched on (persisted, non-zero)
            put("mods", JSONArray(p.all.filter { (k, v) -> k.startsWith("mod_") && v is Int && v != 0 }.keys.map { it.removePrefix("mod_") }))
        }
    }

    // ----------------------------------------------------- games from the phone --

    private fun customList(): JSONArray = JSONArray().apply {
        customDir.listFiles { f -> f.name.endsWith(".json") }?.sortedBy { it.name }?.forEach { f ->
            val rom = File(customDir, f.name.removeSuffix(".json") + ".nes")
            // added before the white list: not shown unless the hub has a module for it
            if (rom.exists() && NativeBridge.nativeDetectGame(rom.readBytes()).isEmpty()) return@forEach
            runCatching { put(JSONObject(f.readText())) }
        }
    }

    private fun addRom(uri: Uri) = thread(name = "hub-add") {
        val result = runCatching {
            val rom = contentResolver.openInputStream(uri)!!.use { it.readBytes() }
            require(rom.size > 16 && rom[0] == 'N'.code.toByte() && rom[1] == 'E'.code.toByte() &&
                rom[2] == 'S'.code.toByte() && rom[3] == 0x1A.toByte()) { "To nie jest plik .nes" }
            // White list: only games the hub has a module for (built-in titles and their
            // other versions, e.g. a translation patch); anything else gets the joke screen.
            val module = NativeBridge.nativeDetectGame(rom)
            if (module.isEmpty()) { js("window.nesHubJoke && window.nesHubJoke()"); return@thread }
            val builtIn = runCatching { assets.open("games/$module.nes").use { it.readBytes() } }.getOrNull()
            require(builtIn == null || !rom.copyOfRange(16, rom.size).contentEquals(builtIn.copyOfRange(16, builtIn.size))) {
                if (Locale.getDefault().language == "pl") "ta gra już jest na półce" else "this game is already on the shelf"
            }
            val crc = CRC32().apply { update(rom, 16, rom.size - 16) }.value
            val id = MainActivity.CUSTOM_PREFIX + "%08x".format(crc)
            File(customDir, "$id.nes").writeBytes(rom)
            val name = (uri.lastPathSegment ?: "Gra").substringAfterLast('/').substringAfterLast(':')
                .substringBeforeLast('.').replace(Regex("\\s*[\\[(].*?[\\])]"), "").trim().ifEmpty { "Gra" }
            val meta = JSONObject().apply {
                put("id", id); put("title", name); put("custom", true)
                put("year", ""); put("by", "z telefonu"); put("module", module)
            }
            js("window.nesHubAdding && window.nesHubAdding(true)")
            addArt(meta, rom)
            File(customDir, "$id.json").writeText(meta.toString())
            meta
        }
        result.onSuccess { js("window.nesHubAdded && window.nesHubAdded(${JSONObject.quote(it.toString())})") }
            .onFailure { Log.e(TAG, "add game", it); js("window.nesHubAdding && window.nesHubAdding(false)"); js("window.nesHubToast && window.nesHubToast(${JSONObject.quote("Nie dodano: " + (it.message ?: "błąd pliku"))})") }
    }

    /** The cartridge's logo, title screen and clip, made from the dump by the module's
     *  recipe; a game without one gets its busiest title frame. */
    private fun addArt(meta: JSONObject, rom: ByteArray) = synchronized(artLock) {
        artBusy = true
        try {
            val art = artRecipes.optJSONObject(meta.optString("module"))
                ?.let { r -> runCatching { ArtGen.make(rom, r) }.onFailure { Log.e(TAG, "art", it) }.getOrNull() }
            if (art != null) { for (k in art.keys()) meta.put(k, art.get(k)); meta.put("art", recipeVersion(meta.optString("module"))) }
            else titleShot(rom)?.let { meta.put("shot", it) }
        } finally { artBusy = false }
    }

    /** Which recipe made a cartridge's art: a changed recipe (a better clip) makes it again. */
    private fun recipeVersion(module: String) = artRecipes.optJSONObject(module)?.toString()?.hashCode() ?: 0

    /** Games added before the hub made cartridge art, or by an older recipe: make it now, in the background. */
    private fun backfillArt() = thread(name = "hub-art") {
        customDir.listFiles { f -> f.name.endsWith(".json") }?.sortedBy { it.name }?.forEach { f ->
            runCatching {
                val meta = JSONObject(f.readText())
                val rom = File(customDir, f.name.removeSuffix(".json") + ".nes").readBytes()
                val module = NativeBridge.nativeDetectGame(rom)
                if (!artRecipes.has(module)) return@runCatching
                if (meta.optInt("art") == recipeVersion(module)) return@runCatching   // made by this recipe
                meta.put("module", module)
                addArt(meta, rom)
                if (!meta.has("art")) return@runCatching
                f.writeText(meta.toString())
                js("window.nesHubAdded && window.nesHubAdded(${JSONObject.quote(meta.toString())}, true)")
            }.onFailure { Log.e(TAG, "art for ${f.name}", it) }
        }
    }

    /** The title screen: run the game a few seconds with no input, keep the busiest frame. */
    private fun titleShot(rom: ByteArray): String? {
        NativeBridge.nativeDetectGame(rom)
        val emu = Emulator.open(rom) ?: return null
        try {
            val px = IntArray(Emulator.SCREEN_PIXELS)
            var best: IntArray? = null; var bestScore = -1
            for (f in 1..480) {
                emu.runFrame(px, null)
                if (f % 40 == 0) {
                    val score = px.asSequence().distinct().take(64).count()
                    if (score > bestScore) { bestScore = score; best = px.copyOf() }
                }
            }
            val bmp = Bitmap.createBitmap(best ?: px, Emulator.SCREEN_WIDTH, Emulator.SCREEN_HEIGHT, Bitmap.Config.ARGB_8888)
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            return "data:image/png;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        } finally {
            emu.close()
        }
    }

    // ---------------------------------------------------------------- backup --

    private fun prefsDir() = File(applicationInfo.dataDir, "shared_prefs")

    private fun writeBackup(uri: Uri) = thread(name = "hub-backup") {
        val r = runCatching {
            contentResolver.openOutputStream(uri)!!.use { os ->
                ZipOutputStream(os).use { zip ->
                    fun add(base: File, prefix: String) {
                        base.walkTopDown().filter { it.isFile }.forEach { f ->
                            zip.putNextEntry(ZipEntry(prefix + f.relativeTo(base).path))
                            f.inputStream().use { it.copyTo(zip) }
                            zip.closeEntry()
                        }
                    }
                    add(filesDir, "files/")
                    add(prefsDir(), "shared_prefs/")
                }
            }
        }
        js("window.nesHubToast && window.nesHubToast(${JSONObject.quote(if (r.isSuccess) "Zapisano kopię zapisów" else "Kopia nieudana: ${r.exceptionOrNull()?.message}")})")
    }

    private fun readBackup(uri: Uri) = thread(name = "hub-restore") {
        val r = runCatching {
            var n = 0
            contentResolver.openInputStream(uri)!!.use { ins ->
                ZipInputStream(ins).use { zip ->
                    while (true) {
                        val e = zip.nextEntry ?: break
                        val target = when {
                            e.name.startsWith("files/") -> File(filesDir, e.name.removePrefix("files/"))
                            e.name.startsWith("shared_prefs/") -> File(prefsDir(), e.name.removePrefix("shared_prefs/"))
                            else -> null
                        }
                        // stay inside the app's own folders
                        if (target != null && !e.isDirectory &&
                            target.canonicalPath.startsWith(applicationInfo.dataDir)) {
                            target.parentFile?.mkdirs()
                            target.outputStream().use { zip.copyTo(it) }
                            n++
                        }
                    }
                }
            }
            require(n > 0) { "w pliku nie ma zapisów huba" }
        }
        if (r.isSuccess) runOnUiThread {
            // preferences are cached in memory: start the app again so it reads the restored ones
            startActivity(Intent(this, HubActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            Runtime.getRuntime().exit(0)
        } else js("window.nesHubToast && window.nesHubToast(${JSONObject.quote("Nie przywrócono: ${r.exceptionOrNull()?.message}")})")
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) return
        when (requestCode) {
            REQ_ADD -> addRom(uri)
            REQ_BACKUP -> writeBackup(uri)
            REQ_RESTORE -> readBackup(uri)
        }
    }

    inner class Bridge {
        @JavascriptInterface
        fun lang(): String = Locale.getDefault().language

        @JavascriptInterface
        fun launch(id: String) = launchGame(id, "")

        /** [title]: the game's name the other player sees when this phone invites over the network. */
        @JavascriptInterface
        fun launchGame(id: String, title: String) = runOnUiThread {
            if (inGame) return@runOnUiThread
            if (artBusy) {
                val msg = if (Locale.getDefault().language == "pl") "Chwila, hub przygotowuje kartridż" else "One moment, the hub is preparing a cartridge"
                js("window.nesHubToast && window.nesHubToast(${JSONObject.quote(msg)})"); return@runOnUiThread
            }
            inGame = true
            if (Net.isGuest()) { inGame = false; return@runOnUiThread }     // player 1 picks the game
            startActivity(Intent(this@HubActivity, MainActivity::class.java).putExtra(MainActivity.EXTRA_GAME, id)
                .putExtra(MainActivity.EXTRA_TITLE, title.ifEmpty { id }).putExtra(MainActivity.EXTRA_NET_LAUNCH, Net.isHost()))
            @Suppress("DEPRECATION") overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        }

        // ---- netplay, player 2's side (player 1 invites from the game's menu)

        /** Look for games: "wifi" (same router / hotspot) or "direct" (Wi-Fi Direct). */
        @JavascriptInterface
        fun netSearch(mode: String) = runOnUiThread {
            Net.search(this@HubActivity, if (mode == "direct") Net.Mode.DIRECT else Net.Mode.WIFI)
            ui.removeCallbacks(netWatch); ui.post(netWatch)
        }

        /** [{id, name, title}] of the games found. */
        @JavascriptInterface
        fun netOffers(): String = JSONArray().apply {
            Net.offers().forEach { f -> put(JSONObject().put("id", f.id).put("name", f.name).put("version", f.version).put("ok", f.ok)) }
        }.toString()

        @JavascriptInterface
        fun netJoin(id: String) = Net.join(id)

        @JavascriptInterface
        fun netGuestGo() = runOnUiThread { startGuestGame() }

        /** Player 1: open this hub to player 2 ("wifi" / "direct"); false = no socket (netStatus error). */
        @JavascriptInterface
        fun netHost(mode: String): Boolean {
            val ok = Net.host(this@HubActivity, if (mode == "direct") Net.Mode.DIRECT else Net.Mode.WIFI)
            ui.removeCallbacks(netWatch); ui.post(netWatch)
            return ok
        }

        /** Player 1's shelf selection: player 2's shelf follows it. */
        @JavascriptInterface
        fun netSelect(id: String) { if (Net.isHost()) Net.session?.select(id) }

        /** {phase, note}: what the sheet shows under the list. */
        @JavascriptInterface
        fun netStatus(): String {
            val s = Net.session
            return JSONObject().put("phase", s?.phase?.name ?: "IDLE")
                .put("role", if (s == null) "" else if (s.isHost) "host" else "guest")
                .put("peer", s?.peerName ?: "").put("sel", s?.hubSel ?: "").put("launched", s?.launched ?: false)
                .put("mode", Net.mode.name).put("addr", Net.addresses()).put("myVersion", Net.versionName(this@HubActivity))
                .put("note", Net.note()).put("reason", s?.reason?.name ?: "").put("error", s?.error?.ifEmpty { Net.lastError } ?: Net.lastError).toString()
        }

        @JavascriptInterface
        fun netStop() = runOnUiThread { ui.removeCallbacks(netWatch); Net.stop() }

        /** {id: {shot, savedAt, playMs, best, lastPlayed, fav, mods}} for the ids given (comma separated). */
        @JavascriptInterface
        fun stats(ids: String): String = JSONObject().apply {
            ids.split(',').filter { it.isNotBlank() }.forEach { put(it, statsFor(it)) }
        }.toString()

        @JavascriptInterface
        fun setFav(id: String, on: Boolean) {
            val s = HashSet(hubPrefs.getStringSet("fav", emptySet())!!)
            if (on) s.add(id) else s.remove(id)
            hubPrefs.edit().putStringSet("fav", s).apply()
        }

        @JavascriptInterface
        fun pads(): Int = padCount()

        @JavascriptInterface
        fun customGames(): String {
            if (!backfilled) { backfilled = true; backfillArt() }
            return customList().toString()
        }

        @JavascriptInterface
        fun addGame() = runOnUiThread {
            @Suppress("DEPRECATION")
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*"), REQ_ADD)
        }

        @JavascriptInterface
        fun removeGame(id: String) {
            if (!id.startsWith(MainActivity.CUSTOM_PREFIX)) return
            File(customDir, "$id.nes").delete(); File(customDir, "$id.json").delete()
            File(filesDir, id).deleteRecursively()
            getSharedPreferences("game_$id", MODE_PRIVATE).edit().clear().apply()
        }

        @JavascriptInterface
        fun backup() = runOnUiThread {
            val name = "nes-hub-zapisy-" + SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date()) + ".zip"
            @Suppress("DEPRECATION")
            startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/zip").putExtra(Intent.EXTRA_TITLE, name), REQ_BACKUP)
        }

        @JavascriptInterface
        fun restore() = runOnUiThread {
            @Suppress("DEPRECATION")
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*"), REQ_RESTORE)
        }
    }

    @Suppress("DEPRECATION")
    private fun hideSystemBars() {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onResume() {
        super.onResume()
        Net.attach(this)
        Net.onChange = { js("window.nesHubNet && window.nesHubNet()") }
        web.onResume()
        hideSystemBars()
        pushPads()
        if (inGame) {
            inGame = false
            web.evaluateJavascript("window.nesHubReturn && window.nesHubReturn()", null)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        Net.permissionAnswered(requestCode, results)
    }

    override fun onPause() {
        Net.onChange = null
        web.onPause()          // the page's visibilitychange stops the music
        super.onPause()
    }

    override fun onDestroy() {
        (getSystemService(Context.INPUT_SERVICE) as InputManager).unregisterInputDeviceListener(padListener)
        web.destroy()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "hub"
        private const val REQ_ADD = 1
        private const val REQ_BACKUP = 2
        private const val REQ_RESTORE = 3
    }
}
