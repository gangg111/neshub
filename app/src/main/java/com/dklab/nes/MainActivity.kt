package com.dklab.nes

import android.app.Activity
import android.app.Dialog
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * NES player for one hub cartridge (intent extra [EXTRA_GAME], started by [HubActivity]). The game fills the window; [ControlPad] is a translucent touch overlay
 * on top of it. Physical gamepads/keyboards go through the same pad, or -- with
 * "Pad = gracz 2" -- a gamepad drives player 2.
 *
 * Leaving the app saves the game to autosave.sav and the next start resumes it.
 */
class MainActivity : Activity() {

    lateinit var emulatorView: EmulatorView
        private set
    private lateinit var controlPad: ControlPad
    private lateinit var fpsView: TextView

    /** Which cartridge (hub game id, see game.conf games=); its ROM is assets/games/<id>.nes,
     *  its settings the prefs file "game_<id>" and its saves files/<id>/. */
    private val gameId by lazy { intent.getStringExtra(EXTRA_GAME) ?: DEFAULT_GAME }
    private val prefs get() = getSharedPreferences("game_$gameId", MODE_PRIVATE)
    private val saveDir by lazy { File(filesDir, gameId).apply { mkdirs() } }
    private var padMask = 0
    private var p2KeyMask = 0
    private var p2AxisMask = 0
    private var menu: Dialog? = null
    private var foreground = false
    /** Netplay: this phone joined another's game as player 2 (started from the hub). */
    private val netGuest by lazy { intent.getBooleanExtra(EXTRA_NET_GUEST, false) }
    /** Player 1 started this game for both (it starts anew and leaves the autosave alone). */
    private val netLaunch by lazy { intent.getBooleanExtra(EXTRA_NET_LAUNCH, false) && Net.isHost() }
    private var romCrc = 0
    private lateinit var netView: TextView
    private lateinit var topBox: android.widget.LinearLayout
    private val ui = android.os.Handler(android.os.Looper.getMainLooper())

    // settings (SharedPreferences "contraforce")
    private var turboA = false
    private var turboB = false
    private var showFps = false
    private var padIsP2 = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        preferSixtyHzMode()

