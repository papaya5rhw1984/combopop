package com.ryu.combopop

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.withFrameNanos
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.ryu.combopop.ui.AppTheme
import com.ryu.combopop.ui.Brand
import kotlin.concurrent.thread
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AppTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF1A1230)) {
                    ComboPopScreen()
                }
            }
        }
    }
}

// ----- constants -----
private const val COLS = 8
private const val TOPGAP = 8f
private const val MAX_STAGE = 100

// palette index -> base color (color-blind-friendly, distinct hues)
private val COLORS = listOf(
    Color(0xFFFF5D7E), // rose
    Color(0xFF6BD66B), // green
    Color(0xFF49B6FF), // sky
    Color(0xFFFFC23D), // amber
    Color(0xFFB57BFF)  // violet
)

private const val AIM_MIN = -Math.PI.toFloat() + 0.18f
private const val AIM_MAX = -0.18f

// ----- sound -----
/**
 * Lightweight runtime tone synthesizer. No asset files. Each sound is rendered
 * to a short PCM buffer and played on a background thread via AudioTrack.
 * Everything is wrapped in try/catch so audio failures never crash gameplay.
 */
private class SoundEngine(prefs: android.content.SharedPreferences) {
    private val sr = 22050
    @Volatile var muted: Boolean = prefs.getBoolean("combopop_muted", false)
        private set
    private val prefsRef = prefs
    @Volatile private var released = false

    fun toggleMute(): Boolean {
        muted = !muted
        try { prefsRef.edit().putBoolean("combopop_muted", muted).apply() } catch (_: Throwable) {}
        return muted
    }

    private fun playTone(freq: Float, durMs: Int, vol: Float, sweep: Float = 1f, square: Boolean = false) {
        if (muted || released) return
        thread(isDaemon = true) {
            var track: AudioTrack? = null
            try {
                val n = (sr * durMs / 1000).coerceAtLeast(64)
                val buf = ShortArray(n)
                val twoPi = (2.0 * Math.PI).toFloat()
                var phase = 0f
                for (i in 0 until n) {
                    val t = i.toFloat() / n
                    val f = freq * (1f + (sweep - 1f) * t)
                    phase += twoPi * f / sr
                    if (phase > twoPi) phase -= twoPi
                    val raw = if (square) (if (sin(phase) >= 0f) 1f else -1f) else sin(phase)
                    // attack-decay envelope
                    val env = if (t < 0.06f) (t / 0.06f) else (1f - (t - 0.06f) / 0.94f)
                    val s = raw * env * vol
                    buf[i] = (s * 32767f).toInt().coerceIn(-32767, 32767).toShort()
                }
                val minBuf = AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
                val sizeBytes = max(minBuf, n * 2)
                track = AudioTrack(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                    AudioFormat.Builder()
                        .setSampleRate(sr)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                    sizeBytes,
                    AudioTrack.MODE_STATIC,
                    AudioManager.AUDIO_SESSION_ID_GENERATE
                )
                track.write(buf, 0, n)
                track.play()
                Thread.sleep((durMs + 40).toLong())
            } catch (_: Throwable) {
                // ignore audio failures
            } finally {
                try { track?.stop() } catch (_: Throwable) {}
                try { track?.release() } catch (_: Throwable) {}
            }
        }
    }

    fun shoot() = playTone(620f, 70, 0.30f, sweep = 1.5f)
    fun snap() = playTone(360f, 60, 0.28f, sweep = 0.8f)
    fun pop(combo: Int) {
        val f = 540f * (1f + (combo - 1) * 0.16f).coerceAtMost(2.2f)
        playTone(f, 90, 0.34f, sweep = 1.35f)
    }
    fun drop() = playTone(280f, 130, 0.26f, sweep = 0.55f)
    fun swap() = playTone(700f, 55, 0.24f, sweep = 1.0f, square = true)
    fun stageClear() {
        playTone(523f, 120, 0.30f)
        thread(isDaemon = true) {
            try { Thread.sleep(110) } catch (_: Throwable) {}
            playTone(659f, 120, 0.30f)
            try { Thread.sleep(110) } catch (_: Throwable) {}
            playTone(784f, 180, 0.32f)
        }
    }
    fun gameOver() {
        playTone(330f, 200, 0.30f, sweep = 0.55f)
        thread(isDaemon = true) {
            try { Thread.sleep(170) } catch (_: Throwable) {}
            playTone(220f, 280, 0.30f, sweep = 0.5f)
        }
    }

    fun release() { released = true }
}

// ----- data -----
private class Bubble(var x: Float, var y: Float, var color: Int)
private class Flying(var x: Float, var y: Float, var vx: Float, var vy: Float, var color: Int) {
    val trail = ArrayList<Offset>(8)
}
private class Particle(
    var x: Float, var y: Float, var vx: Float, var vy: Float,
    val col: Color, var life: Float, val size: Float, val grav: Float
)
private class Floater(var x: Float, var y: Float, val txt: String, var life: Float, var age: Float, val big: Boolean, val col: Color)
private class Ring(val x: Float, val y: Float, val r0: Float, val col: Color, var age: Float, val dur: Float, val maxGrow: Float)
private class Speck(var x: Float, var y: Float, val r: Float, val vy: Float, val phase: Float)
// pop animation for a single bubble: brief expand-then-vanish before particle burst
private class Popper(val x: Float, val y: Float, val col: Color, var age: Float, val r: Float)
// snap squash feedback at landing cell
private class Snap(val x: Float, val y: Float, val col: Color, var age: Float)

private enum class Phase { READY, PLAYING, OVER, CLEAR }

/** Holds all mutable game state. Pixel layout is set once canvas size known. */
private class Game(val prefs: android.content.SharedPreferences, val sound: SoundEngine) {
    // layout
    var W = 0f
    var H = 0f
    var R = 18f
    var CELL = 36f
    var DEAD = 0f
    var HITR2 = 0f
    var laidOut = false

    // grid: rows of nullable color indices
    val grid = ArrayList<Array<Int?>>()
    var parityShift = 0

    var shooter: Bubble? = null
    var nextColor = 0
    var flying: Flying? = null
    var aimAngle = -Math.PI.toFloat() / 2f

    val particles = ArrayList<Particle>()
    val floaters = ArrayList<Floater>()
    val rings = ArrayList<Ring>()
    val poppers = ArrayList<Popper>()
    val specks = ArrayList<Speck>()
    var snap: Snap? = null
    var dashOff = 0f
    var animT = 0f          // global animation clock (seconds-ish)
    var shake = 0f          // screen-shake magnitude
    var shakeX = 0f
    var shakeY = 0f
    var dangerPulse = 0f    // 0..1 near-death red glow strength

    var score = 0
    var best = prefs.getInt("combopop_best", 0)
    var bestCombo = prefs.getInt("combopop_bestcombo", 0)
    var stageNum = 1
    var shotsSinceDrop = 0

