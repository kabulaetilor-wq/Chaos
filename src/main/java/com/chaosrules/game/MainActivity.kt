package com.chaosrules.game

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

class MainActivity : Activity() {
    private lateinit var game: GameView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        game = GameView(this)
        setContentView(game)
        hideBars()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideBars()
    }

    override fun onPause() {
        super.onPause()
        game.pauseGame()
    }

    override fun onResume() {
        super.onResume()
        game.resumeGame()
    }

    @Suppress("DEPRECATION")
    private fun hideBars() {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
    }
}

/** Les règles qui peuvent s'activer (une ou plusieurs en même temps) pendant la partie. */
enum class Rule(val label: String, val hint: String) {
    INVERSE("COMMANDES INVERSÉES", "Ton doigt va à gauche, tu vas à droite"),
    SWAP("RÔLES ÉCHANGÉS", "Les ronds font mal, les carrés rapportent"),
    GRAVITY("GRAVITÉ RENVERSÉE", "Tout monte depuis le bas"),
    DARK("NOIR TOTAL", "Tu ne vois qu'autour de toi"),
    TURBO("TURBO", "Tout va beaucoup plus vite"),
    DRUNK("VERTIGE", "Ton vaisseau dérive tout seul")
}

class GameView(ctx: Context) : View(ctx) {

    private class Item(
        var x: Float, var y: Float, val r: Float, val kind: Int, // 0 rond, 1 carré, 2 coeur
        val zig: Float, val homing: Boolean, val phase: Float
    ) {
        var age = 0f
    }

    private class Pop(val x: Float, var y: Float, val s: String, var life: Float, val c: Int)

    private val cyan = Color.rgb(0, 229, 255)
    private val pink = Color.rgb(255, 46, 147)
    private val gold = Color.rgb(255, 214, 10)
    private val green = Color.rgb(57, 255, 20)
    private val red = Color.rgb(255, 59, 48)
    private val bg = Color.rgb(10, 10, 26)