        // Swap the layout's placeholder SurfaceView for the real one.
        val placeholder = findViewById<View>(R.id.emulator_surface)
        val container = placeholder.parent as ViewGroup
        container.removeView(placeholder)
        emulatorView = EmulatorView(this).also {
            it.id = R.id.emulator_surface
            container.addView(it, 0, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER))
        }

        controlPad = ControlPad(this)
        findViewById<ViewGroup>(R.id.controls_container).addView(controlPad, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        // v2.3: the unfolded default was 60% for the whole overlay; meant only for the top row.
        if (!prefs.getBoolean("size_fix_23", false)) {
            if (prefs.getInt(KEY_SIZE_SQUARE, 60) == 60) {
                prefs.edit().remove(KEY_SIZE_SQUARE).apply()
                controlPad.redoDefaults = setOf("square")
            }
            prefs.edit().putBoolean("size_fix_23", true).apply()
        }
        // v2.5.1: folded top row 70% and the FPS counter off by default, also for saved settings.
        if (!prefs.getBoolean("defaults_251", false)) {
            controlPad.redoDefaults = controlPad.redoDefaults + "wide"
            prefs.edit().putBoolean(KEY_SHOW_FPS, false).putBoolean("defaults_251", true).apply()
        }
        prefs.let {
            turboA = it.getBoolean(KEY_TURBO_A, false)
            turboB = it.getBoolean(KEY_TURBO_B, false)
            showFps = it.getBoolean(KEY_SHOW_FPS, false)
            padIsP2 = it.getBoolean(KEY_PAD_P2, false)
            controlPad.overlayAlpha = it.getInt(KEY_ALPHA, 80) * 255 / 100
            controlPad.classSizes = mapOf("wide" to it.getInt(KEY_SIZE, 100) / 100f,
                "square" to it.getInt(KEY_SIZE_SQUARE, 100) / 100f)
            controlPad.hapticsEnabled = it.getBoolean(KEY_HAPTICS, true)
            emulatorView.crt = it.getBoolean(KEY_CRT, true)
            emulatorView.crtCurved = it.getBoolean(KEY_CRT_CURVED, true)
            emulatorView.crtBloom = it.getBoolean(KEY_BLOOM, false)
            emulatorView.bezel = it.getBoolean(KEY_BEZEL, false)
        }
        controlPad.savedLayouts = listOf("wide", "square").associateWith { prefs.getString("layout_$it", null) ?: "" }
        controlPad.onLayoutEdited = { cls, str -> prefs.edit().putString("layout_$cls", str).apply() }
        controlPad.onButtonsChanged = { mask ->
            val pressed = mask and padMask.inv()
            padMask = mask
            if (pressed and GbButtons.MENU != 0) openMenu()
            sendButtons()
        }

        // FPS counter over the game.
        fpsView = TextView(this).apply {
            setTextColor(0xFFFFFFFF.toInt())
            setShadowLayer(4f, 0f, 0f, 0xFF000000.toInt())
            textSize = 13f
            typeface = Typeface.MONOSPACE
            setBackgroundColor(0x66000000)
            setPadding(12, 4, 12, 4)
            visibility = if (showFps) View.VISIBLE else View.GONE
        }
        // top centre: the top corners belong to SELECT/START (left) and MENU & co. (right).
        // The netplay line sits there too, above the FPS counter.
        topBox = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
        }
        findViewById<ViewGroup>(R.id.root).addView(topBox, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { setMargins(0, 24, 0, 0) })
        topBox.addView(fpsView)
        var stalls0 = 0L; var catch0 = 0L; var slowSeconds = 0
        emulatorView.onFps = { shown, game ->
            var text = "%.0f FPS · gra %.0f".format(shown, game)
            Log.d(TAG, "fps shown=%.1f game=%.1f".format(shown, game))   // adb logcat: pacing tests
            // The CRT glow doubles the GPU's work: a phone that cannot keep 60 with it (the A22
            // ran 30, and in a network game the slower phone slows both) gets it turned off.
            val waited = emulatorView.netStallCount - stalls0
            slowSeconds = if (emulatorView.crt && emulatorView.crtBloom && shown < 50f && waited < 5) slowSeconds + 1 else 0
            if (slowSeconds >= 2) {
                slowSeconds = 0
                emulatorView.crtBloom = false; put(KEY_BLOOM, false)
                fpsView.post { Toast.makeText(this, "Poświata wyłączona: ten telefon nie nadąża z nią w 60 klatkach", Toast.LENGTH_LONG).show() }
            }
            if (emulatorView.netPlay != null) {
                // the network's hitches in the last second: waits for the other phone, catch-ups
                val st = emulatorView.netStallCount; val cu = emulatorView.netCatchUpCount
                text += " · sieć: czekanie ${st - stalls0}, nadrabianie ${cu - catch0}"
                val np = emulatorView.netPlay
                Net.log("stat: " + (if (netGuest) "player 2" else "player 1") + " ran=%.0f wait=${st - stalls0} catchup=${cu - catch0}".format(game) +
                    (if (netGuest) " buffer=${np?.backlog() ?: 0}" else " lead=${(Net.session?.guestPadMax() ?: -1) - (np?.step ?: 0)}") +
                    " step=${np?.step ?: 0}")
                stalls0 = st; catch0 = cu
            }
            fpsView.post { fpsView.text = text }
        }

        // Picture: the core's palette and NTSC signal (read when the game loads).
        applyPicture()

        // The cartridge: clean ROM + the enabled patches; the game module (fixes, mods) is
        // then picked from the result by itself (see games/*.c game_sig), not by name.
        val start = if (netGuest) Net.session?.start else null
        val rom = try {
            buildRom(start?.patches?.split("|")?.filter { it.isNotEmpty() }?.toSet())
                .also { moduleId = NativeBridge.nativeDetectGame(it); romCrc = crc(it) }
        } catch (e: Exception) {
            Log.e(TAG, "rom asset missing", e); null
        }
        if (netGuest && (start == null || rom == null || romCrc != start.romCrc)) {
            Toast.makeText(this, "Gracz 1 ma inną wersję tej gry (łatki) — nie da się dołączyć", Toast.LENGTH_LONG).show()
            Net.stop(); finish(); return
        }
        if (rom == null || !emulatorView.loadRom(rom, null)) {
            Toast.makeText(this, "Nie udało się uruchomić gry", Toast.LENGTH_LONG).show()
            return
        }
        // Light-gun games (Duck Hunt): touch the picture to aim and shoot.
        if (emulatorView.usesZapper) {
            controlPad.aimMode = true
            val padAt = IntArray(2); val emuAt = IntArray(2)
            controlPad.onAim = { x, y, down ->
                controlPad.getLocationOnScreen(padAt); emulatorView.getLocationOnScreen(emuAt)
                emulatorView.aim(x + padAt[0] - emuAt[0], y + padAt[1] - emuAt[1], down)
            }
        }
        // Resume where the player left off -- unless the patches changed since (other ROM).
        if (patchErrors.isNotEmpty()) toast("Pominięto łatkę: " + patchErrors.joinToString("; "))
        // A two-player game starts anew; the autosave belongs to playing alone.
        if (!netGuest && !netLaunch && autosave.isFile && (prefs.getString(KEY_AUTOSAVE_PATCHES, null) ?: patchSet) == patchSet) {
            val ok = runCatching { emulatorView.loadState(autosave.readBytes()) }.getOrDefault(false)
            if (!ok) Log.w(TAG, "autosave unusable, starting fresh")
        }
        // Re-apply the game's remembered modifications (toggles marked persist).
        if (!netGuest) for (m in emulatorView.mods()) if (m.persist && prefs.contains(modKey(m))) {
            emulatorView.modSet(m.index, prefs.getInt(modKey(m), 0))
        }

        // Netplay status line (bottom centre) and the guest's start.
        netView = TextView(this).apply {
            // the hub's pixel font (Press Start 2P with the Polish letters fixed, assets/fonts/pixel.ttf)
            typeface = runCatching { Typeface.createFromAsset(assets, "fonts/pixel.ttf") }.getOrDefault(Typeface.MONOSPACE)
            setTextColor(0xFFFFFFFF.toInt()); setShadowLayer(0f, 3f, 3f, 0xFF000000.toInt())
            textSize = 16f; gravity = Gravity.CENTER; setLineSpacing(0f, 1.3f)
            maxWidth = (resources.displayMetrics.widthPixels * 0.62f).toInt()
            setBackgroundColor(0xB3000000.toInt()); setPadding(28, 18, 28, 18); visibility = View.GONE
        }
        topBox.addView(netView, 0, android.widget.LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 12 })
        if (netGuest) startNetPlay(host = false)
        // Player 1 picked this game in the shared hub: it starts on both phones.
        if (netLaunch) {
            // Wi-Fi Direct's packets arrive less evenly: the guest's pad waits a little longer (5 frames, 83 ms)
            Net.session?.launch(NetSession.Start(gameId, romCrc, patchSet, NET_DELAY[Net.mode] ?: 5, intent.getStringExtra(EXTRA_TITLE) ?: gameId))
            startNetPlay(host = true)
        }
        guestEpoch = Net.session?.epoch ?: -1
        ui.post(netTick)
    }

    /**
     * The NES draws 60 frames a second. On a panel locked to 90 Hz (Galaxy A22 with "high"
     * motion smoothness) they land 1 and 2 refreshes apart in turn: an uneven, juddering
     * picture, and in a network game a phone that keeps falling behind. Surface.setFrameRate
     * (EmulatorView) is only a hint Samsung ignores there; the window's preferred display mode
     * is what games use: the mode with this resolution whose rate is a multiple of 60
     * (60 or 120); the Fold's 120 stays 120.
     */
    private fun preferSixtyHzMode() {
        runCatching<Unit> {
            @Suppress("DEPRECATION") val d = windowManager.defaultDisplay
            val cur = d.mode
            Net.log("display: now ${cur.modeId} ${cur.refreshRate} Hz, modes " + d.supportedModes.joinToString { "${it.modeId}=${it.physicalWidth}x${it.physicalHeight}@${it.refreshRate}" })
            fun even(hz: Float) = (hz / 60f).let { kotlin.math.abs(it - Math.round(it)) < 0.02f && it >= 1f }
            // Always chosen, not only when the reported mode is off: after setFrameRate(60) the
            // A22 reports 60 Hz to the app (a frame-rate override) while its panel stays at 90.
            val m = d.supportedModes.filter { it.physicalWidth == cur.physicalWidth && it.physicalHeight == cur.physicalHeight && even(it.refreshRate) }
                .maxByOrNull { it.refreshRate } ?: run { Net.log("display: no mode at a multiple of 60"); return }
            window.attributes = window.attributes.apply { preferredDisplayModeId = m.modeId }
            Log.i(TAG, "display ${cur.refreshRate} Hz -> mode ${m.modeId} (${m.refreshRate} Hz) for even 60 fps")
            Net.log("display: ${cur.refreshRate} Hz -> ${m.refreshRate} Hz (mode ${m.modeId})")
        }.onFailure { Net.log("display: mode not set: $it") }
    }

    // ---------------------------------------------------------------- netplay --

    private fun crc(b: ByteArray) = java.util.zip.CRC32().apply { update(b) }.value.toInt()

    /** The mods applied as cheat codes (toggles): the state does not hold them, NetPlay sends them. */
    private fun netToggles() = emulatorView.mods().filter { it.type == MOD_TOGGLE }.map { it.index }

    private fun startNetPlay(host: Boolean) {
        val s = Net.session ?: return
        emulatorView.netHost = host
        emulatorView.netSession = { Net.session }
        emulatorView.netPlay = NetPlay(s, emulatorView.netMachine(netToggles()), s.start?.delay ?: 3)
        Net.settle()
    }

    private var guestEpoch = -1

    private fun leaveNet() {
        emulatorView.netPlay = null
        Net.stop()
        if (netGuest) finish()
    }

    /** A host's or a guest's change of the game's state goes to the other phone whole. */
    private fun netStateChanged() { if (!netGuest) emulatorView.netPlay?.hostWantsState = true }

    private val netTick: Runnable = object : Runnable {
        override fun run() {
            ui.postDelayed(this, 300)
            val s = Net.session
            val np = emulatorView.netPlay
            val text = when {
                s == null -> if (netGuest) "Połączenie zakończone" else null
                // player 1 went back to the hub (or started another game): so does player 2
                netGuest && s.phase == NetSession.Phase.PLAYING && (!s.launched || s.epoch != guestEpoch) -> {
                    emulatorView.netPlay = null; finish(); null
                }
                s.phase == NetSession.Phase.PLAYING -> when {
                    // nothing over the game while it flows; a line only when it waits for the other phone
                    emulatorView.netWaiting > 30 -> if (netGuest) "Czekam na gracza 1 (${s.peerName})…" else "Czekam na gracza 2 (${s.peerName})…"
                    else -> null
                }
                s.phase == NetSession.Phase.ENDED -> {
                    emulatorView.netPlay = null
                    val why = when (s.reason) { NetSession.Reason.TIMEOUT -> "brak połączenia"; NetSession.Reason.VERSION -> "inna wersja huba"; else -> "drugi gracz wyszedł" }
                    Net.stop(); toast("Gra sieciowa zakończona: $why")
                    if (netGuest) finish()
                    null
                }
                else -> null
            }
            netView.visibility = if (text == null) View.GONE else View.VISIBLE
            if (text != null && netView.text != text) netView.text = text
        }
    }

    // ------------------------------------------------------------------ input --

    /** Pad state -> emulator. MENU/REWIND/FFWD are ours; with turbo on, A/B become the
     *  core's turbo bits (pulsed 2 frames on / 2 off in nes_core.c). */
    private fun sendButtons() {
        emulatorView.rewind = padMask and GbButtons.REWIND != 0
        emulatorView.fastForward = padMask and GbButtons.FFWD != 0
        emulatorView.setButtons(withTurbo(padMask))
        emulatorView.buttons2 = withTurbo(p2KeyMask or p2AxisMask)
    }

    private fun withTurbo(mask: Int): Int {
        var m = mask and (GbButtons.MENU or GbButtons.REWIND or GbButtons.FFWD).inv()
        if (turboA && m and GbButtons.A != 0) m = (m and GbButtons.A.inv()) or GbButtons.TURBO_A
        if (turboB && m and GbButtons.B != 0) m = (m and GbButtons.B.inv()) or GbButtons.TURBO_B
        return m
    }

    private fun isGamepad(source: Int) =
        source and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
            source and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK

    /** With "Pad = gracz 2", a gamepad key goes to player 2 (its MENU still opens the menu). */
    private fun handleP2Key(event: KeyEvent): Boolean {
        if (!padIsP2 || !isGamepad(event.source)) return false
        val bit = controlPad.keyCodeToButton(event.keyCode)
        if (bit == 0) return false
        val down = event.action == KeyEvent.ACTION_DOWN
        if (bit == GbButtons.MENU) { if (down && event.repeatCount == 0) openMenu(); return true }
        val game = bit and (GbButtons.REWIND or GbButtons.FFWD).inv()
        p2KeyMask = if (down) p2KeyMask or game else p2KeyMask and game.inv()
        sendButtons()
        return true
    }

    private fun handleP2Motion(event: MotionEvent): Boolean {
        if (!padIsP2 || event.source and InputDevice.SOURCE_JOYSTICK != InputDevice.SOURCE_JOYSTICK) return false
        var x = event.getAxisValue(MotionEvent.AXIS_HAT_X)
        var y = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
        if (abs(x) < 0.5f && abs(y) < 0.5f) {
            x = event.getAxisValue(MotionEvent.AXIS_X)
            y = event.getAxisValue(MotionEvent.AXIS_Y)
        }
        var m = 0
        if (x <= -0.5f) m = m or GbButtons.LEFT
        if (x >= 0.5f) m = m or GbButtons.RIGHT
        if (y <= -0.5f) m = m or GbButtons.UP
        if (y >= 0.5f) m = m or GbButtons.DOWN
        p2AxisMask = m
        sendButtons()
        return true
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (editing && keyCode == KeyEvent.KEYCODE_BACK) { stopLayoutEditor(); return true }
        return handleP2Key(event) || controlPad.handleKeyEvent(event) || super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean =
        handleP2Key(event) || controlPad.handleKeyEvent(event) || super.onKeyUp(keyCode, event)

    override fun onGenericMotionEvent(event: MotionEvent): Boolean =
        handleP2Motion(event) || controlPad.handleMotionEvent(event) || super.onGenericMotionEvent(event)

    // ------------------------------------------------------------------- menu --

    private fun modKey(m: Emulator.Mod) = "mod_" + m.key

    private fun put(key: String, v: Boolean) = prefs.edit().putBoolean(key, v).apply()
    private fun put(key: String, v: Int) = prefs.edit().putInt(key, v).apply()

    /** In-game menu (NES-pad styled, see NesMenu). The game pauses while it is open. */
    private fun openMenu() {
        if (menu?.isShowing == true) return
        controlPad.releaseAll()
        p2KeyMask = 0; p2AxisMask = 0
        sendButtons()
        emulatorView.onActivityPause()

        val savePage = NesMenu.Page("Zapisz") { (1..SLOTS).map { n ->
            NesMenu.Item.Action("Slot $n", preview = {
                emulatorView.snapshot() to (if (slotFile(n).isFile) "Nadpisze: ${slotCaption(n)}" else "Pusty slot")
            }) { saveSlot(n) }
        } }
        val loadPage = NesMenu.Page("Wczytaj") { (1..SLOTS).map { n ->
            NesMenu.Item.Action("Slot $n", preview = {
                if (!slotFile(n).isFile) null to "Pusty slot" else slotShot(n) to slotCaption(n)
            }) { loadSlot(n) }
        } }
        val statePage = NesMenu.Page("Stan gry") { listOf(
            NesMenu.Item.Submenu("Zapisz", savePage),
            NesMenu.Item.Submenu("Wczytaj", loadPage),
            NesMenu.Item.Action("Zacznij od nowa", preview = { null to "Gra wróci do ekranu tytułowego" }) {
                emulatorView.reset(); netStateChanged(); toast("Nowa gra")
            },
        ) }
        // Built from the game plug-in's list (games/<name>.c), so the app holds no RAM addresses.
        val mods = emulatorView.mods()
        val modsPage = NesMenu.Page("Modyfikacje") { mods.filter { it.type != MOD_STAT }.map { m ->
            when (m.type) {
                MOD_TOGGLE -> NesMenu.Item.Toggle(m.label, emulatorView.modGet(m.index) != 0) {
                    emulatorView.modSet(m.index, if (it) 1 else 0); netStateChanged()
                    if (m.persist) put(modKey(m), if (it) 1 else 0)
                }
                MOD_STEPPER -> NesMenu.Item.Stepper(m.label, emulatorView.modGet(m.index).coerceIn(m.min, m.max),
                    m.min, m.max, format = if (m.max >= 10) { v -> "%02d".format(v) } else { v -> "$v" }) {
                    emulatorView.modSet(m.index, it); netStateChanged()
                    if (m.persist) put(modKey(m), it)
                }
                else -> NesMenu.Item.Action(m.label, preview = {
                    null to m.caption.replace("%d", emulatorView.modGet(m.index).toString())
                }) { emulatorView.modSet(m.index, 1); netStateChanged(); toast(m.label) }
            }
        } }
        // Size per group (this screen: folded and unfolded keep their own), plus everything at once.
        fun groupSize(label: String, groups: List<PadGroup>) = NesMenu.Item.Stepper(label,
            controlPad.groupScalePercent(groups), (ControlPad.MIN_SCALE * 100).toInt(), (ControlPad.MAX_SCALE * 100).toInt(),
            step = 10, format = { "$it%" }) { controlPad.setGroupScalePercent(groups, it) }
        val sizePage = NesMenu.Page("Rozmiar") { listOf(
            groupSize("Krzyżak", listOf(PadGroup.DPAD)),
            groupSize("A i B", listOf(PadGroup.AB)),
            groupSize("Select i Start", listOf(PadGroup.SELSTART)),
            groupSize("Przyciski nakładki", listOf(PadGroup.MENU, PadGroup.REW, PadGroup.FF)),
            // per screen: folded (wide) and unfolded (square) keep their own size
            NesMenu.Item.Stepper("Wszystko", Math.round(controlPad.sizeScale * 100), 60, 150, step = 10, format = { "$it%" }) {
                controlPad.setClassSize(it / 100f)
                put(if (controlPad.layoutClass == "square") KEY_SIZE_SQUARE else KEY_SIZE, it)
            },
        ) }
        val overlayPage = NesMenu.Page("Nakładka") { listOf(
            NesMenu.Item.Stepper("Krycie", prefs.getInt(KEY_ALPHA, 80), 10, 100, step = 10, format = { "$it%" }) {
                controlPad.overlayAlpha = it * 255 / 100; put(KEY_ALPHA, it)
            },
            NesMenu.Item.Submenu("Rozmiar", sizePage),
            NesMenu.Item.Action("Edytuj układ") { startLayoutEditor() },
            NesMenu.Item.Toggle("Wibracja", controlPad.hapticsEnabled) {
                controlPad.hapticsEnabled = it; put(KEY_HAPTICS, it)
            },

            NesMenu.Item.Toggle("Licznik FPS", showFps) {
                showFps = it; fpsView.visibility = if (it) View.VISIBLE else View.GONE; put(KEY_SHOW_FPS, it)
            },
        ) }
        // Netplay is set up in the hub (Gra przez sieć); here only leaving it.
        val netItems = if (emulatorView.netPlay == null) emptyList() else listOf<NesMenu.Item>(
            NesMenu.Item.Action("Zakończ grę sieciową", preview = { null to "Gracz 2: ${Net.session?.peerName ?: ""} — rozłączy oba telefony" }) {
                menu?.dismiss(); leaveNet()
            })
        if (netGuest) {
            // The guest plays the host's game: only its own overlay and leaving.
            menu = NesMenu(this).show(NesMenu.Page("Menu") { listOf(
                NesMenu.Item.Submenu("Nakładka", overlayPage),
                NesMenu.Item.Submenu("Obraz", picturePage()),
                NesMenu.Item.MultiToggle("Turbo", listOf("B", "A"), booleanArrayOf(turboB, turboA)) { i, on ->
                    if (i == 0) { turboB = on; put(KEY_TURBO_B, on) } else { turboA = on; put(KEY_TURBO_A, on) }
                },
                NesMenu.Item.Action("Opuść grę sieciową", preview = { null to "Gracz 1: ${Net.session?.peerName ?: ""}" }) { menu?.dismiss(); leaveNet() },
            ) }) { menu = null; sendButtons(); hideSystemBars(); if (foreground && !editing) emulatorView.onActivityResume() }
            return
        }
        menu = NesMenu(this).show(NesMenu.Page("Menu") { listOf(
            NesMenu.Item.Submenu("Stan gry", statePage),
            NesMenu.Item.Submenu("Obraz", picturePage()),
        ) + (if (mods.isEmpty()) emptyList<NesMenu.Item>() else listOf<NesMenu.Item>(NesMenu.Item.Submenu("Modyfikacje", modsPage))) + listOf(
            NesMenu.Item.Submenu("Nakładka", overlayPage),
            NesMenu.Item.Submenu("Łatki", patchesPage()),
            // NES order: B left, A right.
            NesMenu.Item.MultiToggle("Turbo", listOf("B", "A"), booleanArrayOf(turboB, turboA)) { i, on ->
                if (i == 0) { turboB = on; put(KEY_TURBO_B, on) } else { turboA = on; put(KEY_TURBO_A, on) }
            },
            NesMenu.Item.Toggle("Pad = gracz 2", padIsP2) { padIsP2 = it; put(KEY_PAD_P2, it) },
        ) + netItems + listOf(
            // Back to the hub; onPause writes the autosave, so the game resumes next time.
            NesMenu.Item.Action("Zmień grę", preview = { null to if (emulatorView.netPlay != null) "Oba telefony wrócą do huba" else "Gra zostanie zapisana i wznowiona przy powrocie" }) {
                menu?.dismiss(); finish()
            },
        ) }) {
            menu = null
            sendButtons()
            hideSystemBars()
            if (foreground && !editing) emulatorView.onActivityResume()
        }
    }

    // ---------------------------------------------------------------- picture --
    // Established work only: crt-lottes (CrtShader.kt), Blargg's NTSC filter and the
    // palettes built into FCEUmm, NyNy77's bezel from libretro/overlay-borders.

    private fun applyPicture() {
        NativeBridge.nativeSetOption("fceumm_palette", PALETTES[prefs.getInt(KEY_PALETTE, 0).coerceIn(PALETTES.indices)].first)
        val sig = prefs.getInt(KEY_NTSC, 0).coerceIn(SIGNALS.indices)
        NativeBridge.nativeSetOption("fceumm_ntsc_filter", SIGNALS[sig].first)
        emulatorView.ntsc = sig != 0
    }

    private fun picturePage() = NesMenu.Page("Obraz") { listOf(
        // one row: no filter / CRT tube (rounded) / CRT flat
        NesMenu.Item.MultiToggle("Filtr", listOf("BRAK", "CRT", "PŁASKI"), booleanArrayOf(
            !emulatorView.crt, emulatorView.crt && emulatorView.crtCurved, emulatorView.crt && !emulatorView.crtCurved), radio = true) { i, _ ->
            emulatorView.crt = i != 0; put(KEY_CRT, i != 0)
            if (i != 0) { emulatorView.crtCurved = i == 1; put(KEY_CRT_CURVED, i == 1) }
            emulatorView.redraw()          // the paused frame shows the filter at once
        },
        NesMenu.Item.Toggle("Poświata (CRT)", emulatorView.crtBloom) {
            emulatorView.crtBloom = it; put(KEY_BLOOM, it); emulatorView.redraw()
        },
        NesMenu.Item.Stepper("Sygnał", prefs.getInt(KEY_NTSC, 0).coerceIn(SIGNALS.indices), 0, SIGNALS.size - 1,
            format = { SIGNALS[it].second }) {
            put(KEY_NTSC, it); applyPicture(); emulatorView.repaint()
        },
        NesMenu.Item.Stepper("Paleta", prefs.getInt(KEY_PALETTE, 0).coerceIn(PALETTES.indices), 0, PALETTES.size - 1,
            format = { PALETTES[it].second }) {
            put(KEY_PALETTE, it); applyPicture(); emulatorView.repaint()
        },
        NesMenu.Item.Toggle("Ramka telewizora", emulatorView.bezel) {
            emulatorView.bezel = it; put(KEY_BEZEL, it); emulatorView.redraw()
        },
    ) }

    // ------------------------------------------------------------ save states --

    private val autosave get() = File(saveDir, "autosave.sav")
    private fun slotFile(n: Int) = File(saveDir, "slot$n.sav")
    private fun slotPng(n: Int) = File(saveDir, "slot$n.png")
    private fun slotCaption(n: Int) = "zapis z " +
        SimpleDateFormat("HH:mm, dd.MM", Locale.getDefault()).format(Date(slotFile(n).lastModified()))

    /** Write beside and rename: a kill mid-write must not destroy the old file. */
    private fun writeAtomic(target: File, write: (File) -> Unit): Boolean {
        val tmp = File(target.parentFile, target.name + ".tmp")
        write(tmp)
        return tmp.renameTo(target)
    }

    private fun saveSlot(n: Int) {
        val blob = emulatorView.saveState()
        val ok = blob != null && runCatching {
            writeAtomic(slotFile(n)) { it.writeBytes(blob) } &&
                writeAtomic(slotPng(n)) { f ->
                    f.outputStream().use { emulatorView.snapshot().compress(Bitmap.CompressFormat.PNG, 100, it) }
                }
        }.getOrElse { Log.e(TAG, "state save failed", it); false }
        toast(if (ok) "Zapisano w slocie $n" else "Zapis nie powiódł się")
    }

    /** The slot's frame: the PNG stored with it, or rendered once from the state. */
    private fun slotShot(n: Int): Bitmap? {
        slotPng(n).takeIf { it.isFile }?.let { f -> BitmapFactory.decodeFile(f.path)?.let { return it } }
        val bmp = runCatching { emulatorView.renderState(slotFile(n).readBytes()) }.getOrNull() ?: return null
        runCatching {
            writeAtomic(slotPng(n)) { f -> f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        }
        return bmp
    }

    private fun loadSlot(n: Int) {
        val f = slotFile(n)
        if (!f.isFile) { toast("Slot $n jest pusty"); return }
        val ok = runCatching { emulatorView.loadState(f.readBytes()) }.getOrDefault(false)
        if (ok) netStateChanged()
        toast(if (ok) "Wczytano slot $n" else "Zapis uszkodzony lub z innej wersji")
    }

    /** The cartridge: a built-in one from assets, or one the player added in the hub. */
    private fun cleanRom(): ByteArray =
        if (gameId.startsWith(CUSTOM_PREFIX)) File(filesDir, "custom/$gameId.nes").readBytes()
        else assets.open("games/$gameId.nes").use { it.readBytes() }

    private fun writeAutosave() {
        val blob = emulatorView.saveState() ?: return
        runCatching { writeAtomic(autosave) { it.writeBytes(blob) } }
            .onSuccess { prefs.edit().putString(KEY_AUTOSAVE_PATCHES, patchSet).putLong(KEY_SAVED_AT, System.currentTimeMillis()).apply() }
            .onFailure { Log.e(TAG, "autosave failed", it) }
        // the hub's shelf shows where the player left off
        runCatching {
            writeAtomic(File(saveDir, "autosave.jpg")) { f -> f.outputStream().use { emulatorView.snapshot().compress(Bitmap.CompressFormat.JPEG, 85, it) } }
        }
    }

    /** Play time and best score for the hub (score: the plug-in's MOD_STAT "score"). */
    private var playingSince = 0L
    private fun recordStats() {
        val e = prefs.edit()
        if (playingSince != 0L) {
            e.putLong(KEY_PLAY_MS, prefs.getLong(KEY_PLAY_MS, 0) + (android.os.SystemClock.elapsedRealtime() - playingSince))
            playingSince = 0L
        }
        emulatorView.mods().firstOrNull { it.type == MOD_STAT && it.key == "score" }?.let { m ->
            val v = emulatorView.modGet(m.index)
            if (v > prefs.getInt(KEY_BEST, 0)) e.putInt(KEY_BEST, v)
        }
        e.putLong(KEY_LAST_PLAYED, System.currentTimeMillis()).apply()
    }

    // ---------------------------------------------------------------- patches --
    // Built-in: assets/patches/<game>/ (on by default). Added from the phone: files/<game>/patches/.

    private class Patch(val name: String, val builtIn: Boolean, val read: () -> ByteArray)

    private var moduleId = ""
    private var patchSet = ""                       // enabled patches of the running ROM
    private val patchErrors = ArrayList<String>()
    private val userPatchDir get() = File(saveDir, "patches")

    /** Built-in patches go by the game (its module), so a dump added from the phone gets them too. */
    private val patchGame by lazy {
        if (gameId.startsWith(CUSTOM_PREFIX)) NativeBridge.nativeDetectGame(cleanRom()).ifEmpty { gameId } else gameId
    }

    private fun patches(): List<Patch> {
        val builtIn = (assets.list("patches/$patchGame") ?: emptyArray()).sorted()
            .map { n -> Patch(n, true) { assets.open("patches/$patchGame/$n").use { it.readBytes() } } }
        val user = (userPatchDir.listFiles() ?: emptyArray()).filter { it.isFile }.sortedBy { it.name }
            .map { f -> Patch(f.name, false) { f.readBytes() } }
        return builtIn + user
    }

    private fun patchOn(p: Patch) = prefs.getBoolean("patch_" + p.name, p.builtIn)

    /** [only]: exactly these patches (a netplay guest takes the host's), else the player's choice. */
    private fun buildRom(only: Set<String>? = null): ByteArray {
        var rom = cleanRom()
        val used = ArrayList<String>()
        for (p in patches()) if (only?.contains(p.name) ?: patchOn(p)) {
            try { rom = Patches.apply(rom, p.read()); used += p.name }
            catch (e: Exception) { patchErrors += "${p.name.substringBeforeLast('.')} (${e.message})" }
        }
        patchSet = used.joinToString("|")
        return rom
    }

    private fun patchLabel(p: Patch) = p.name.substringBeforeLast('.')

    private fun restartWithPatches() {
        menu?.dismiss()
        toast("Gra uruchamia się od nowa z wybranymi łatkami")
        recreate()
    }

    private fun pickPatch() {
        val i = android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(android.content.Intent.CATEGORY_OPENABLE).setType("*/*")
        menu?.dismiss()
        @Suppress("DEPRECATION") startActivityForResult(i, REQ_PATCH)
    }

    @Deprecated("platform API, no AndroidX here")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        @Suppress("DEPRECATION") super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data ?: return
        if (requestCode != REQ_PATCH || resultCode != RESULT_OK) return
        try {
            val bytes = contentResolver.openInputStream(uri)?.use { s ->
                val b = s.readBytes(); if (b.size > 16 * 1024 * 1024) error("plik jest za duży"); b
            } ?: error("nie da się odczytać pliku")
            if (!Patches.isPatch(bytes)) error("to nie jest łatka IPS ani BPS")
            // the patch must apply to this game's ROM (BPS checks the ROM version itself)
            Patches.apply(cleanRom(), bytes)
            var name = queryName(uri)?.replace(Regex("[/\\\\]"), "_") ?: "łatka.ips"
            if (!name.lowercase().endsWith(".ips") && !name.lowercase().endsWith(".bps"))
                name += if (bytes[0] == 'B'.code.toByte()) ".bps" else ".ips"
            userPatchDir.mkdirs()
            writeAtomic(File(userPatchDir, name)) { it.writeBytes(bytes) }
            prefs.edit().putBoolean("patch_$name", true).apply()
            restartWithPatches()
        } catch (e: Exception) {
            Log.w(TAG, "patch rejected", e)
            Toast.makeText(this, "Nie dodano łatki: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun queryName(uri: android.net.Uri): String? = runCatching {
        contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    private fun patchesPage() = NesMenu.Page("Łatki") {
        val list = patches()
        listOf<NesMenu.Item>(
            NesMenu.Item.Action(if (moduleId.isNotEmpty()) "Moduł gry: aktywny" else "Moduł gry: brak", preview = {
                null to (if (moduleId.isNotEmpty())
                    "Hub rozpoznał grę zgodną z modułem „$moduleId”: poprawki i modyfikacje działają"
                else "Ta wersja gry nie pasuje do żadnego modułu: gra działa bez poprawek i modyfikacji")
            }) {},
        ) + list.map { p ->
            NesMenu.Item.Toggle(patchLabel(p) + if (p.builtIn) "" else " (własna)", patchOn(p)) {
                prefs.edit().putBoolean("patch_" + p.name, it).apply()
            }
        } + listOf(
            NesMenu.Item.Action("Uruchom z tymi łatkami", preview = {
                null to "Gra wystartuje od nowa (sloty zapisu zostają)"
            }) { restartWithPatches() },
            NesMenu.Item.Action("Dodaj łatkę z telefonu", preview = {
                null to "Plik .ips lub .bps, np. z Pobranych"
            }) { pickPatch() },
        )
    }

    // ---------------------------------------------------------- layout editor --

    private var editing = false
    private var editBar: View? = null

    /** Drag controls on a grid; − / + resize the selected one. The game stays paused. */
    private fun startLayoutEditor() {
        editing = true
        emulatorView.onActivityPause()
        controlPad.editMode = true
        val dp = resources.displayMetrics.density
        fun btn(text: String, onClick: () -> Unit) = TextView(this).apply {
            this.text = text
            setTextColor(0xFFF52B26.toInt())
            textSize = if (text.length == 1) 26f else 16f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding((14 * dp).toInt(), (6 * dp).toInt(), (14 * dp).toInt(), (6 * dp).toInt())
            background = android.graphics.drawable.StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_pressed), android.graphics.drawable.GradientDrawable().apply {
                    setColor(0xFFC4C6C1.toInt()); cornerRadius = 10 * dp; setStroke((2.5f * dp).toInt(), 0xFF1B1E20.toInt()) })
                addState(intArrayOf(), android.graphics.drawable.GradientDrawable().apply {
                    setColor(0xFFE3E5E1.toInt()); cornerRadius = 10 * dp; setStroke((2.5f * dp).toInt(), 0xFF1B1E20.toInt()) })
            }
            setOnClickListener { onClick() }
        }
        val bar = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding((12 * dp).toInt(), (10 * dp).toInt(), (12 * dp).toInt(), (12 * dp).toInt())
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xEE3D4143.toInt()); cornerRadius = 14 * dp; setStroke((3 * dp).toInt(), 0xFFAEB1AA.toInt()) }
            addView(TextView(this@MainActivity).apply {
                text = "Przeciągnij przycisk • − / + zmienia rozmiar zaznaczonego"
                setTextColor(0xFFE3E5E1.toInt()); textSize = 13f; gravity = Gravity.CENTER
                setPadding(0, 0, 0, (8 * dp).toInt())
            })
            val row = android.widget.LinearLayout(this@MainActivity).apply { orientation = android.widget.LinearLayout.HORIZONTAL }
            fun add(v: View) = row.addView(v, android.widget.LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = (6 * dp).toInt(); marginEnd = (6 * dp).toInt() })
            add(btn("−") { controlPad.scaleSelected(-0.1f) })
            add(btn("+") { controlPad.scaleSelected(0.1f) })
            add(btn("DOMYŚLNE") { controlPad.resetLayout() })
            add(btn("GOTOWE") { stopLayoutEditor() })
            addView(row)
        }
        findViewById<ViewGroup>(R.id.root).addView(bar, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        editBar = bar
    }

    private fun stopLayoutEditor() {
        editing = false
        controlPad.editMode = false
        editBar?.let { (it.parent as? ViewGroup)?.removeView(it) }
        editBar = null
        hideSystemBars()
        if (foreground) emulatorView.onActivityResume()
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    // -------------------------------------------------------------- lifecycle --

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
        foreground = true
        playingSince = android.os.SystemClock.elapsedRealtime()
        hideSystemBars()
        // Stay paused behind an open menu.
        if (menu?.isShowing != true && !editing) emulatorView.onActivityResume()
    }

    override fun onPause() {
        // A finger still down when the app goes to the background would stay latched.
        foreground = false
        controlPad.releaseAll()
        p2KeyMask = 0; p2AxisMask = 0
        emulatorView.onActivityPause()
        // The loop is stopped now, so the state is a whole frame: resume from it next time.
        if (emulatorView.isRomLoaded && !netGuest) { if (!netLaunch) writeAutosave(); recordStats() }
        super.onPause()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        Net.permissionAnswered(requestCode, results)
    }

    override fun onDestroy() {
        ui.removeCallbacks(netTick)
        // Player 1 leaving the game: both hubs again, the connection stays.
        if (emulatorView.netPlay != null && !netGuest) Net.session?.toLobby()
        emulatorView.netPlay = null
        emulatorView.release()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_GAME = "game"
        const val EXTRA_TITLE = "title"
        const val EXTRA_NET_GUEST = "net_guest"
        const val EXTRA_NET_LAUNCH = "net_launch"
        private const val DEFAULT_GAME = "contraforce"
        private const val TAG = "neshub"
        private const val REQ_PATCH = 41
        private const val KEY_AUTOSAVE_PATCHES = "autosave_patches"
        private const val SLOTS = 4
        private const val KEY_TURBO_A = "turbo_a"
        private const val KEY_TURBO_B = "turbo_b"
        private const val MOD_TOGGLE = 0
        private const val MOD_STAT = 3
        const val CUSTOM_PREFIX = "custom_"
        const val KEY_SAVED_AT = "saved_at"
        const val KEY_PLAY_MS = "play_ms"
        const val KEY_BEST = "best_score"
        const val KEY_LAST_PLAYED = "last_played"
        private const val MOD_STEPPER = 1
        private const val KEY_SHOW_FPS = "show_fps"
        private const val KEY_PAD_P2 = "pad_p2"
        private const val KEY_ALPHA = "overlay_alpha"
        private const val KEY_SIZE = "overlay_size"            // folded / wide screen
        private const val KEY_SIZE_SQUARE = "overlay_size_square" // unfolded
        private const val KEY_HAPTICS = "haptics"
        private const val KEY_CRT = "crt"
        private const val KEY_BLOOM = "crt_bloom"
        /** Frames player 2's pad is sent ahead. It must cover their jitter buffer (2-3 records)
         *  plus the trip: with 5 the log showed it arriving just in time (a 1-frame wait about
         *  every second on player 1). */
        private val NET_DELAY = mapOf(Net.Mode.WIFI to 6, Net.Mode.DIRECT to 8)
        private const val KEY_BEZEL = "bezel"
        private const val KEY_NTSC = "ntsc_signal"
        private const val KEY_PALETTE = "palette"
        /** fceumm_ntsc_filter values (Blargg's nes_ntsc presets) and their names in the menu. */
        private val SIGNALS = listOf("disabled" to "CZYSTY", "composite" to "KOMPOZYT", "svideo" to "S-VIDEO", "rgb" to "RGB")
        /** fceumm_palette values: FCEUmm's built-in palettes, by their authors' names. */
        private val PALETTES = listOf(
            "default" to "FCEUmm", "nes-classic-fbx-fs" to "NES Classic FBX", "smooth-v2-fbx" to "Smooth V2 FBX",
            "composite-direct-fbx" to "Composite FBX", "ntsc-hardware-fbx" to "NTSC HW FBX", "pvm-style-d93-fbx" to "PVM D93 FBX",
            "sony-cxa2025as-us" to "Sony CXA2025AS", "nescap" to "NESCAP", "wavebeam" to "Wavebeam",
            "restored-wii-vc" to "Wii VC", "royaltea" to "PVM-2530", "pal" to "PAL")
        private const val KEY_CRT_CURVED = "crt_curved"     // false: the CRT filter on a flat screen
    }
}