    var phase = Phase.READY

    // bump version forces Compose recomposition of HUD numbers
    var hudVersion = 0

    fun layout(w: Float, h: Float) {
        W = w
        CELL = max(30f, (w / COLS))
        R = CELL / 2f
        HITR2 = (CELL * 0.92f) * (CELL * 0.92f)
        H = h
        DEAD = H - CELL * 2.0f
        laidOut = true
        rebuildSpecks()
    }

    private fun rebuildSpecks() {
        specks.clear()
        if (W <= 0f || H <= 0f) return
        val n = 26
        for (i in 0 until n) {
            specks.add(
                Speck(
                    Random.nextFloat() * W,
                    Random.nextFloat() * H,
                    1.5f + Random.nextFloat() * 3.5f,
                    0.15f + Random.nextFloat() * 0.45f,
                    Random.nextFloat() * 6.28f
                )
            )
        }
    }

    // hex grid helpers
    fun rowY(r: Int): Float = TOPGAP + R + r * CELL * 0.9f
    fun evenRow(r: Int): Boolean = ((r + parityShift) % 2 == 0)
    fun colCount(r: Int): Int = if (evenRow(r)) COLS else COLS - 1
    fun cellX(r: Int, c: Int): Float = R + c * CELL + (if (evenRow(r)) 0f else R)

    fun boardWidth(): Float = COLS * CELL

    fun randColorFromBoard(): Int {
        val present = HashSet<Int>()
        for (r in grid.indices) {
            val row = grid[r]
            for (c in 0 until colCount(r)) {
                val v = if (c < row.size) row[c] else null
                if (v != null) present.add(v)
            }
        }
        val pool: List<Int> = if (present.isNotEmpty()) present.toList() else COLORS.indices.toList()
        return pool[Random.nextInt(pool.size)]
    }

    fun newShooter() {
        shooter = Bubble(W / 2f, H - R - 6f, nextColor)
        nextColor = randColorFromBoard()
    }

    fun paletteForStage(): Int = min(3 + (stageNum - 1) / 10, COLORS.size)

    // per-stage background tint hue
    fun stageTint(): Color {
        val hues = listOf(
            Color(0xFF241846), Color(0xFF182a46), Color(0xFF103642),
            Color(0xFF2a1840), Color(0xFF40182f), Color(0xFF18402f)
        )
        return hues[(stageNum - 1) % hues.size]
    }

    fun initGrid() {
        grid.clear()
        val startRows = min(4 + (stageNum - 1) / 6, 10)
        val palette = paletteForStage()
        for (r in 0 until startRows) {
            val cc = colCount(r)
            val row = arrayOfNulls<Int>(cc)
            for (c in 0 until cc) row[c] = Random.nextInt(palette)
            grid.add(row)
        }
    }

    fun start() {
        stageNum = 1
        parityShift = 0
        initGrid()
        score = 0
        particles.clear(); floaters.clear(); rings.clear(); poppers.clear()
        snap = null
        flying = null
        shotsSinceDrop = 0
        shake = 0f; dangerPulse = 0f
        nextColor = randColorFromBoard()
        newShooter()
        aimAngle = -Math.PI.toFloat() / 2f
        phase = Phase.PLAYING
        hudVersion++
    }

    private fun addShake(amt: Float) { shake = min(shake + amt, 22f) }

    // ----- aiming -----
    fun setAimFrom(px: Float, py: Float) {
        val s = shooter ?: return
        val dx = px - s.x
        val dy = py - s.y
        var ang = atan2(dy, dx)
        if (ang > AIM_MAX) ang = AIM_MAX
        if (ang < AIM_MIN) ang = AIM_MIN
        aimAngle = ang
    }

    fun nextChipPos(): Offset {
        val s = shooter ?: return Offset(W - R - 6f, H)
        return Offset(W - R - 6f, s.y - R * 0.7f + R * 0.9f)
    }

    fun isOnNextChip(px: Float, py: Float): Boolean {
        if (shooter == null || flying != null) return false
        val p = nextChipPos()
        val rr = R * 1.4f
        val dx = px - p.x; val dy = py - p.y
        return dx * dx + dy * dy < rr * rr
    }

    // 발사 대기 중인 슈터 구슬을 탭했는지 (탭 시 현재 조준 방향 유지 발사용)
    fun isOnShooter(px: Float, py: Float): Boolean {
        val s = shooter ?: return false
        if (flying != null) return false
        val rr = R * 2.2f
        val dx = px - s.x; val dy = py - s.y
        return dx * dx + dy * dy < rr * rr
    }

    fun swapColors() {
        if (phase != Phase.PLAYING || flying != null) return
        val s = shooter ?: return
        val t = s.color; s.color = nextColor; nextColor = t
        val p = nextChipPos()
        rings.add(Ring(p.x, p.y, R * 0.5f, Color(0xFFB57BFF), 0f, 300f, R * 2f))
        sound.swap()
    }

    fun fire() {
        if (flying != null || phase != Phase.PLAYING) return
        val s = shooter ?: return
        val sp = 17f
        flying = Flying(s.x, s.y, cos(aimAngle) * sp, sin(aimAngle) * sp, s.color)
        s.color = nextColor
        nextColor = randColorFromBoard()
        sound.shoot()
    }

    // ----- collision -----
    fun collidesAt(x: Float, y: Float): Boolean {
        val rc = Math.round((y - TOPGAP - R) / (CELL * 0.9f))
        val r0 = max(0, rc - 2)
        val r1 = min(grid.size - 1, rc + 2)
        var r = r0
        while (r <= r1) {
            val row = grid[r]
            val cc = colCount(r)
            for (c in 0 until cc) {
                val v = if (c < row.size) row[c] else null
                if (v == null) continue
                val cx = cellX(r, c); val cy = rowY(r)
                val dx = cx - x; val dy = cy - y
                if (dx * dx + dy * dy < HITR2) return true
            }
            r++
        }
        return false
    }

    // reflection prediction path
    fun predictPath(): Pair<List<Offset>, Offset> {
        val s = shooter ?: return Pair(emptyList(), Offset.Zero)
        var x = s.x; var y = s.y
        var vx = cos(aimAngle); var vy = sin(aimAngle)
        val pts = ArrayList<Offset>()
        pts.add(Offset(x, y))
        val step = R * 0.7f
        var i = 0
        while (i < 600) {
            x += vx * step; y += vy * step
            if (x < R) { x = R; vx = -vx; pts.add(Offset(x, y)) }
            else if (x > boardWidth() - R) { x = boardWidth() - R; vx = -vx; pts.add(Offset(x, y)) }
            var hit = collidesAt(x, y)
            if (y <= R + TOPGAP) hit = true
            if (hit) { pts.add(Offset(x, y)); return Pair(pts, Offset(x, y)) }
            i++
        }
        pts.add(Offset(x, y))
        return Pair(pts, Offset(x, y))
    }