    private val prefs = ctx.getSharedPreferences("chaos", Context.MODE_PRIVATE)
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD }
    private val path = Path()
    private val items = ArrayList<Item>()
    private val pops = ArrayList<Pop>()

    private var w = 0f
    private var h = 0f
    private var state = 0 // 0 menu, 1 jeu, 2 game over
    private var running = true
    private var lastNs = 0L

    private var t = 0f
    private var pts = 0
    private var surv = 0f
    private var streak = 0
    private var lives = 3
    private var best = prefs.getInt("best", 0)
    private var newRecord = false
    private var invuln = 0f
    private var flash = 0f
    private var hitFlash = 0f
    private var shake = 0f
    private var spawnTimer = 0f
    private var scroll = 0f
    private var overT = 0f
    private var menuT = 0f

    private var rules: Set<Rule> = emptySet()
    private var nextRules: Set<Rule> = emptySet()
    private var ruleTimer = 8f
    private var warned = false
    private var bannerT = 0f

    private var px = 0f
    private var py = 0f
    private var touchX = 0f

    private val pr: Float get() = w * 0.045f
    private val dir: Float get() = if (Rule.GRAVITY in rules) -1f else 1f
    private val combo: Int get() = min(8, 1 + streak / 3)
    private val total: Int get() = pts + surv.toInt()

    override fun onSizeChanged(nw: Int, nh: Int, ow: Int, oh: Int) {
        w = nw.toFloat()
        h = nh.toFloat()
        px = w / 2f
        py = h * 0.86f
        touchX = px
    }

    fun pauseGame() {
        running = false
    }

    fun resumeGame() {
        lastNs = 0L
        running = true
        invalidate()
    }

    private fun reset() {
        items.clear()
        pops.clear()
        t = 0f; pts = 0; surv = 0f; streak = 0; lives = 3
        invuln = 0f; flash = 0f; hitFlash = 0f; shake = 0f
        spawnTimer = 0.5f; scroll = 0f
        rules = emptySet(); nextRules = emptySet()
        ruleTimer = 8f; warned = false; bannerT = 0f
        newRecord = false
        px = w / 2f; py = h * 0.86f
        state = 1
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (state == 0 || (state == 2 && overT > 0.7f)) reset()
                touchX = e.x
            }
            MotionEvent.ACTION_MOVE -> touchX = e.x
        }
        return true
    }

    // ------------------------------------------------------------------ logique

    private fun pickNext(): Set<Rule> {
        var res: Set<Rule>
        do {
            res = if (rules.isNotEmpty() && Random.nextFloat() < 0.2f) {
                emptySet()
            } else {
                val n = when {
                    t > 90f && Random.nextFloat() < 0.4f -> 3
                    t > 40f && Random.nextFloat() < 0.6f -> 2
                    else -> 1
                }
                Rule.values().toList().shuffled().take(n).toSet()
            }
        } while (res == rules)
        return res
    }

    private fun applyRules() {
        val wasGravity = Rule.GRAVITY in rules
        rules = nextRules
        if (wasGravity != (Rule.GRAVITY in rules)) items.clear()
        invuln = max(invuln, 1.2f)
        flash = 0.5f
        bannerT = 2.5f
        warned = false
        ruleTimer = max(5f, 11f - t * 0.04f) + Random.nextFloat() * 2.5f
    }

    private fun hit() {
        lives--
        streak = 0
        shake = 0.5f
        hitFlash = 0.4f
        invuln = 1.0f
        if (lives <= 0) endGame()
    }

    private fun endGame() {
        state = 2
        overT = 0f
        if (total > best) {
            best = total
            newRecord = true
            prefs.edit().putInt("best", best).apply()
        }
    }

    private fun spawn(d: Float) {
        val r = w * (0.035f + Random.nextFloat() * 0.03f)
        val roll = Random.nextFloat()
        val kind = when {
            roll < 0.03f && lives < 3 -> 2
            roll < 0.38f -> 0
            else -> 1
        }
        val homing = kind != 2 && t > 25f && Random.nextFloat() < min(0.35f, 0.1f + (t - 25f) / 200f)
        val zig = if (kind != 2 && t > 12f && Random.nextFloat() < 0.3f) w * (0.08f + Random.nextFloat() * 0.1f) else 0f
        val x = r + Random.nextFloat() * (w - 2 * r)
        val y = if (d > 0) -r else h + r
        items.add(Item(x, y, r, kind, zig, homing, Random.nextFloat() * 6.28f))
    }

    private fun spawnWall(d: Float) {
        val n = 9
        val cell = w / n
        val r = cell / 2f * 0.95f
        val gap = Random.nextInt(n - 2)
        val y = if (d > 0) -r else h + r
        for (i in 0 until n) {
            if (i == gap || i == gap + 1) continue
            items.add(Item((i + 0.5f) * cell, y, r, 1, 0f, false, 0f))
        }
    }

    private fun update(dt: Float) {
        t += dt
        surv += dt * 3f
        invuln = max(0f, invuln - dt)
        flash = max(0f, flash - dt)
        hitFlash = max(0f, hitFlash - dt)
        shake = max(0f, shake - dt)
        bannerT = max(0f, bannerT - dt)

        ruleTimer -= dt
        if (!warned && ruleTimer <= 2.2f) {
            nextRules = pickNext()
            warned = true
        }
        if (ruleTimer <= 0f) applyRules()

        val d = dir
        val swap = Rule.SWAP in rules

        // vaisseau
        val targetY = if (d > 0) h * 0.86f else h * 0.14f
        py += (targetY - py) * min(1f, dt * 6f)
        if (Rule.DRUNK in rules) {
            px += (sin(t * 1.9f) * 0.9f + sin(t * 4.3f) * 0.5f) * w * 0.35f * dt
        }
        val target = if (Rule.INVERSE in rules) w - touchX else touchX
        val maxStep = w * 1.7f * dt
        px += max(-maxStep, min(maxStep, target - px))
        px = max(pr, min(w - pr, px))

        // vitesse
        val diff = min(2.4f, 1f + t / 70f)
        var speed = h * 0.30f * diff
        if (Rule.TURBO in rules) speed *= 1.7f
        scroll += d * speed * dt

        // apparitions
        spawnTimer -= dt
        if (spawnTimer <= 0f) {
            var iv = max(0.2f, 0.65f - t * 0.004f)
            if (Rule.TURBO in rules) iv /= 1.5f
            spawnTimer = iv * (0.7f + Random.nextFloat() * 0.6f)
            if (t > 18f && Random.nextFloat() < 0.07f) spawnWall(d) else spawn(d)
        }

        // objets
        var i = items.size - 1
        while (i >= 0) {
            val o = items[i]
            o.age += dt
            o.y += d * speed * dt * (if (o.kind == 2) 0.8f else 1f)
            if (o.zig > 0f) o.x += cos(o.phase + o.age * 3f) * o.zig * 3f * dt
            if (o.homing && abs(o.y - py) > h * 0.22f) {
                val dx = px - o.x
                val step = w * (0.08f + 0.04f * diff) * dt
                o.x += max(-step, min(step, dx))
            }
            o.x = max(o.r, min(w - o.r, o.x))

            if (o.y > h + o.r * 2 || o.y < -o.r * 2) {
                items.removeAt(i); i--; continue
            }
            if (hypot(o.x - px, o.y - py) < o.r + pr * 0.75f) {
                val good = when (o.kind) {
                    2 -> true
                    0 -> !swap
                    else -> swap
                }
                if (good) {
                    if (o.kind == 2) {
                        lives = min(3, lives + 1)
                        pts += 25
                        pops.add(Pop(o.x, o.y, "+1 VIE", 0.9f, gold))
                    } else {
                        streak++
                        val gain = 10 * combo
                        pts += gain
                        pops.add(Pop(o.x, o.y, "+$gain", 0.7f, green))
                    }
                    items.removeAt(i)
                } else if (invuln <= 0f) {
                    items.removeAt(i)
                    hit()
                }
            }
            i--
        }

        val pi = pops.iterator()
        while (pi.hasNext()) {
            val pp = pi.next()
            pp.life -= dt
            pp.y -= h * 0.08f * dt
            if (pp.life <= 0f) pi.remove()
        }
    }

    // ------------------------------------------------------------------ dessin

    override fun onDraw(c: Canvas) {
        val now = System.nanoTime()
        val dt = if (lastNs == 0L) 0f else min(0.05f, (now - lastNs) / 1e9f)
        lastNs = now
        if (running) {
            when (state) {
                1 -> update(dt)
                2 -> overT += dt
                else -> menuT += dt
            }
        }
        render(c)
        if (running) postInvalidateOnAnimation()
    }

    private fun txt(
        c: Canvas, s: String, x: Float, y: Float, size: Float, color: Int,
        align: Paint.Align = Paint.Align.CENTER, alpha: Int = 255
    ) {
        text.textSize = size
        text.color = color
        text.alpha = alpha
        text.textAlign = align
        c.drawText(s, x, y, text)
    }

    private fun diamond(c: Canvas, x: Float, y: Float, r: Float) {
        path.reset()
        path.moveTo(x, y - r)
        path.lineTo(x + r, y)
        path.lineTo(x, y + r)
        path.lineTo(x - r, y)
        path.close()
        c.drawPath(path, p)
    }

    private fun glow(c: Canvas, x: Float, y: Float, r: Float, col: Int) {
        p.style = Paint.Style.FILL
        p.color = col
        p.alpha = 50
        c.drawCircle(x, y, r, p)
        p.alpha = 255
    }

    private fun drawGrid(c: Canvas) {
        val sp = h / 10f
        val off = ((scroll % sp) + sp) % sp
        p.style = Paint.Style.STROKE
        p.strokeWidth = 2f
        p.color = Color.WHITE
        p.alpha = 22
        for (k in -1..11) c.drawLine(0f, off + k * sp, w, off + k * sp, p)
        for (k in 0..8) c.drawLine(k * w / 8f, 0f, k * w / 8f, h, p)
        p.alpha = 255
        p.style = Paint.Style.FILL
    }

    private fun render(c: Canvas) {
        c.drawColor(bg)
        c.save()
        if (shake > 0f) {
            c.translate((Random.nextFloat() - 0.5f) * w * 0.1f * shake, (Random.nextFloat() - 0.5f) * w * 0.1f * shake)
        }
        drawGrid(c)

        if (state != 0) {
            for (o in items) {
                when (o.kind) {
                    0 -> {
                        glow(c, o.x, o.y, o.r * 1.8f, cyan)
                        p.color = cyan
                        c.drawCircle(o.x, o.y, o.r, p)
                    }
                    1 -> {
                        glow(c, o.x, o.y, o.r * 1.8f, pink)
                        p.color = pink
                        c.save()
                        c.rotate(o.age * 90f, o.x, o.y)
                        c.drawRect(o.x - o.r, o.y - o.r, o.x + o.r, o.y + o.r, p)
                        c.restore()
                    }
                    else -> {
                        glow(c, o.x, o.y, o.r * 1.8f, gold)
                        p.color = gold
                        diamond(c, o.x, o.y, o.r * 1.1f)
                    }
                }
            }

            val show = invuln <= 0f || ((invuln * 14f).toInt() % 2 == 0)
            if (show) {
                val tip = if (dir > 0) -1f else 1f
                glow(c, px, py, pr * 2f, Color.WHITE)
                path.reset()
                path.moveTo(px, py + tip * pr * 1.2f)
                path.lineTo(px - pr, py - tip * pr * 0.9f)
                path.lineTo(px, py - tip * pr * 0.5f)
                path.lineTo(px + pr, py - tip * pr * 0.9f)
                path.close()
                p.color = Color.WHITE
                c.drawPath(path, p)
            }
            for (pp in pops) {
                txt(c, pp.s, pp.x, pp.y, w * 0.05f, pp.c, alpha = min(255, (pp.life * 400).toInt()))
            }
        }
        c.restore()

        if (state == 1 && Rule.DARK in rules) {
            p.shader = RadialGradient(
                px, py, w * 0.28f,
                intArrayOf(Color.argb(0, 0, 0, 0), Color.argb(0, 0, 0, 0), Color.BLACK),
                floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP
            )
            c.drawRect(0f, 0f, w, h, p)
            p.shader = null
        }

        if (flash > 0f) {
            p.color = Color.WHITE
            p.alpha = min(255, (flash * 200f).toInt())
            c.drawRect(0f, 0f, w, h, p)
            p.alpha = 255
        }
        if (hitFlash > 0f) {
            p.color = red
            p.alpha = min(255, (hitFlash * 400f).toInt())
            c.drawRect(0f, 0f, w, h, p)
            p.alpha = 255
        }

        when (state) {
            0 -> drawMenu(c)
            1 -> {
                drawHud(c)
                drawBanner(c)
            }
            else -> drawGameOver(c)
        }
    }

    private fun drawHud(c: Canvas) {
        val y = h * 0.055f
        txt(c, "$total", w * 0.04f, y, w * 0.07f, Color.WHITE, Paint.Align.LEFT)
        if (combo > 1) txt(c, "x$combo", w * 0.04f, y + w * 0.07f, w * 0.045f, gold, Paint.Align.LEFT)

        val swap = Rule.SWAP in rules
        val y1 = y - w * 0.015f
        val y2 = y1 + w * 0.06f
        p.style = Paint.Style.FILL
        p.color = cyan
        c.drawCircle(w * 0.43f, y1, w * 0.02f, p)
        txt(c, if (swap) "DANGER" else "BON", w * 0.47f, y1 + w * 0.013f, w * 0.036f, if (swap) red else green, Paint.Align.LEFT)
        p.color = pink
        c.drawRect(w * 0.43f - w * 0.02f, y2 - w * 0.02f, w * 0.43f + w * 0.02f, y2 + w * 0.02f, p)
        txt(c, if (swap) "BON" else "DANGER", w * 0.47f, y2 + w * 0.013f, w * 0.036f, if (swap) green else red, Paint.Align.LEFT)

        for (k in 0..2) {
            p.color = gold
            p.style = if (k < lives) Paint.Style.FILL else Paint.Style.STROKE
            p.strokeWidth = 3f
            diamond(c, w * (0.93f - k * 0.06f), y - w * 0.015f, w * 0.022f)
        }
        p.style = Paint.Style.FILL
        txt(c, "NIV ${1 + (t / 20f).toInt()}", w * 0.96f, y + w * 0.045f, w * 0.035f, Color.WHITE, Paint.Align.RIGHT, 180)
    }

    private fun drawRuleList(c: Canvas, set: Set<Rule>, y0: Float, alpha: Int) {
        if (set.isEmpty()) {
            txt(c, "RÈGLES NORMALES", w / 2, y0, w * 0.065f, green, alpha = alpha)
            txt(c, "Ronds = bons, carrés = danger", w / 2, y0 + w * 0.06f, w * 0.038f, Color.WHITE, alpha = alpha)
            return
        }
        var y = y0
        for (r in set) {
            txt(c, r.label, w / 2, y, w * 0.062f, pink, alpha = alpha)
            txt(c, r.hint, w / 2, y + w * 0.055f, w * 0.037f, Color.WHITE, alpha = alpha)
            y += w * 0.13f
        }
    }

    private fun drawBanner(c: Canvas) {
        val cy = h * 0.38f
        if (warned && ruleTimer > 0f) {
            txt(c, "CHANGEMENT DANS ${ceil(ruleTimer).toInt()}", w / 2, cy, w * 0.058f, gold)
            drawRuleList(c, nextRules, cy + w * 0.11f, 230)
        } else if (bannerT > 0f) {
            drawRuleList(c, rules, cy, min(255, (bannerT * 255f).toInt()))
        }
    }

    private fun drawMenu(c: Canvas) {
        txt(c, "CHAOS RULES", w / 2, h * 0.24f, w * 0.13f, cyan)
        txt(c, "Glisse ton doigt pour te déplacer", w / 2, h * 0.36f, w * 0.045f, Color.WHITE)
        txt(c, "Ronds = bons  •  Carrés = danger", w / 2, h * 0.41f, w * 0.045f, Color.WHITE)
        txt(c, "MAIS LES RÈGLES CHANGENT...", w / 2, h * 0.50f, w * 0.05f, pink)
        txt(c, "Surveille l'écran : un avertissement", w / 2, h * 0.55f, w * 0.04f, Color.WHITE, alpha = 200)
        txt(c, "apparaît 2 secondes avant chaque changement.", w / 2, h * 0.585f, w * 0.04f, Color.WHITE, alpha = 200)
        txt(c, "3 vies. Bonne chance.", w / 2, h * 0.65f, w * 0.045f, gold)
        val a = (160 + 95 * sin(menuT * 4f)).toInt()
        txt(c, "TOUCHE POUR JOUER", w / 2, h * 0.78f, w * 0.06f, green, alpha = a)
        txt(c, "Record : $best", w / 2, h * 0.86f, w * 0.045f, Color.WHITE)
    }

    private fun drawGameOver(c: Canvas) {
        p.color = Color.BLACK
        p.alpha = 160
        c.drawRect(0f, 0f, w, h, p)
        p.alpha = 255
        txt(c, "GAME OVER", w / 2, h * 0.30f, w * 0.12f, red)
        txt(c, "Score : $total", w / 2, h * 0.42f, w * 0.075f, Color.WHITE)
        if (newRecord) txt(c, "NOUVEAU RECORD !", w / 2, h * 0.50f, w * 0.06f, gold)
        else txt(c, "Record : $best", w / 2, h * 0.50f, w * 0.05f, Color.WHITE)
        txt(c, "Niveau atteint : ${1 + (t / 20f).toInt()}", w / 2, h * 0.57f, w * 0.045f, Color.WHITE, alpha = 200)
        if (overT > 0.7f) {
            val a = (160 + 95 * sin(overT * 4f)).toInt()
            txt(c, "TOUCHE POUR REJOUER", w / 2, h * 0.72f, w * 0.06f, green, alpha = a)
        }
    }
}