    private fun getCell(r: Int, c: Int): Int? {
        if (r < 0 || r >= grid.size) return null
        val row = grid[r]
        if (c < 0 || c >= row.size) return null
        return row[c]
    }

    private fun setCell(r: Int, c: Int, v: Int?) {
        if (r < 0 || r >= grid.size) return
        val row = grid[r]
        if (c < 0 || c >= row.size) return
        row[c] = v
    }

    fun ensureRow(r: Int) {
        while (grid.size <= r) {
            val cc = colCount(grid.size)
            grid.add(arrayOfNulls<Int>(cc))
        }
    }

    fun neighbors(r: Int, c: Int): List<Pair<Int, Int>> {
        val odd = !evenRow(r)
        val list = ArrayList<Pair<Int, Int>>(6)
        list.add(Pair(r, c - 1)); list.add(Pair(r, c + 1))
        if (odd) {
            list.add(Pair(r - 1, c)); list.add(Pair(r - 1, c + 1))
            list.add(Pair(r + 1, c)); list.add(Pair(r + 1, c + 1))
        } else {
            list.add(Pair(r - 1, c - 1)); list.add(Pair(r - 1, c))
            list.add(Pair(r + 1, c - 1)); list.add(Pair(r + 1, c))
        }
        return list.filter { (rr, cc) -> rr >= 0 && rr < grid.size && cc >= 0 && cc < colCount(rr) }
    }

    fun matchCluster(r: Int, c: Int, color: Int): List<Pair<Int, Int>> {
        val seen = HashSet<Long>()
        val stack = ArrayList<Pair<Int, Int>>()
        stack.add(Pair(r, c))
        val out = ArrayList<Pair<Int, Int>>()
        while (stack.isNotEmpty()) {
            val (rr, cc) = stack.removeAt(stack.size - 1)
            val k = rr.toLong() * 100000L + cc
            if (seen.contains(k)) continue
            seen.add(k)
            val v = getCell(rr, cc)
            if (v == null || v != color) continue
            out.add(Pair(rr, cc))
            for (n in neighbors(rr, cc)) stack.add(n)
        }
        return out
    }

    fun settleFlying() {
        val f = flying ?: return
        val fx = f.x; val fy = f.y; val color = f.color
        flying = null
        ensureRow(grid.size) // scan empty bottom row
        var bestR = -1; var bestC = -1; var bd = Float.MAX_VALUE
        for (r in grid.indices) {
            val cc = colCount(r)
            for (c in 0 until cc) {
                if (getCell(r, c) != null) continue
                var ok = (r == 0)
                if (!ok) {
                    for ((nr, nc) in neighbors(r, c)) {
                        if (getCell(nr, nc) != null) { ok = true; break }
                    }
                }
                if (!ok) continue
                val cx = cellX(r, c); val cy = rowY(r)
                val d = (cx - fx) * (cx - fx) + (cy - fy) * (cy - fy)
                if (d < bd) { bd = d; bestR = r; bestC = c }
            }
        }
        if (bestR < 0) {
            val n = nearestEmptyCell(fx, fy)
            bestR = n.first; bestC = n.second
            ensureRow(bestR)
        }
        ensureRow(bestR)
        setCell(bestR, bestC, color)
        snap = Snap(cellX(bestR, bestC), rowY(bestR), COLORS[color], 0f)
        sound.snap()
        resolve(bestR, bestC, color)
        if (phase == Phase.PLAYING) {
            shotsSinceDrop++
            if (shotsSinceDrop >= dropEvery()) { shotsSinceDrop = 0; dropCeiling() }
        }
    }

    fun nearestEmptyCell(x: Float, y: Float): Pair<Int, Int> {
        var bestR = 0; var bestC = 0; var bd = Float.MAX_VALUE
        for (r in 0..grid.size) {
            val cc = colCount(r)
            for (c in 0 until cc) {
                val cx = cellX(r, c); val cy = rowY(r)
                val d = (cx - x) * (cx - x) + (cy - y) * (cy - y)
                if (d < bd) { bd = d; bestR = r; bestC = c }
            }
        }
        return Pair(bestR, bestC)
    }

    fun floatingGroups(): List<List<Pair<Int, Int>>> {
        val anchored = HashSet<Long>()
        val stack = ArrayList<Pair<Int, Int>>()
        if (grid.isNotEmpty()) {
            for (c in 0 until colCount(0)) if (getCell(0, c) != null) stack.add(Pair(0, c))
        }
        while (stack.isNotEmpty()) {
            val (r, c) = stack.removeAt(stack.size - 1)
            val k = r.toLong() * 100000L + c
            if (anchored.contains(k)) continue
            if (getCell(r, c) == null) continue
            anchored.add(k)
            for (n in neighbors(r, c)) stack.add(n)
        }
        val seen = HashSet<Long>()
        val groups = ArrayList<List<Pair<Int, Int>>>()
        for (r in grid.indices) {
            for (c in 0 until colCount(r)) {
                if (getCell(r, c) == null) continue
                val k = r.toLong() * 100000L + c
                if (anchored.contains(k) || seen.contains(k)) continue
                val comp = ArrayList<Pair<Int, Int>>()
                val st = ArrayList<Pair<Int, Int>>()
                st.add(Pair(r, c))
                while (st.isNotEmpty()) {
                    val (rr, cc) = st.removeAt(st.size - 1)
                    val kk = rr.toLong() * 100000L + cc
                    if (seen.contains(kk) || anchored.contains(kk)) continue
                    if (getCell(rr, cc) == null) continue
                    seen.add(kk); comp.add(Pair(rr, cc))
                    for (n in neighbors(rr, cc)) st.add(n)
                }
                if (comp.isNotEmpty()) groups.add(comp)
            }
        }
        return groups
    }

    fun dropGroup(cells: List<Pair<Int, Int>>) {
        for ((r, c) in cells) {
            val cx = cellX(r, c); val cy = rowY(r)
            val ci = getCell(r, c) ?: continue
            val col = COLORS[ci]
            setCell(r, c, null)
            // falling chunk: gravity pull + fade
            for (i in 0 until 7) {
                val a = Random.nextFloat() * Math.PI.toFloat() * 2f
                val sp = 0.6f + Random.nextFloat() * 2.2f
                particles.add(
                    Particle(cx, cy, cos(a) * sp, sin(a) * sp + 2.4f, col, 1f, 4f + Random.nextFloat() * 3f, 0.32f)
                )
            }
        }
        capParticles()
    }

    fun popCells(cells: List<Pair<Int, Int>>, comboMul: Int) {
        val burst = (8 + comboMul * 3).coerceAtMost(18)
        for ((r, c) in cells) {
            val cx = cellX(r, c); val cy = rowY(r)
            val ci = getCell(r, c) ?: continue
            val col = COLORS[ci]
            setCell(r, c, null)
            poppers.add(Popper(cx, cy, col, 0f, R))
            rings.add(Ring(cx, cy, R * 0.4f, col, 0f, 320f, R * (2.0f + comboMul * 0.3f)))
            for (i in 0 until burst) {
                val a = Random.nextFloat() * Math.PI.toFloat() * 2f
                val sp = 1.2f + Random.nextFloat() * (3f + comboMul * 0.6f)
                particles.add(
                    Particle(cx, cy, cos(a) * sp, sin(a) * sp, col, 1f, 3f + Random.nextFloat() * 3.5f, 0.12f)
                )
            }
        }
        capParticles()
    }

    private fun capParticles() {
        val cap = 380
        if (particles.size > cap) {
            val remove = particles.size - cap
            for (i in 0 until remove) particles.removeAt(0)
        }
    }

    fun resolve(r: Int, c: Int, color: Int) {
        val cluster = matchCluster(r, c, color)
        if (cluster.size >= 3) {
            var combo = 1
            var gained = 0
            popCells(cluster, combo)
            gained += cluster.size * 10 * combo
            score += cluster.size * 10 * combo
            sound.pop(combo)
            addShake(min(2f + cluster.size * 0.6f, 9f))
            var groups = floatingGroups()
            var guard = 0
            while (groups.isNotEmpty() && guard++ < 40) {
                combo++
                var total = 0
                for (g in groups) { total += g.size; dropGroup(g) }
                gained += total * 15 * combo
                score += total * 15 * combo
                sound.drop()
                addShake(min(total * 0.5f, 8f))
                if (total > 0) {
                    floaters.add(
                        Floater(W / 2f, H * 0.5f + combo * 10f, "DROP x$combo!", 1f, 0f, false, Color(0xFFFFD24D))
                    )
                }
                groups = floatingGroups()
            }
            if (combo > 1) {
                addShake(4f + combo * 1.5f)
                floaters.add(Floater(W / 2f, H * 0.4f, "COMBO x$combo!", 1f, 0f, true, Color(0xFFFF6F9C)))
                floaters.add(Floater(cellX(r, c), rowY(r), "+$gained", 1f, 0f, false, Color.White))
            } else {
                floaters.add(Floater(cellX(r, c), rowY(r), "+$gained", 1f, 0f, false, Color.White))
            }
            if (score > best) { best = score; prefs.edit().putInt("combopop_best", best).apply() }
            if (combo > bestCombo) { bestCombo = combo; prefs.edit().putInt("combopop_bestcombo", bestCombo).apply() }
            hudVersion++
        }
        compactRows()
        if (isBoardEmpty()) { nextStage(); return }
        checkLose()
    }

    fun compactRows() {
        while (grid.isNotEmpty()) {
            val last = grid[grid.size - 1]
            if (last.all { it == null }) grid.removeAt(grid.size - 1) else break
        }
    }

    fun isBoardEmpty(): Boolean {
        for (r in grid.indices) for (c in 0 until colCount(r)) if (getCell(r, c) != null) return false
        return true
    }

    fun nextStage() {
        // 무한 스테이지 — 상한 없음. 10단계마다 마일스톤 보너스.
        stageNum++
        val milestone = stageNum % 10 == 0
        val banner = if (milestone) "STAGE $stageNum  ★" else "STAGE $stageNum"
        floaters.add(Floater(W / 2f, H * 0.42f, banner, 1f, 0f, true, if (milestone) Color(0xFFFFD24D) else Color(0xFF8AE6FF)))
        rings.add(Ring(W / 2f, H * 0.42f, 10f, Color(0xFFFF8FB6), 0f, 700f, R * 6f))
        rings.add(Ring(W / 2f, H * 0.42f, 10f, Color(0xFF8AE6FF), 0f, 760f, R * 8f))
        addShake(if (milestone) 11f else 7f)
        sound.stageClear()
        if (milestone) {
            val bonus = stageNum * 50
            score += bonus
            floaters.add(Floater(W / 2f, H * 0.52f, "보너스 +$bonus", 1f, 0f, false, Color(0xFFFFD24D)))
            rings.add(Ring(W / 2f, H * 0.42f, 10f, Color(0xFFFFD24D), 0f, 820f, R * 10f))
            if (score > best) { best = score; prefs.edit().putInt("combopop_best", best).apply() }
        }
        // celebration confetti (마일스톤이면 더 많이)
        val confetti = if (milestone) 70 else 40
        for (i in 0 until confetti) {
            val a = Random.nextFloat() * Math.PI.toFloat() * 2f
            val sp = 2f + Random.nextFloat() * 5f
            particles.add(
                Particle(
                    W / 2f, H * 0.42f, cos(a) * sp, sin(a) * sp, COLORS[Random.nextInt(COLORS.size)],
                    1f, 4f + Random.nextFloat() * 4f, 0.14f
                )
            )
        }
        capParticles()
        shotsSinceDrop = 0
        parityShift = 0
        initGrid()
        // 새 보드에 존재하는 색으로 발사/대기 버블 갱신 (안 터지는 색 방지)
        nextColor = randColorFromBoard()
        shooter?.let { it.color = randColorFromBoard() }
        aimAngle = -Math.PI.toFloat() / 2f
        hudVersion++
    }

    fun dropEvery(): Int = max(3, 12 - (stageNum - 1) / 8)

    fun dropCeiling() {
        parityShift = parityShift xor 1
        val palette = paletteForStage()
        val cc = colCount(0)
        val row = arrayOfNulls<Int>(cc)
        for (c in 0 until cc) row[c] = Random.nextInt(palette)
        grid.add(0, row)
        rings.add(Ring(W / 2f, rowY(0), R, Color(0xFFFF6F8C), 0f, 380f, R * 3f))
        addShake(5f)
        sound.drop()
        checkLose()
    }

    fun gameClear() {
        phase = Phase.CLEAR
        addShake(10f)
        sound.stageClear()
        if (score > best) { best = score; prefs.edit().putInt("combopop_best", best).apply() }
        hudVersion++
    }

    fun checkLose() {
        for (r in grid.indices) for (c in 0 until colCount(r)) {
            if (getCell(r, c) != null && rowY(r) + R >= DEAD) { gameOver(); return }
        }
    }

    fun gameOver() {
        phase = Phase.OVER
        addShake(14f)
        sound.gameOver()
        if (score > best) { best = score; prefs.edit().putInt("combopop_best", best).apply() }
        hudVersion++
    }

    // lowest occupied y; used for near-death pulse
    fun lowestBubbleY(): Float {
        var lo = 0f
        for (r in grid.indices) for (c in 0 until colCount(r)) {
            if (getCell(r, c) != null) { val y = rowY(r) + R; if (y > lo) lo = y }
        }
        return lo
    }

    // ----- per-frame update -----
    fun update(dt: Float) {
        animT += dt / 60f
        // screen shake decay + jitter
        if (shake > 0.05f) {
            shakeX = (Random.nextFloat() - 0.5f) * shake
            shakeY = (Random.nextFloat() - 0.5f) * shake
            shake *= (1f - 0.18f * dt)
            if (shake < 0.05f) { shake = 0f; shakeX = 0f; shakeY = 0f }
        } else { shakeX = 0f; shakeY = 0f }

        // near-death pulse
        val lo = lowestBubbleY()
        val proximity = if (DEAD > 0f) ((lo - (DEAD - CELL * 2.2f)) / (CELL * 2.2f)).coerceIn(0f, 1f) else 0f
        dangerPulse = proximity

        flying?.let { f ->
            // trail
            f.trail.add(Offset(f.x, f.y))
            if (f.trail.size > 7) f.trail.removeAt(0)
            val steps = 2
            var s = 0
            while (s < steps) {
                f.x += f.vx * dt / steps
                f.y += f.vy * dt / steps
                if (f.x < R) {
                    f.x = R; f.vx *= -1f
                    rings.add(Ring(R, f.y, R * 0.3f, Color.White, 0f, 220f, R * 1.2f))
                }
                if (f.x > boardWidth() - R) {
                    f.x = boardWidth() - R; f.vx *= -1f
                    rings.add(Ring(boardWidth() - R, f.y, R * 0.3f, Color.White, 0f, 220f, R * 1.2f))
                }
                var hit = collidesAt(f.x, f.y)
                if (f.y <= R + TOPGAP) hit = true
                if (hit) { settleFlying(); break }
                s++
            }
        }
        var i = particles.size - 1
        while (i >= 0) {
            val p = particles[i]
            p.x += p.vx * dt; p.y += p.vy * dt; p.vy += p.grav * dt; p.life -= 0.03f * dt
            if (p.life <= 0f) particles.removeAt(i)
            i--
        }
        i = floaters.size - 1
        while (i >= 0) {
            val fl = floaters[i]
            fl.age += dt / 60f
            fl.y -= 0.6f * dt
            fl.life = 1f - fl.age / 1.0f
            if (fl.life <= 0f) floaters.removeAt(i)
            i--
        }
        i = rings.size - 1
        while (i >= 0) {
            val ring = rings[i]
            ring.age += dt * 16.67f
            if (ring.age >= ring.dur) rings.removeAt(i)
            i--
        }
        i = poppers.size - 1
        while (i >= 0) {
            val pp = poppers[i]
            pp.age += dt / 60f
            if (pp.age >= 0.18f) poppers.removeAt(i)
            i--
        }
        snap?.let { sn ->
            sn.age += dt / 60f
            if (sn.age >= 0.22f) snap = null
        }
        // floating specks drift upward, wrap
        for (sp in specks) {
            sp.y -= sp.vy * dt
            if (sp.y < -6f) { sp.y = H + 6f; sp.x = Random.nextFloat() * W }
        }
        dashOff = (dashOff + 0.6f * dt) % 24f
    }
}

@Composable
private fun ComboPopScreen() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { context.getSharedPreferences("combopop", Context.MODE_PRIVATE) }
    val sound = remember { SoundEngine(prefs) }
    val game = remember { Game(prefs, sound) }
    var paused by remember { mutableStateOf(false) }
    var muted by remember { mutableStateOf(sound.muted) }

    // tick state to drive recomposition each frame
    var tick by remember { mutableStateOf(0L) }

    // pause when app backgrounded; release audio when disposed
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP || event == Lifecycle.Event.ON_PAUSE) {
                if (game.phase == Phase.PLAYING) paused = true
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            sound.release()
        }
    }

    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                if (last == 0L) last = now
                var dt = (now - last) / 16_666_666f
                last = now
                if (dt > 3f) dt = 3f
                if (game.phase == Phase.PLAYING && !paused && game.laidOut) game.update(dt)
            }
            tick++
        }
    }

    Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
        @Suppress("UNUSED_EXPRESSION") game.hudVersion // read so header recomposes
        val hv = game.hudVersion
        Header(
            game, hv, muted,
            onMute = { muted = sound.toggleMute() },
            onPause = { if (game.phase == Phase.PLAYING) paused = !paused }
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 4.dp),
            contentAlignment = Alignment.Center
        ) {
            GameCanvas(game, paused, tick) { paused = !paused }
            if (game.phase != Phase.PLAYING) {
                Overlay(game) { game.start(); paused = false }
            } else if (paused) {
                PauseOverlay { paused = false }
            }
        }
        Footer()
    }
}

@Composable
private fun Header(
    game: Game,
    @Suppress("UNUSED_PARAMETER") hudVersion: Int,
    muted: Boolean,
    onMute: () -> Unit,
    onPause: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0x33000000))
            .padding(horizontal = 14.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text("Combo", color = Color(0xFFFF6F9C), fontWeight = FontWeight.Black, fontSize = 18.sp)
            Spacer(Modifier.width(3.dp))
            Text("Pop", color = Color(0xFF8AE6FF), fontWeight = FontWeight.Black, fontSize = 18.sp)
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            StatCol("스테이지", "${game.stageNum}")
            StatCol("점수", "${game.score}")
            StatCol("콤보", "x${game.bestCombo}")
            StatCol("최고", "${game.best}")
            // mute toggle (text glyphs only — these are UI text, not Canvas)
            Box(
                modifier = Modifier
                    .background(Color(0x33FFFFFF), RoundedCornerShape(999.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
                    .pointerInput(Unit) { detectTapGestures(onTap = { onMute() }) }
            ) {
                Text(if (muted) "🔇" else "🔊", fontSize = 15.sp)
            }
            Box(
                modifier = Modifier
                    .background(Color(0x33FFFFFF), RoundedCornerShape(999.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
                    .pointerInput(Unit) { detectTapGestures(onTap = { onPause() }) }
            ) {
                Text("⏸", fontSize = 15.sp, color = Color.White)
            }
        }
    }
}

@Composable
private fun StatCol(label: String, value: String) {
    Column(horizontalAlignment = Alignment.End) {
        Text(label, color = Color(0xFFB8A8C8), fontSize = 9.sp, fontWeight = FontWeight.Bold)
        Text(value, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Black)
    }
}

@Composable
private fun Footer() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0x22000000))
            .padding(vertical = 5.dp),
        contentAlignment = Alignment.Center
    ) {
        Text("FlipShoot Studio · 콤보 팝 · 오프라인 플레이", color = Color(0xFF9A8AB8), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun GameCanvas(game: Game, paused: Boolean, tick: Long, onTogglePause: () -> Unit) {
    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { off ->
                        if (game.phase == Phase.PLAYING && !paused) {
                            when {
                                game.isOnNextChip(off.x, off.y) -> game.swapColors()
                                // 슈터 구슬을 탭하면 현재 조준 방향 그대로 발사
                                game.isOnShooter(off.x, off.y) -> game.fire()
                                // 그 외(보드 영역) 탭은 그 지점으로 조준 후 발사
                                else -> { game.setAimFrom(off.x, off.y); game.fire() }
                            }
                        }
                    }
                )
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { off ->
                        if (game.phase == Phase.PLAYING && !paused && !game.isOnNextChip(off.x, off.y)) {
                            game.setAimFrom(off.x, off.y)
                        }
                    },
                    onDrag = { change, _ ->
                        if (game.phase == Phase.PLAYING && !paused) {
                            game.setAimFrom(change.position.x, change.position.y)
                        }
                        change.consume()
                    },
                    onDragEnd = {
                        if (game.phase == Phase.PLAYING && !paused) game.fire()
                    }
                )
            }
    ) {
        if (!game.laidOut || game.W != size.width || game.H != size.height) {
            game.layout(size.width, size.height)
            game.shooter?.let { it.x = game.W / 2f; it.y = game.H - game.R - 6f }
        }
        @Suppress("UNUSED_EXPRESSION") tick // ensure redraw each frame
        translate(game.shakeX, game.shakeY) {
            drawGame(game)
        }
    }
}

private fun DrawScope.drawGame(game: Game) {
    val W = game.W; val H = game.H; val R = game.R
    if (W <= 0f || H <= 0f) return

    drawBackground(game)

    // grid bubbles (subtle idle shimmer via per-cell phase)
    for (r in game.grid.indices) {
        val row = game.grid[r]
        for (c in 0 until game.colCount(r)) {
            val v = if (c < row.size) row[c] else null
            if (v != null) {
                val cx = game.cellX(r, c); val cy = game.rowY(r)
                val shimmer = 0.5f + 0.5f * sin(game.animT * 1.6f + (r * 0.7f + c * 0.5f))
                drawBubble(cx, cy, v, 1f, R, shimmer)
            }
        }
    }

    drawDeadline(game)

    // aim prediction (glowing dashed trajectory + landing ring)
    if (game.phase == Phase.PLAYING && game.flying == null && game.shooter != null) {
        val (pts, target) = game.predictPath()
        if (pts.size >= 2) {
            val aimCol = COLORS[game.shooter!!.color]
            drawDashedPath(pts, aimCol, game.dashOff, R)
            // landing ring preview
            val k = 0.5f + 0.5f * sin(game.animT * 4f)
            drawCircle(
                color = aimCol.copy(alpha = 0.35f + k * 0.25f),
                radius = R - 2f,
                center = target,
                style = Stroke(width = max(2f, R * 0.14f))
            )
            drawCircle(color = aimCol.copy(alpha = 0.12f), radius = R - 3f, center = target)
        }
    }

    // flying bubble + trail
    game.flying?.let { f ->
        val n = f.trail.size
        for (idx in 0 until n) {
            val t = f.trail[idx]
            val a = (idx + 1f) / (n + 1f)
            drawCircle(color = COLORS[f.color].copy(alpha = 0.10f + a * 0.22f), radius = R * (0.5f + a * 0.45f), center = t)
        }
        drawBubble(f.x, f.y, f.color, 1f, R, 0.5f)
    }

    drawShooterAndNext(game)

    // poppers (brief expand flash before particles)
    for (pp in game.poppers) {
        val k = (pp.age / 0.18f).coerceIn(0f, 1f)
        val rr = pp.r * (1f + k * 0.55f)
        drawCircle(color = pp.col.copy(alpha = (1f - k) * 0.85f), radius = rr, center = Offset(pp.x, pp.y))
        drawCircle(color = Color.White.copy(alpha = (1f - k) * 0.5f), radius = rr * 0.5f, center = Offset(pp.x, pp.y))
    }

    // particles
    for (p in game.particles) {
        val a = max(0f, min(1f, p.life))
        drawCircle(color = p.col.copy(alpha = a), radius = p.size * a, center = Offset(p.x, p.y))
    }

    // rings (expanding)
    for (ring in game.rings) {
        val k = ring.age / ring.dur
        if (k >= 1f) continue
        drawCircle(
            color = ring.col.copy(alpha = (1f - k) * 0.8f),
            radius = ring.r0 + k * ring.maxGrow,
            center = Offset(ring.x, ring.y),
            style = Stroke(width = max(1.5f, R * 0.12f))
        )
    }

    // snap squash feedback
    game.snap?.let { sn ->
        val k = (sn.age / 0.22f).coerceIn(0f, 1f)
        val rr = R * (1f + 0.2f * sin(k * Math.PI.toFloat()))
        drawCircle(color = sn.col.copy(alpha = (1f - k) * 0.4f), radius = rr, center = Offset(sn.x, sn.y), style = Stroke(width = max(2f, R * 0.14f)))
    }

    // ceiling-drop HUD
    if (game.phase == Phase.PLAYING) {
        val remain = max(0, game.dropEvery() - game.shotsSinceDrop)
        drawContext.canvas.nativeCanvas.apply {
            val paint = android.graphics.Paint().apply {
                color = if (remain <= 1) android.graphics.Color.parseColor("#FF7A8F")
                else android.graphics.Color.parseColor("#C6B8E0")
                textAlign = android.graphics.Paint.Align.LEFT
                textSize = 12f * 2f
                isFakeBoldText = true
                isAntiAlias = true
                setShadowLayer(4f, 0f, 1f, android.graphics.Color.argb(150, 0, 0, 0))
            }
            drawText("천장까지 ${remain}발", 13f, 30f, paint)
        }
    }

    // floaters
    drawFloaters(game)
}

private fun DrawScope.drawBackground(game: Game) {
    val W = game.W; val H = game.H
    val tint = game.stageTint()
    // vertical gradient base
    val grad = androidx.compose.ui.graphics.Brush.verticalGradient(
        colors = listOf(
            shade(tint, 0.10f),
            tint,
            shade(tint, -0.30f)
        ),
        startY = 0f, endY = H
    )
    drawRect(brush = grad, size = size)

    // soft animated glow blob near top
    val gx = W * (0.5f + 0.18f * sin(game.animT * 0.5f))
    val gy = H * 0.22f
    val glow = androidx.compose.ui.graphics.Brush.radialGradient(
        colors = listOf(Color.White.copy(alpha = 0.08f), Color.Transparent),
        center = Offset(gx, gy),
        radius = W * 0.7f
    )
    drawRect(brush = glow, size = size)

    // floating decorative specks
    for (sp in game.specks) {
        val tw = 0.4f + 0.4f * sin(game.animT * 2f + sp.phase)
        drawCircle(color = Color.White.copy(alpha = 0.06f + tw * 0.10f), radius = sp.r, center = Offset(sp.x, sp.y))
    }

    // near-death red wash
    if (game.dangerPulse > 0.02f && game.phase == Phase.PLAYING) {
        val pulse = 0.5f + 0.5f * sin(game.animT * 7f)
        val a = game.dangerPulse * (0.10f + pulse * 0.14f)
        drawRect(color = Color(0xFFFF2D55).copy(alpha = a), size = size)
    }
}

private fun DrawScope.drawDeadline(game: Game) {
    val W = game.W
    val danger = game.dangerPulse
    val pulse = 0.5f + 0.5f * sin(game.animT * 7f)
    val bandAlpha = 0.16f + danger * (0.25f + pulse * 0.3f)
    val lineCol = if (danger > 0.4f) Color(0xFFFF3B5C) else Color(0xFFFF6F8C)
    // warning band
    drawRect(
        color = lineCol.copy(alpha = bandAlpha),
        topLeft = Offset(0f, game.DEAD - 11f),
        size = androidx.compose.ui.geometry.Size(W, 22f)
    )
    // glow when near
    if (danger > 0.3f) {
        drawRect(
            color = Color(0xFFFF3B5C).copy(alpha = danger * (0.10f + pulse * 0.18f)),
            topLeft = Offset(0f, game.DEAD - 40f),
            size = androidx.compose.ui.geometry.Size(W, 80f)
        )
    }
    // dashed line
    val dashLen = 12f; val gap = 12f
    var x = -game.dashOff
    while (x < W) {
        val x2 = min(x + dashLen, W)
        if (x2 > 0f) drawLine(
            color = lineCol.copy(alpha = 0.9f),
            start = Offset(max(0f, x), game.DEAD),
            end = Offset(x2, game.DEAD),
            strokeWidth = 2.5f + danger * 2f
        )
        x += dashLen + gap
    }
}

private fun DrawScope.drawDashedPath(pts: List<Offset>, col: Color, dashOff: Float, R: Float) {
    // build a dashed-along-path effect by stepping fixed distances
    val dash = 14f; val gap = 10f
    var carry = dashOff % (dash + gap)
    var drawing = carry < dash
    var seg = if (drawing) dash - carry else (dash + gap) - carry
    val glowW = max(2f, R * 0.22f)
    for (i in 0 until pts.size - 1) {
        var ax = pts[i].x; var ay = pts[i].y
        val bx = pts[i + 1].x; val by = pts[i + 1].y
        var dx = bx - ax; var dy = by - ay
        var len = sqrt(dx * dx + dy * dy)
        if (len < 0.001f) continue
        val ux = dx / len; val uy = dy / len
        var remaining = len
        while (remaining > 0f) {
            val take = min(seg, remaining)
            val nx = ax + ux * take; val ny = ay + uy * take
            if (drawing) {
                // glow underlay + bright core
                drawLine(color = col.copy(alpha = 0.18f), start = Offset(ax, ay), end = Offset(nx, ny), strokeWidth = glowW)
                drawLine(color = col.copy(alpha = 0.85f), start = Offset(ax, ay), end = Offset(nx, ny), strokeWidth = max(1.5f, R * 0.1f))
            }
            ax = nx; ay = ny
            remaining -= take
            seg -= take
            if (seg <= 0.001f) {
                drawing = !drawing
                seg = if (drawing) dash else gap
            }
        }
    }
}

private fun DrawScope.drawShooterAndNext(game: Game) {
    val R = game.R
    game.shooter?.let { s ->
        if (game.phase == Phase.PLAYING && game.flying == null) {
            // pulsing base ring
            val pulse = 0.5f + 0.5f * sin(game.animT * 3.6f)
            drawCircle(
                color = COLORS[s.color].copy(alpha = 0.16f + pulse * 0.22f),
                radius = R + 3f + pulse * 5f,
                center = Offset(s.x, s.y),
                style = Stroke(width = max(2f, R * 0.16f))
            )
            drawCircle(
                color = Color.White.copy(alpha = 0.10f + pulse * 0.12f),
                radius = R + 8f + pulse * 4f,
                center = Offset(s.x, s.y),
                style = Stroke(width = max(1.5f, R * 0.08f))
            )
        }
        drawBubble(s.x, s.y, s.color, 1f, R, 0.5f)
        val chip = game.nextChipPos()
        // next chip pulsing ring
        val cp = 0.5f + 0.5f * sin(game.animT * 2.4f)
        drawCircle(
            color = COLORS[game.nextColor].copy(alpha = 0.12f + cp * 0.14f),
            radius = R * 0.9f + cp * 3f,
            center = Offset(chip.x, chip.y),
            style = Stroke(width = max(1.5f, R * 0.1f))
        )
        drawBubble(chip.x, chip.y, game.nextColor, 0.85f, R * 0.82f, 0.5f)
        drawContext.canvas.nativeCanvas.apply {
            val paint = android.graphics.Paint().apply {
                color = android.graphics.Color.parseColor("#D8C8EE")
                textAlign = android.graphics.Paint.Align.CENTER
                textSize = 9f * 2f
                isFakeBoldText = true
                isAntiAlias = true
            }
            drawText("NEXT", chip.x, s.y - R * 0.8f, paint)
        }
    }
}

private fun DrawScope.drawFloaters(game: Game) {
    val R = game.R
    for (f in game.floaters) {
        val alpha = max(0f, f.life)
        // pop-in scale ease for big text
        val scale = if (f.big) {
            val a = (f.age / 0.18f).coerceIn(0f, 1f)
            0.6f + 0.4f * a
        } else 1f
        drawContext.canvas.nativeCanvas.apply {
            val size = if (f.big) R * 1.5f * scale else 18f * 2f
            val cr = (f.col.red * 255).toInt(); val cg = (f.col.green * 255).toInt(); val cb = (f.col.blue * 255).toInt()
            val stroke = android.graphics.Paint().apply {
                color = android.graphics.Color.argb((alpha * 235).toInt(), 20, 14, 40)
                textAlign = android.graphics.Paint.Align.CENTER
                textSize = size
                isFakeBoldText = true
                isAntiAlias = true
                style = android.graphics.Paint.Style.STROKE
                strokeWidth = if (f.big) 6f else 3.5f
            }
            val fill = android.graphics.Paint().apply {
                color = android.graphics.Color.argb((alpha * 255).toInt(), cr, cg, cb)
                textAlign = android.graphics.Paint.Align.CENTER
                textSize = size
                isFakeBoldText = true
                isAntiAlias = true
                setShadowLayer(if (f.big) 10f else 5f, 0f, 0f, android.graphics.Color.argb((alpha * 180).toInt(), cr, cg, cb))
            }
            drawText(f.txt, f.x, f.y, stroke)
            drawText(f.txt, f.x, f.y, fill)
        }
    }
}

private fun shade(c: Color, amt: Float): Color {
    val f = if (amt < 0f) 0f else 1f
    val p = kotlin.math.abs(amt)
    val r = c.red + (f - c.red) * p
    val g = c.green + (f - c.green) * p
    val b = c.blue + (f - c.blue) * p
    return Color(r.coerceIn(0f, 1f), g.coerceIn(0f, 1f), b.coerceIn(0f, 1f), c.alpha)
}

/** Glossy candy bubble: soft shadow, radial gradient body, specular highlight, outline, shimmer. */
private fun DrawScope.drawBubble(x: Float, y: Float, colorIdx: Int, alpha: Float, R: Float, shimmer: Float) {
    val col = COLORS[colorIdx]
    val rr = R - 1.5f
    // soft drop shadow
    drawCircle(color = Color.Black.copy(alpha = 0.22f * alpha), radius = rr, center = Offset(x + R * 0.10f, y + R * 0.16f))
    // radial gradient body (light from upper-left)
    val grad = androidx.compose.ui.graphics.Brush.radialGradient(
        colors = listOf(shade(col, 0.45f), col, shade(col, -0.30f)),
        center = Offset(x - R * 0.28f, y - R * 0.30f),
        radius = R * 1.55f
    )
    drawCircle(brush = grad, radius = rr, center = Offset(x, y), alpha = alpha)
    // subtle shimmer overlay
    if (shimmer > 0f) {
        drawCircle(color = Color.White.copy(alpha = 0.06f * shimmer * alpha), radius = rr, center = Offset(x, y))
    }
    // outline
    drawCircle(
        color = shade(col, -0.50f).copy(alpha = 0.85f * alpha),
        radius = rr,
        center = Offset(x, y),
        style = Stroke(width = max(2f, R * 0.12f))
    )
    // inner rim light (bottom)
    drawCircle(
        color = Color.White.copy(alpha = 0.18f * alpha),
        radius = rr - R * 0.1f,
        center = Offset(x, y + R * 0.06f),
        style = Stroke(width = max(1f, R * 0.07f))
    )
    // glossy specular highlight
    drawCircle(
        color = Color.White.copy(alpha = 0.72f * alpha),
        radius = R * 0.24f,
        center = Offset(x - R * 0.27f, y - R * 0.34f)
    )
    // small sparkle
    drawCircle(
        color = Color.White.copy(alpha = 0.55f * alpha),
        radius = R * 0.09f,
        center = Offset(x + R * 0.30f, y - R * 0.06f)
    )
    // per-color symbol (subtle, aids color distinction / accessibility)
    drawColorSymbol(x, y, colorIdx, R, 0.40f * alpha)
}

/** Faint white glyph unique per color index — readable even for color-blind players. */
private fun DrawScope.drawColorSymbol(x: Float, y: Float, colorIdx: Int, R: Float, alpha: Float) {
    if (alpha <= 0.02f) return
    val col = Color.White.copy(alpha = alpha)
    val s = R * 0.34f
    val w = max(1.5f, R * 0.12f)
    when (colorIdx) {
        0 -> drawCircle(color = col, radius = s * 0.66f, center = Offset(x, y))                 // dot
        1 -> drawCircle(color = col, radius = s, center = Offset(x, y), style = Stroke(width = w)) // ring
        2 -> drawRect(                                                                            // square
            color = col,
            topLeft = Offset(x - s * 0.85f, y - s * 0.85f),
            size = androidx.compose.ui.geometry.Size(s * 1.7f, s * 1.7f)
        )
        3 -> {                                                                                   // plus
            drawLine(color = col, start = Offset(x - s, y), end = Offset(x + s, y), strokeWidth = w)
            drawLine(color = col, start = Offset(x, y - s), end = Offset(x, y + s), strokeWidth = w)
        }
        else -> {                                                                                // X
            drawLine(color = col, start = Offset(x - s, y - s), end = Offset(x + s, y + s), strokeWidth = w)
            drawLine(color = col, start = Offset(x - s, y + s), end = Offset(x + s, y - s), strokeWidth = w)
        }
    }
}

@Composable
private fun Overlay(game: Game, onStart: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xF21A1230)),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(20.dp)) {
            when (game.phase) {
                Phase.OVER -> {
                    Text("게임 오버", color = Color(0xFFFF7A8F), fontSize = 32.sp, fontWeight = FontWeight.Black)
                    Spacer(Modifier.height(10.dp))
                    Text("점수 ${game.score}", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(2.dp))
                    Text("스테이지 ${game.stageNum} 도달 · 최고 콤보 x${game.bestCombo}", color = Color(0xFFC6B8E0), fontSize = 14.sp)
                    Text("최고 점수 ${game.best}", color = Color(0xFFC6B8E0), fontSize = 14.sp)
                }
                Phase.CLEAR -> {
                    Text("CLEAR!", color = Color(0xFF8AE6FF), fontSize = 34.sp, fontWeight = FontWeight.Black)
                    Spacer(Modifier.height(10.dp))
                    Text("100 스테이지 정복!", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(2.dp))
                    Text("점수 ${game.score} · 최고 콤보 x${game.bestCombo}", color = Color(0xFFC6B8E0), fontSize = 14.sp)
                    Text("최고 점수 ${game.best}", color = Color(0xFFC6B8E0), fontSize = 14.sp)
                }
                else -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Combo", color = Color(0xFFFF6F9C), fontSize = 34.sp, fontWeight = FontWeight.Black)
                        Spacer(Modifier.width(6.dp))
                        Text("Pop", color = Color(0xFF8AE6FF), fontSize = 34.sp, fontWeight = FontWeight.Black)
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "버블을 쏴서 같은 색 3개 이상을 터뜨리세요.\n연쇄로 터질수록 콤보 배수 폭발!",
                        color = Color(0xFFD8C8EE), fontSize = 14.sp, fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "드래그로 조준 · 떼면 발사 · 탭으로 발사\nNEXT 버블 탭 = 색 교체\n여러 번 쏘면 천장이 내려와요\n버블이 바닥선에 닿으면 게임 오버 · 끝없는 스테이지 도전!",
                        color = Color(0xFFA89AC8), fontSize = 12.sp
                    )
                }
            }
            Spacer(Modifier.height(18.dp))
            Button(
                onClick = onStart,
                shape = RoundedCornerShape(999.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Brand.Accent)
            ) {
                Text(
                    if (game.phase == Phase.READY) "시작하기" else "다시 하기",
                    color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Black
                )
            }
        }
    }
}

@Composable
private fun PauseOverlay(onResume: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xC60C0812))
            .pointerInput(Unit) { detectTapGestures(onTap = { onResume() }) },
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("일시정지", color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Black)
            Spacer(Modifier.height(8.dp))
            Text("탭하면 계속하기", color = Color(0xFFC9B8E0), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}
