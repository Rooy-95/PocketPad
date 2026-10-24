package com.dathaze.pocketpad.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.max
import android.os.SystemClock
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import com.dathaze.pocketpad.hid.GamepadState
import com.dathaze.pocketpad.hid.HidConstants
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Full-screen touch gamepad with switchable skins:
 *
 *  - [Skin.PS]      — full PS4/PS5-style pad: d-pad, △○✕□, dual sticks,
 *                     L1/L2/R1/R2, L3/R3, SHARE/OPTIONS and a central PS
 *                     home button (press-and-hold works like the real thing).
 *  - [Skin.SNES]    — Super Nintendo: d-pad, X/A/B/Y in SNES colors, L/R,
 *                     SELECT/START, home.
 *  - [Skin.NES]     — NES: d-pad, B/A, SELECT/START, home.
 *  - [Skin.GAMEBOY] — Game Boy: d-pad, B/A (diagonal), SELECT/START, home.
 *
 * Every skin drives the same HID gamepad report, positionally mapped, so a
 * host (TV, tablet, emulator) sees one consistent controller no matter the
 * skin: bottom face = button 1 (Cross/B), right face = button 2 (Circle/A),
 * left face = button 3 (Square/Y), top face = button 4 (Triangle/X).
 *
 * The view draws and hit-tests every control itself so multitouch works
 * naturally. Layout adapts to portrait/landscape in [onSizeChanged].
 *
 * MOD: in layout edit mode an "AJUSTES" pill (top centre) opens a small panel
 * to change button size, stick/d-pad size, stick dead zone and sensitivity.
 */
class ControllerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    enum class Skin { PS, XBOX, SNES, NES, GAMEBOY }

    /**
     * Extra pad designs (PSP and the Sega consoles). They are chosen from the
     * AJUSTES panel in layout edit mode and, when not NONE, replace the skin.
     */
    enum class Extra(val title: String) {
        NONE("Ninguno"),
        PSP("PSP"),
        MASTER_SYSTEM("Master Sys"),
        MEGADRIVE3("MegaDrive 3"),
        MEGADRIVE6("MegaDrive 6"),
        SATURN("Saturn"),
        DREAMCAST("Dreamcast"),
        GAMEGEAR("Game Gear")
    }

    /** Connection state shown by the halo around the PS/home button. */
    enum class LinkState { IDLE, DISCOVERABLE, CONNECTED }

    interface Listener {
        /** Touch state changed — read [touchState] and send a report. */
        fun onTouchStateChanged()
        fun onOpenSettings()
        /** PS/home button held for ~0.7 s while not connected. */
        fun onHomeLongPress()
    }

    var listener: Listener? = null

    /** Live state produced by the touch controls. */
    val touchState = GamepadState()

    /** Haptic feedback engine; intensity is user-configurable. */
    val haptics = Haptics(context)

    /** Drives the halo around the PS/home button. */
    var linkState: LinkState = LinkState.IDLE
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    var skin: Skin = Skin.PS
        set(value) {
            if (field == value) return
            field = value
            if (extra != Extra.NONE && value.name != extraBase) {
                extra = Extra.NONE
                saveExtra()
            }
            releaseAllPointers()
            applySkin()
            if (width > 0 && height > 0) layoutControls(width.toFloat(), height.toFloat())
            invalidate()
        }

    // ---------------------------------------------------------------- controls

    private abstract inner class Control {
        var cx = 0f
        var cy = 0f
        var radius = 0f
        /** Radius before the user's size setting is applied. */
        var baseRadius = 0f
        var pointerId = -1
        val pressed: Boolean get() = pointerId != -1

        open fun contains(x: Float, y: Float): Boolean =
            hypot((x - cx).toDouble(), (y - cy).toDouble()) <= radius * 1.15

        open fun onDown(x: Float, y: Float) {}
        open fun onMove(x: Float, y: Float) {}
        open fun onUp() {}
        abstract fun draw(canvas: Canvas)

        // -------- molded-plastic rendering ------------------------------
        // Shaders depend on geometry, so they are rebuilt only when a control
        // moves or resizes — not on every frame.

        private var shaderX = Float.NaN
        private var shaderY = Float.NaN
        private var shaderR = Float.NaN
        private var capRaised: LinearGradient? = null
        private var capSunken: LinearGradient? = null

        private fun ensureShaders() {
            if (cx == shaderX && cy == shaderY && radius == shaderR) return
            shaderX = cx; shaderY = cy; shaderR = radius
            // Light falls from above: lit crown, shadowed skirt. Pressed caps
            // invert, so the top edge reads as the deepest part of the dish.
            capRaised = LinearGradient(
                cx, cy - radius, cx, cy + radius, capLit, capShade, Shader.TileMode.CLAMP
            )
            capSunken = LinearGradient(
                cx, cy - radius, cx, cy + radius, capPressedDeep, capPressedRise,
                Shader.TileMode.CLAMP
            )
        }

        /**
         * Draws a physical-looking button: a recessed socket, a drop shadow,
         * a gradient cap, and a bevel that is bright along the top edge and
         * dark along the bottom. Pressing sinks the cap into the socket, kills
         * the shadow and flips the bevel — the same cues a real controller
         * gives your eye.
         */
        fun drawMoldedCap(canvas: Canvas, r: Float, rimColor: Int) {
            ensureShaders()
            val sink = if (pressed) r * 0.07f else 0f
            val capY = cy + sink

            // Socket the cap sits in.
            moldPaint.shader = null
            moldPaint.color = socketColor
            canvas.drawCircle(cx, cy + r * 0.04f, r * 1.14f, moldPaint)
            bevelPaint.strokeWidth = r * 0.08f
            bevelPaint.color = withAlpha(Color.BLACK, 140)
            arcRect(cx, cy + r * 0.04f, r * 1.10f)
            canvas.drawArc(tmpRect, 190f, 160f, false, bevelPaint)
            bevelPaint.color = withAlpha(Color.WHITE, 22)
            canvas.drawArc(tmpRect, 10f, 160f, false, bevelPaint)

            // Cast shadow — only while the cap stands proud of the socket.
            if (!pressed) {
                moldPaint.color = withAlpha(Color.BLACK, 90)
                canvas.drawCircle(cx, capY + r * 0.10f, r * 0.98f, moldPaint)
            }

            // The cap itself.
            moldPaint.shader = if (pressed) capSunken else capRaised
            canvas.drawCircle(cx, capY, r, moldPaint)
            moldPaint.shader = null

            // Bevelled edge.
            arcRect(cx, capY, r * 0.955f)
            bevelPaint.strokeWidth = r * 0.10f
            bevelPaint.color = withAlpha(Color.WHITE, if (pressed) 18 else 66)
            canvas.drawArc(tmpRect, 186f, 150f, false, bevelPaint)
            bevelPaint.color = withAlpha(Color.BLACK, if (pressed) 130 else 85)
            canvas.drawArc(tmpRect, 6f, 150f, false, bevelPaint)

            // Coloured ring for face/home buttons.
            if (rimColor != 0) {
                strokePaint.color = withAlpha(rimColor, if (pressed) 255 else 165)
                canvas.drawCircle(cx, capY, r * 0.98f, strokePaint)
            }

            // Specular glint on the crown.
            if (!pressed) {
                arcRect(cx, capY - r * 0.10f, r * 0.62f)
                bevelPaint.strokeWidth = r * 0.13f
                bevelPaint.color = withAlpha(Color.WHITE, 30)
                canvas.drawArc(tmpRect, 205f, 105f, false, bevelPaint)
            }
        }

        /** Vertical offset the cap currently sits at (for labels). */
        fun capSink(r: Float): Float = if (pressed) r * 0.07f else 0f
    }

    /**
     * Round momentary button bound to one HID button bit. Held as long as the
     * finger stays down — so a long press on PS/home behaves exactly like
     * holding the button on a physical controller.
     */
    private inner class PadButton(
        var label: String,
        val buttonIndex: Int,
        var labelColor: Int = textColor,
        var labelScale: Float = 1f,
        val rimmed: Boolean = false,
        val isHome: Boolean = false
    ) : Control() {
        private val longPressRunnable = Runnable {
            if (linkState != LinkState.CONNECTED) {
                haptics.press()
                listener?.onHomeLongPress()
            }
        }

        // Every tap must reach the game as a real press followed by a real release.
        // - A press is held for at least minPressMs, so a very short tap cannot start
        //   and end inside one emulator frame.
        // - There is a gap of at least gapMs between a release and the next press, so
        //   mashing the button is seen as many separate presses, not one long one.
        private val minPressMs = 50L
        private val gapMs = 20L
        private var held = false          // what the host currently sees
        private var heldAt = 0L
        private var lastReleaseAt = 0L
        private var wantDown = false      // finger is on the button
        private var pendingPress = false  // a press still has to be delivered
        private val syncRunnable = Runnable { sync() }

        private fun sync() {
            removeCallbacks(syncRunnable)
            val now = SystemClock.uptimeMillis()
            if (held && (!wantDown || pendingPress)) {
                val wait = minPressMs - (now - heldAt)
                if (wait > 0) {
                    postDelayed(syncRunnable, wait)
                    return
                }
                held = false
                lastReleaseAt = now
                touchState.setButton(buttonIndex, false)
                setTrigger(0)
                notifyChanged()
            }
            if (!held && pendingPress) {
                val wait = gapMs - (now - lastReleaseAt)
                if (wait > 0) {
                    postDelayed(syncRunnable, wait)
                    return
                }
                held = true
                heldAt = now
                pendingPress = false
                touchState.setButton(buttonIndex, true)
                setTrigger(255)
                notifyChanged()
            }
            if (held && !wantDown) postDelayed(syncRunnable, minPressMs)
        }

        override fun onDown(x: Float, y: Float) {
            wantDown = true
            pendingPress = true
            // Transmit first, buzz second. The vibrator call takes a moment to
            // return, and doing it first delays the press reaching the host.
            sync()
            haptics.press()
            if (isHome) postDelayed(longPressRunnable, 700)
        }

        override fun onUp() {
            if (isHome) removeCallbacks(longPressRunnable)
            wantDown = false
            sync()
        }

        /** L2/R2 are triggers, so they drive the analog axis as well as the
         *  button bit — games that read a throttle need the axis. */
        private fun setTrigger(value: Int) {
            when (buttonIndex) {
                HidConstants.BTN_L2 -> touchState.lt = value
                HidConstants.BTN_R2 -> touchState.rt = value
            }
        }

        override fun draw(canvas: Canvas) {
            val tint = if (rimmed) labelColor else accentColor
            if (isHome) drawHalo(canvas)
            if (pressed) {
                glowPaint.color = tint
                glowPaint.alpha = 70
                canvas.drawCircle(cx, cy, radius * 1.20f, glowPaint)
            }
            drawMoldedCap(canvas, radius, if (rimmed) labelColor else 0)

            // Engraved label: a dark offset copy under the glyph makes it read
            // as stamped into the plastic rather than printed on top.
            val capY = cy + capSink(radius)
            textPaint.textSize = radius * 0.72f * labelScale
            val baseline = capY - (textPaint.ascent() + textPaint.descent()) / 2f
            textPaint.color = withAlpha(Color.BLACK, 120)
            canvas.drawText(label, cx, baseline + radius * 0.035f, textPaint)
            textPaint.color = if (pressed) brighten(labelColor) else labelColor
            canvas.drawText(label, cx, baseline, textPaint)
        }

        /**
         * Neon status halo around the PS/home button, like a controller's
         * light ring: faint when idle, pulsing electric cyan while the phone
         * is discoverable, steady neon green once connected. A dark separator
         * ring underneath keeps it readable on any background.
         */
        private fun drawHalo(canvas: Canvas) {
            haloPaint.strokeWidth = radius * 0.09f
            haloPaint.color = Color.BLACK
            haloPaint.alpha = 150
            canvas.drawCircle(cx, cy, radius * 1.14f, haloPaint)
            when (linkState) {
                LinkState.CONNECTED -> neonRing(canvas, neonGreen, 1f)
                LinkState.DISCOVERABLE -> {
                    val t = (SystemClock.uptimeMillis() % 1400L) / 1400f
                    neonRing(canvas, neonCyan, 0.35f + 0.65f * abs(sin(t * PI)).toFloat())
                    postInvalidateOnAnimation()
                }
                LinkState.IDLE -> neonRing(canvas, Color.WHITE, 0.16f)
            }
        }

        /** Three concentric strokes with alpha falloff = a neon glow. */
        private fun neonRing(canvas: Canvas, color: Int, intensity: Float) {
            val r = radius * 1.30f
            haloPaint.color = color
            haloPaint.strokeWidth = radius * 0.34f      // wide outer bloom
            haloPaint.alpha = (34 * intensity).toInt()
            canvas.drawCircle(cx, cy, r, haloPaint)
            haloPaint.strokeWidth = radius * 0.18f      // mid glow
            haloPaint.alpha = (90 * intensity).toInt()
            canvas.drawCircle(cx, cy, r, haloPaint)
            haloPaint.strokeWidth = radius * 0.07f      // bright core
            haloPaint.alpha = (255 * intensity).toInt()
            canvas.drawCircle(cx, cy, r, haloPaint)
        }
    }

    /** Eight-way d-pad driving the HID hat switch. */
    private inner class Dpad : Control() {
        private val minPressMs = 70L
        private var downAt = 0L
        private val releaseRunnable = Runnable {
            touchState.hat = HidConstants.HAT_NEUTRAL
            notifyChanged()
        }

        override fun onDown(x: Float, y: Float) {
            removeCallbacks(releaseRunnable)
            downAt = SystemClock.uptimeMillis()
            update(x, y, first = true)
        }

        override fun onMove(x: Float, y: Float) {
            removeCallbacks(releaseRunnable)
            update(x, y, first = false)
        }

        override fun onUp() {
            val held = SystemClock.uptimeMillis() - downAt
            if (held >= minPressMs) {
                touchState.hat = HidConstants.HAT_NEUTRAL
                notifyChanged()
            } else {
                postDelayed(releaseRunnable, minPressMs - held)
            }
        }

        private fun update(x: Float, y: Float, first: Boolean) {
            val dx = x - cx
            val dy = y - cy
            val dist = hypot(dx.toDouble(), dy.toDouble())
            val newHat = if (dist < radius * 0.18) {
                HidConstants.HAT_NEUTRAL
            } else {
                // Angle 0 = up, clockwise; snap to nearest of 8 directions.
                val deg = (Math.toDegrees(atan2(dx.toDouble(), -dy.toDouble())) + 360.0) % 360.0
                ((deg + 22.5) / 45.0).toInt() % 8
            }
            if (newHat != touchState.hat) {
                touchState.hat = newHat
                notifyChanged()
                if (first) haptics.press() else haptics.tick()
            } else if (first) {
                haptics.press()
            }
        }

        /**
         * One moulded cross on a pivot, the way a Game Boy or DualShock pad
         * works: a single continuous piece with no seams cut through it, sunk
         * in a shallow dish. Pushing a direction rocks the whole cross that
         * way — the pressed side dips into shadow and the opposite side lifts
         * — so a thumb resting in the middle can roll between directions.
         */
        override fun draw(canvas: Canvas) {
            val arm = radius * 0.30f
            val len = radius * 0.84f
            val hat = touchState.hat
            val up = hat == 7 || hat == 0 || hat == 1
            val right = hat == 1 || hat == 2 || hat == 3
            val down = hat == 3 || hat == 4 || hat == 5
            val left = hat == 5 || hat == 6 || hat == 7

            ensureCross(arm, len)

            // Housing: a real pad has no black plate behind the cross, just a
            // shallow hollow the piece pivots in. Kept tight to the cross and
            // faded at its edge so it reads as recessed shell, not a disc.
            moldPaint.shader = null
            val housingR = len * 1.16f
            moldPaint.color = withAlpha(Color.BLACK, 70)
            canvas.drawCircle(cx, cy + radius * 0.02f, housingR, moldPaint)
            moldPaint.color = withAlpha(Color.BLACK, 60)
            canvas.drawCircle(cx, cy + radius * 0.02f, housingR * 0.93f, moldPaint)
            bevelPaint.strokeWidth = radius * 0.045f
            bevelPaint.color = withAlpha(Color.BLACK, 120)
            arcRect(cx, cy + radius * 0.02f, housingR * 0.99f)
            canvas.drawArc(tmpRect, 195f, 150f, false, bevelPaint)
            bevelPaint.color = withAlpha(Color.WHITE, 26)
            canvas.drawArc(tmpRect, 15f, 150f, false, bevelPaint)

            // How far the cross tips, and toward where.
            val tilt = radius * 0.05f
            val tx = (if (right) 1f else 0f) - (if (left) 1f else 0f)
            val ty = (if (down) 1f else 0f) - (if (up) 1f else 0f)
            val norm = if (tx != 0f && ty != 0f) 0.707f else 1f

            canvas.save()
            canvas.translate(cx + tx * tilt * norm, cy + ty * tilt * norm)

            // Moulded edge: a dark copy underneath gives the piece thickness.
            moldPaint.color = withAlpha(Color.BLACK, 150)
            canvas.save()
            canvas.translate(0f, radius * 0.055f)
            canvas.drawPath(crossPath, moldPaint)
            canvas.restore()

            // The cross itself — one shape, one gradient, no seams.
            moldPaint.shader = crossShader(-len, len)
            canvas.drawPath(crossPath, moldPaint)
            moldPaint.shader = null

            // Bevel: lit along the top edge, shadowed along the bottom.
            bevelPaint.strokeWidth = radius * 0.055f
            canvas.save()
            canvas.clipRect(-len, -len, len, 0f)
            bevelPaint.color = withAlpha(Color.WHITE, 62)
            canvas.drawPath(crossPath, bevelPaint)
            canvas.restore()
            canvas.save()
            canvas.clipRect(-len, 0f, len, len)
            bevelPaint.color = withAlpha(Color.BLACK, 110)
            canvas.drawPath(crossPath, bevelPaint)
            canvas.restore()

            // Thumb dish in the middle — a soft depression, not a button.
            moldPaint.color = withAlpha(Color.BLACK, 46)
            canvas.drawCircle(0f, 0f, arm * 0.80f, moldPaint)
            moldPaint.color = withAlpha(Color.BLACK, 34)
            canvas.drawCircle(0f, 0f, arm * 0.55f, moldPaint)
            bevelPaint.strokeWidth = radius * 0.022f
            bevelPaint.color = withAlpha(Color.WHITE, 20)
            arcRect(0f, 0f, arm * 0.80f)
            canvas.drawArc(tmpRect, 15f, 150f, false, bevelPaint)

            // The side being pushed sits in shadow; soft, clipped to the piece.
            if (hat != HidConstants.HAT_NEUTRAL) {
                canvas.save()
                canvas.clipPath(crossPath)
                val px = tx * len * norm
                val py = ty * len * norm
                moldPaint.color = withAlpha(Color.BLACK, 52)
                canvas.drawCircle(px, py, len * 0.95f, moldPaint)
                canvas.drawCircle(px, py, len * 0.62f, moldPaint)
                canvas.restore()
            }

            // Arrows ride with the piece.
            drawDirArrow(canvas, 0f, -len * 0.70f, radius * 0.150f, 0f, up)
            drawDirArrow(canvas, len * 0.70f, 0f, radius * 0.150f, 90f, right)
            drawDirArrow(canvas, 0f, len * 0.70f, radius * 0.150f, 180f, down)
            drawDirArrow(canvas, -len * 0.70f, 0f, radius * 0.150f, 270f, left)

            canvas.restore()
        }

        /** Cross silhouette, centred on the origin, rebuilt only on resize. */
        private var crossArm = Float.NaN
        private var crossLen = Float.NaN
        private val crossPath = Path()

        private fun ensureCross(arm: Float, len: Float) {
            if (arm == crossArm && len == crossLen) return
            crossArm = arm
            crossLen = len
            val round = arm * 0.55f
            crossPath.reset()
            tmpRect.set(-arm, -len, arm, len)
            crossPath.addRoundRect(tmpRect, round, round, Path.Direction.CW)
            scratchPath.reset()
            tmpRect.set(-len, -arm, len, arm)
            scratchPath.addRoundRect(tmpRect, round, round, Path.Direction.CW)
            // Union so the piece has one continuous outline instead of two
            // overlapping bars with a seam where they cross.
            crossPath.op(scratchPath, Path.Op.UNION)
        }

        private fun drawDirArrow(
            canvas: Canvas, x: Float, y: Float, size: Float, rotation: Float, active: Boolean
        ) {
            arrowPaint.color = withAlpha(Color.BLACK, 130)
            drawArrow(canvas, x, y + size * 0.16f, size, rotation)
            arrowPaint.color = if (active) neonCyan else dpadArrowColor
            drawArrow(canvas, x, y, size, rotation)
        }
    }

    /** Analog stick driving one axis pair. */
    private inner class Stick(val isLeft: Boolean) : Control() {
        private var knobX = 0f
        private var knobY = 0f

        override fun onDown(x: Float, y: Float) {
            update(x, y)
            haptics.press()
        }

        override fun onMove(x: Float, y: Float) = update(x, y)

        override fun onUp() {
            knobX = 0f
            knobY = 0f
            setAxes(128, 128)
        }

        private fun update(x: Float, y: Float) {
            var dx = x - cx
            var dy = y - cy
            val dist = hypot(dx.toDouble(), dy.toDouble()).toFloat()
            val max = radius * 0.82f
            if (dist > max && dist > 0f) {
                dx *= max / dist
                dy *= max / dist
            }
            knobX = dx
            knobY = dy

            // MOD: radial dead zone + sensitivity (gain) from the edit panel.
            var nx = if (max > 0f) dx / max else 0f
            var ny = if (max > 0f) dy / max else 0f
            val mag = hypot(nx.toDouble(), ny.toDouble()).toFloat()
            val dz = deadzonePct / 100f
            if (mag <= dz || mag <= 0f) {
                nx = 0f
                ny = 0f
            } else {
                val scaled = ((mag - dz) / (1f - dz)) * (sensitivityPct / 100f)
                val clamped = min(scaled, 1f)
                val curved = if (curvePct > 0) {
                    Math.pow(clamped.toDouble(), 1.0 + curvePct / 50.0).toFloat()
                } else {
                    clamped
                }
                val k = curved / mag
                nx *= k
                ny *= k
            }

            setAxes(
                (128 + nx * 127).roundToInt().coerceIn(0, 255),
                (128 + ny * 127).roundToInt().coerceIn(0, 255)
            )
        }

        private fun setAxes(ax: Int, ay: Int) {
            if (isLeft) {
                if (touchState.lx != ax || touchState.ly != ay) {
                    touchState.lx = ax
                    touchState.ly = ay
                    notifyChanged()
                }
            } else {
                if (touchState.rx != ax || touchState.ry != ay) {
                    touchState.rx = ax
                    touchState.ry = ay
                    notifyChanged()
                }
            }
        }

        /**
         * A thumbstick standing in a deep well. The head is a dome: a bright
         * crown, a rim-lit grippy lip, and a concave face that catches light
         * on its far wall. It throws a contact shadow onto the well floor
         * that lengthens and shifts as the stick is pushed, which is what
         * sells it as a solid object standing above the surface.
         */
        override fun draw(canvas: Canvas) {
            val kx = cx + knobX
            val ky = cy + knobY
            val capR = radius * 0.56f
            val travel = hypot(knobX.toDouble(), knobY.toDouble()).toFloat()
            val lean = if (radius > 0f) (travel / (radius * 0.82f)).coerceIn(0f, 1f) else 0f

            // Well: deep, with a dark upper rim and a lit lower rim so it
            // reads as a hole rather than a disc.
            moldPaint.shader = null
            moldPaint.color = wellColor
            canvas.drawCircle(cx, cy, radius, moldPaint)
            moldPaint.color = withAlpha(Color.BLACK, 110)
            canvas.drawCircle(cx, cy, radius * 0.90f, moldPaint)
            bevelPaint.strokeWidth = radius * 0.10f
            bevelPaint.color = withAlpha(Color.BLACK, 185)
            arcRect(cx, cy, radius * 0.95f)
            canvas.drawArc(tmpRect, 185f, 165f, false, bevelPaint)
            bevelPaint.color = withAlpha(Color.WHITE, 34)
            canvas.drawArc(tmpRect, 5f, 165f, false, bevelPaint)

            // Travel ring, lit while the stick is in hand.
            strokePaint.color =
                withAlpha(if (pressed) accentColor else outlineColor, if (pressed) 170 else 70)
            canvas.drawCircle(cx, cy, radius * 0.86f, strokePaint)

            // Contact shadow: grows and stretches away as the stick leans.
            moldPaint.color = withAlpha(Color.BLACK, 150)
            canvas.drawCircle(
                kx + knobX * 0.10f,
                ky + radius * (0.11f + 0.05f * lean),
                capR * (1.02f + 0.05f * lean),
                moldPaint
            )

            // Dome.
            moldPaint.shader = LinearGradient(
                kx, ky - capR, kx, ky + capR, capCrown, capSkirt, Shader.TileMode.CLAMP
            )
            canvas.drawCircle(kx, ky, capR, moldPaint)
            moldPaint.shader = null

            // Grippy lip around the head.
            bevelPaint.strokeWidth = capR * 0.18f
            bevelPaint.color = withAlpha(Color.WHITE, 78)
            arcRect(kx, ky, capR * 0.91f)
            canvas.drawArc(tmpRect, 192f, 148f, false, bevelPaint)
            bevelPaint.color = withAlpha(Color.BLACK, 135)
            canvas.drawArc(tmpRect, 12f, 148f, false, bevelPaint)

            // Concave face: shadowed near wall, lit far wall.
            moldPaint.color = withAlpha(Color.BLACK, 92)
            canvas.drawCircle(kx, ky, capR * 0.60f, moldPaint)
            bevelPaint.strokeWidth = capR * 0.13f
            bevelPaint.color = withAlpha(Color.WHITE, 40)
            arcRect(kx, ky, capR * 0.57f)
            canvas.drawArc(tmpRect, 20f, 140f, false, bevelPaint)
            bevelPaint.color = withAlpha(Color.BLACK, 90)
            canvas.drawArc(tmpRect, 200f, 140f, false, bevelPaint)

            // Crown glint.
            bevelPaint.strokeWidth = capR * 0.15f
            bevelPaint.color = withAlpha(Color.WHITE, 46)
            arcRect(kx, ky - capR * 0.06f, capR * 0.80f)
            canvas.drawArc(tmpRect, 208f, 96f, false, bevelPaint)
        }
    }

    /** Settings gear — opens the connection/skin menu instead of sending input. */
    private inner class GearButton : Control() {
        override fun onDown(x: Float, y: Float) {
            haptics.press()
        }

        override fun onUp() {
            listener?.onOpenSettings()
        }

        override fun draw(canvas: Canvas) {
            drawMoldedCap(canvas, radius, 0)
            val capY = cy + capSink(radius)
            textPaint.textSize = radius
            val baseline = capY - (textPaint.ascent() + textPaint.descent()) / 2f
            textPaint.color = withAlpha(Color.BLACK, 120)
            canvas.drawText("⚙", cx, baseline + radius * 0.04f, textPaint)
            textPaint.color = textColor
            canvas.drawText("⚙", cx, baseline, textPaint)
        }
    }

    // ------------------------------------------------------------------ paints

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val moldPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val bevelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val tmpRect = RectF()

    // Molded-plastic palette: lit crown, shadowed skirt, near-black socket.
    private val capLit = Color.parseColor("#474D69")
    private val capShade = Color.parseColor("#202432")
    private val capPressedDeep = Color.parseColor("#171A24")
    private val capPressedRise = Color.parseColor("#343A52")
    private val socketColor = Color.parseColor("#0C0E15")
    private val wellColor = Color.parseColor("#090B11")
    // Sticks read as taller than the flat caps, so they get a brighter crown
    // and a deeper skirt than the button gradient.
    private val capCrown = Color.parseColor("#585F80")
    private val capSkirt = Color.parseColor("#1B1E2A")

    private fun arcRect(x: Float, y: Float, r: Float) {
        tmpRect.set(x - r, y - r, x + r, y + r)
    }

    /** Vertical gradient reused for the d-pad cross. */
    private fun crossShader(top: Float, bottom: Float): LinearGradient =
        LinearGradient(0f, top, 0f, bottom, capLit, capShade, Shader.TileMode.CLAMP)

    /** Lift a color toward white — used for labels on a pressed cap. */
    private fun brighten(color: Int): Int = Color.rgb(
        (Color.red(color) + (255 - Color.red(color)) * 0.45f).toInt(),
        (Color.green(color) + (255 - Color.green(color)) * 0.45f).toInt(),
        (Color.blue(color) + (255 - Color.blue(color)) * 0.45f).toInt()
    )
    private val neonCyan = Color.parseColor("#2BE4FF")
    private val neonGreen = Color.parseColor("#3BFFA8")
    private val arrowPath = Path()
    private val scratchPath = Path()

    // Background gradients: deep navy fading to indigo (Game Boy skin goes green).
    private val bgTop = Color.parseColor("#0C0D16")
    private val bgBottom = Color.parseColor("#1A1E33")
    private val gbBgTop = Color.parseColor("#131F18")
    private val gbBgBottom = Color.parseColor("#20301F")
    private val dpadArrowColor = Color.parseColor("#A9AEC6")
    private val outlineColor = Color.parseColor("#3A3D52")
    private val textColor = Color.parseColor("#E8E9F0")
    private val dimTextColor = Color.parseColor("#8A8FA8")
    private val accentColor = Color.parseColor("#6C79FF")

    // PS face colors
    private val psTriangleColor = Color.parseColor("#2ECC71")
    private val psCircleColor = Color.parseColor("#E74C3C")
    private val psCrossColor = Color.parseColor("#3498DB")
    private val psSquareColor = Color.parseColor("#E78FB3")

    // SNES (Super Famicom) face colors: X blue, A red, B yellow, Y green
    private val snesXColor = Color.parseColor("#3B6EE0")
    private val snesAColor = Color.parseColor("#D93025")
    private val snesBColor = Color.parseColor("#F4B400")
    private val snesYColor = Color.parseColor("#0F9D58")

    // Xbox face colours (A green, B red, X blue, Y amber)
    private val xbA = Color.parseColor("#6CC24A")
    private val xbB = Color.parseColor("#E4472D")
    private val xbX = Color.parseColor("#2E7BD4")
    private val xbY = Color.parseColor("#F2C230")
    private val nesColor = Color.parseColor("#E4404A")
    private val gbColor = Color.parseColor("#B48CC8")

    // Sega pads: light labels on dark buttons; Dreamcast keeps its own colours.
    private val segaLabel = Color.parseColor("#D9DCEA")
    private val dcA = Color.parseColor("#E4472D")
    private val dcB = Color.parseColor("#F2C230")
    private val dcX = Color.parseColor("#2E7BD4")
    private val dcY = Color.parseColor("#6CC24A")

    // ---------------------------------------------------------------- layout

    // Positional face buttons: bottom/right/left/top — HID mapping is fixed,
    // labels and colors change with the skin.
    private val dpad = Dpad()
    private val leftStick = Stick(isLeft = true)
    private val rightStick = Stick(isLeft = false)
    private val faceBottom = PadButton("✕", HidConstants.BTN_CROSS, psCrossColor, rimmed = true)
    private val faceRight = PadButton("○", HidConstants.BTN_CIRCLE, psCircleColor, rimmed = true)
    private val faceLeft = PadButton("□", HidConstants.BTN_SQUARE, psSquareColor, rimmed = true)
    private val faceTop = PadButton("△", HidConstants.BTN_TRIANGLE, psTriangleColor, rimmed = true)
    private val btnL1 = PadButton("L1", HidConstants.BTN_L1)
    private val btnL2 = PadButton("L2", HidConstants.BTN_L2)
    private val btnR1 = PadButton("R1", HidConstants.BTN_R1)
    private val btnR2 = PadButton("R2", HidConstants.BTN_R2)
    private val btnL3 = PadButton("L3", HidConstants.BTN_L3, dimTextColor, 0.9f)
    private val btnR3 = PadButton("R3", HidConstants.BTN_R3, dimTextColor, 0.9f)
    private val btnShare = PadButton("SHARE", HidConstants.BTN_SHARE, dimTextColor, 0.38f)
    private val btnOptions = PadButton("OPTIONS", HidConstants.BTN_OPTIONS, dimTextColor, 0.3f)
    private val btnHome =
        PadButton("PS", HidConstants.BTN_PS, accentColor, 0.8f, rimmed = true, isHome = true)
    private val gear = GearButton()

    private var activeControls: List<Control> = emptyList()

    // ---------------------------------------------------- custom layouts

    /** Stable keys for saving each control's position. */
    private val controlKeys: Map<String, Control> = mapOf(
        "dpad" to dpad, "lstick" to leftStick, "rstick" to rightStick,
        "ftop" to faceTop, "fright" to faceRight, "fbottom" to faceBottom,
        "fleft" to faceLeft, "l1" to btnL1, "l2" to btnL2, "r1" to btnR1,
        "r2" to btnR2, "l3" to btnL3, "r3" to btnR3, "share" to btnShare,
        "options" to btnOptions, "home" to btnHome, "gear" to gear
    )

    private val layoutPrefs =
        context.getSharedPreferences("pocketpad_layouts", Context.MODE_PRIVATE)

    // ------------------------------------------------- MOD: user settings
    // Saved in the same preferences file as the layouts.

    /** Size of face/shoulder/menu buttons, in percent (60..200). */
    private var buttonSizePct = layoutPrefs.getInt("btn_size", 100)

    /** Size of the sticks and the d-pad, in percent (60..200). */
    private var stickSizePct = layoutPrefs.getInt("stick_size", 100)

    /** Size of the d-pad on its own, in percent. */
    private var dpadSizePct = layoutPrefs.getInt("dpad_size", 100)

    /** Response curve of the sticks, 0 (linear) to 100 (very soft around the centre). */
    private var curvePct = layoutPrefs.getInt("curve", 0)

    /** Radial dead zone of the analog sticks, in percent (0..30). */
    private var deadzonePct = layoutPrefs.getInt("deadzone", 0)

    /** Stick gain, in percent (50..150). 100 = unchanged. */
    private var sensitivityPct = layoutPrefs.getInt("sensitivity", 100)

    /** Extra design in use (NONE = the normal skin) and the skin it was chosen over. */
    private var extra: Extra = try {
        Extra.valueOf(layoutPrefs.getString("extra_style", "NONE") ?: "NONE")
    } catch (e: Exception) {
        Extra.NONE
    }
    private var extraBase: String = layoutPrefs.getString("extra_base", "") ?: ""

    private fun saveExtra() {
        layoutPrefs.edit()
            .putString("extra_style", extra.name)
            .putString("extra_base", extraBase)
            .apply()
    }

    private fun setExtraStyle(e: Extra) {
        releaseAllPointers()
        extra = e
        extraBase = skin.name
        saveExtra()
        applySkin()
        relayout()
        invalidate()
    }

    private fun scaleFor(c: Control): Float = when {
        c === gear -> 1f
        c === dpad -> dpadSizePct / 100f
        c === leftStick || c === rightStick -> stickSizePct / 100f
        else -> buttonSizePct / 100f
    }

    /** Re-apply the size settings without moving anything. */
    private fun applyScales() {
        for (c in controlKeys.values) c.radius = c.baseRadius * scaleFor(c)
        invalidate()
    }

    private fun adjustSetting(row: Int, dir: Int) {
        when (row) {
            0 -> buttonSizePct = (buttonSizePct + dir * 10).coerceIn(50, 250)
            1 -> dpadSizePct = (dpadSizePct + dir * 10).coerceIn(50, 250)
            2 -> stickSizePct = (stickSizePct + dir * 10).coerceIn(50, 250)
            3 -> deadzonePct = (deadzonePct + dir).coerceIn(0, 50)
            4 -> sensitivityPct = (sensitivityPct + dir * 5).coerceIn(10, 200)
            5 -> curvePct = (curvePct + dir * 5).coerceIn(0, 100)
            6 -> {
                val all = Extra.values()
                setExtraStyle(all[(extra.ordinal + dir + all.size) % all.size])
            }
        }
        layoutPrefs.edit()
            .putInt("btn_size", buttonSizePct)
            .putInt("stick_size", stickSizePct)
            .putInt("dpad_size", dpadSizePct)
            .putInt("curve", curvePct)
            .putInt("deadzone", deadzonePct)
            .putInt("sensitivity", sensitivityPct)
            .apply()
        haptics.tick()
        if (row < 3) applyScales() else invalidate()
    }

    // ------------------------------------------- MOD: edit-mode settings UI

    private var settingsOpen = false
    private val pillRect = RectF()
    private val panelRect = RectF()
    private val closeRect = RectF()
    private val minusRects = Array(7) { RectF() }
    private val plusRects = Array(7) { RectF() }
    private val panelLabels = arrayOf("Botones", "Cruceta", "Sticks", "Zona muerta", "Sensibilidad", "Curva", "Diseño")
    private val panelFill = Color.parseColor("#1B1E2A")
    private val panelButtonFill = Color.parseColor("#2C3048")

    private val dp: Float get() = resources.displayMetrics.density

    private fun computeEditUi() {
        val d = dp
        val pillW = 120f * d
        val pillH = 34f * d
        val pillTop = safeTop + 6f * d
        pillRect.set((width - pillW) / 2f, pillTop, (width + pillW) / 2f, pillTop + pillH)

        val rowH = 34f * d
        val headH = 34f * d
        val footH = 46f * d
        val panelW = min(width.toFloat() - 24f * d, 420f * d)
        val panelH = headH + rowH * 7 + footH
        val left = (width - panelW) / 2f
        val top = max(safeTop + 8f * d, (height - panelH) / 2f)
        panelRect.set(left, top, left + panelW, top + panelH)

        val btn = 32f * d
        for (i in 0 until 7) {
            val rowCy = top + headH + rowH * i + rowH / 2f
            val plusRight = left + panelW - 12f * d
            plusRects[i].set(plusRight - btn, rowCy - btn / 2f, plusRight, rowCy + btn / 2f)
            val minusRight = plusRects[i].left - 110f * d
            minusRects[i].set(minusRight - btn, rowCy - btn / 2f, minusRight, rowCy + btn / 2f)
        }
        val closeW = 140f * d
        closeRect.set(
            left + (panelW - closeW) / 2f,
            top + panelH - footH + 8f * d,
            left + (panelW + closeW) / 2f,
            top + panelH - 8f * d
        )
    }

    private fun drawLabel(
        canvas: Canvas, text: String, x: Float, cyText: Float, size: Float, color: Int,
        align: Paint.Align = Paint.Align.CENTER
    ) {
        textPaint.textAlign = align
        textPaint.textSize = size
        textPaint.color = color
        val baseline = cyText - (textPaint.ascent() + textPaint.descent()) / 2f
        canvas.drawText(text, x, baseline, textPaint)
        textPaint.textAlign = Paint.Align.CENTER
    }

    private fun drawEditUi(canvas: Canvas) {
        computeEditUi()
        val d = dp
        moldPaint.shader = null

        if (!settingsOpen) {
            val rr = pillRect.height() / 2f
            moldPaint.color = withAlpha(Color.BLACK, 190)
            canvas.drawRoundRect(pillRect, rr, rr, moldPaint)
            strokePaint.color = accentColor
            canvas.drawRoundRect(pillRect, rr, rr, strokePaint)
            drawLabel(canvas, "AJUSTES", pillRect.centerX(), pillRect.centerY(), 13f * d, textColor)
            return
        }

        canvas.drawColor(withAlpha(Color.BLACK, 130))
        moldPaint.color = panelFill
        canvas.drawRoundRect(panelRect, 18f * d, 18f * d, moldPaint)
        strokePaint.color = outlineColor
        canvas.drawRoundRect(panelRect, 18f * d, 18f * d, strokePaint)
        drawLabel(
            canvas, "Ajustes del mando", panelRect.centerX(), panelRect.top + 20f * d,
            16f * d, textColor
        )

        val values = arrayOf(
            "$buttonSizePct%", "$dpadSizePct%", "$stickSizePct%", "$deadzonePct%", "$sensitivityPct%", "$curvePct%", extra.title
        )
        for (i in 0 until 7) {
            val rowCy = minusRects[i].centerY()
            drawLabel(
                canvas, panelLabels[i], panelRect.left + 16f * d, rowCy, 13f * d, textColor,
                Paint.Align.LEFT
            )
            for (r in arrayOf(minusRects[i], plusRects[i])) {
                moldPaint.color = panelButtonFill
                canvas.drawRoundRect(r, 10f * d, 10f * d, moldPaint)
            }
            drawLabel(canvas, "−", minusRects[i].centerX(), minusRects[i].centerY(), 24f * d, textColor)
            drawLabel(canvas, "+", plusRects[i].centerX(), plusRects[i].centerY(), 24f * d, textColor)
            drawLabel(
                canvas, values[i], (minusRects[i].right + plusRects[i].left) / 2f, rowCy,
                14f * d, neonCyan
            )
        }

        moldPaint.color = accentColor
        canvas.drawRoundRect(closeRect, 12f * d, 12f * d, moldPaint)
        drawLabel(canvas, "Cerrar", closeRect.centerX(), closeRect.centerY(), 15f * d, Color.WHITE)
    }

    /** Returns true when the tap was consumed by the settings UI. */
    private fun handleEditUiTap(x: Float, y: Float): Boolean {
        computeEditUi()
        if (settingsOpen) {
            if (closeRect.contains(x, y)) {
                settingsOpen = false
                return true
            }
            for (i in 0 until 7) {
                if (minusRects[i].contains(x, y)) {
                    adjustSetting(i, -1)
                    return true
                }
                if (plusRects[i].contains(x, y)) {
                    adjustSetting(i, 1)
                    return true
                }
            }
            if (!panelRect.contains(x, y)) settingsOpen = false
            return true
        }
        if (pillRect.contains(x, y)) {
            settingsOpen = true
            haptics.tick()
            return true
        }
        return false
    }

    /** True while the user is dragging buttons around. */
    var isEditingLayout = false
        private set

    // ------------------------------------------------- custom background

    private var bgBitmap: Bitmap? = null
    private var bgPanX = 0.5f
    private var bgPanY = 0.5f
    private val bgBitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    // Tint between picture and controls so buttons always stay readable.
    private val scrimColor = Color.argb(110, 6, 7, 12)

    /** True while the user is dragging the background picture into place. */
    var isPanningBackground = false
        private set

    val hasBackgroundImage: Boolean get() = bgBitmap != null

    /** Set (or clear, with null) the custom background picture. */
    fun setBackgroundImage(bitmap: Bitmap?, panX: Float, panY: Float) {
        val previous = bgBitmap
        bgBitmap = bitmap
        // Free the picture being replaced instead of waiting on the collector;
        // full-screen bitmaps are several megabytes each.
        if (previous != null && previous !== bitmap && !previous.isRecycled) {
            previous.recycle()
        }
        bgPanX = panX.coerceIn(0f, 1f)
        bgPanY = panY.coerceIn(0f, 1f)
        invalidate()
    }

    fun enterBackgroundPanMode() {
        releaseAllPointers()
        isPanningBackground = true
        invalidate()
    }

    /** Exit pan mode, returning the chosen position to persist. */
    fun finishBackgroundPan(): Pair<Float, Float> {
        isPanningBackground = false
        invalidate()
        return bgPanX to bgPanY
    }

    fun setBackgroundPan(panX: Float, panY: Float) {
        bgPanX = panX.coerceIn(0f, 1f)
        bgPanY = panY.coerceIn(0f, 1f)
        invalidate()
    }

    fun centerBackground() = setBackgroundPan(0.5f, 0.5f)

    fun enterLayoutEditMode() {
        releaseAllPointers()
        settingsOpen = false
        isEditingLayout = true
        invalidate()
    }

    /** Persist the current positions for this skin + orientation and exit. */
    fun saveEditedLayout() {
        val w = safeWidth()
        val h = safeHeight()
        if (w > 0 && h > 0) {
            val serialized = controlKeys.entries.joinToString(";") { (key, c) ->
                "$key:${(c.cx - offX) / w}:${(c.cy - offY) / h}"
            }
            layoutPrefs.edit().putString(layoutPrefKey(), serialized).apply()
        }
        settingsOpen = false
        isEditingLayout = false
        invalidate()
    }

    /** Forget the saved layout for this skin + orientation; back to default. */
    fun resetLayoutToDefault() {
        layoutPrefs.edit().remove(layoutPrefKey()).apply()
        relayout()
        invalidate()
    }

    /** Exit without saving; reload whatever was stored (or the default). */
    fun cancelLayoutEdit() {
        settingsOpen = false
        isEditingLayout = false
        relayout()
        invalidate()
    }

    private fun layoutPrefKey(): String =
        "${if (extra != Extra.NONE) "X_" + extra.name else skin.name}_${if (height >= width) "P" else "L"}"

    private fun safeWidth() = (width - safeLeft - safeRight).coerceAtLeast(1f)
    private fun safeHeight() = (height - safeTop - safeBottom).coerceAtLeast(1f)

    private fun relayout() {
        if (width > 0 && height > 0) layoutControls(width.toFloat(), height.toFloat())
    }

    /** Re-apply a user-saved layout on top of the defaults. */
    private fun applySavedLayout() {
        val saved = layoutPrefs.getString(layoutPrefKey(), null) ?: return
        val w = safeWidth()
        val h = safeHeight()
        for (entry in saved.split(';')) {
            val parts = entry.split(':')
            if (parts.size != 3) continue
            val control = controlKeys[parts[0]] ?: continue
            val fx = parts[1].toFloatOrNull() ?: continue
            val fy = parts[2].toFloatOrNull() ?: continue
            control.cx = offX + fx * w
            control.cy = offY + fy * h
        }
    }

    // Safe-area offsets so controls avoid the camera cutout and gesture bars.
    private var safeLeft = 0f
    private var safeTop = 0f
    private var safeRight = 0f
    private var safeBottom = 0f
    private var offX = 0f
    private var offY = 0f

    init {
        applySkin()
        ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            safeLeft = bars.left.toFloat()
            safeTop = bars.top.toFloat()
            safeRight = bars.right.toFloat()
            safeBottom = bars.bottom.toFloat()
            if (width > 0 && height > 0) {
                layoutControls(width.toFloat(), height.toFloat())
                invalidate()
            }
            insets
        }
    }

    private fun applySkin() {
        if (extra != Extra.NONE) {
            applyExtra()
            return
        }
        when (skin) {
            Skin.PS -> {
                faceBottom.label = "✕"; faceBottom.labelColor = psCrossColor; faceBottom.labelScale = 1f
                faceRight.label = "○"; faceRight.labelColor = psCircleColor; faceRight.labelScale = 1f
                faceLeft.label = "□"; faceLeft.labelColor = psSquareColor; faceLeft.labelScale = 1f
                faceTop.label = "△"; faceTop.labelColor = psTriangleColor; faceTop.labelScale = 1f
                btnL1.label = "L1"; btnR1.label = "R1"
                btnL2.label = "L2"; btnR2.label = "R2"
                btnL3.label = "L3"; btnR3.label = "R3"
                btnShare.label = "SHARE"; btnShare.labelScale = 0.38f
                btnOptions.label = "OPTIONS"; btnOptions.labelScale = 0.3f
                btnHome.label = "PS"
                activeControls = listOf(
                    dpad, leftStick, rightStick,
                    faceTop, faceRight, faceBottom, faceLeft,
                    btnL1, btnL2, btnR1, btnR2, btnL3, btnR3,
                    btnShare, btnOptions, btnHome, gear
                )
            }
            Skin.XBOX -> {
                // Positions are what the host actually reads, and they match
                // PlayStation one-for-one: bottom/right/left/top = A/B/X/Y.
                faceBottom.label = "A"; faceBottom.labelColor = xbA; faceBottom.labelScale = 0.9f
                faceRight.label = "B"; faceRight.labelColor = xbB; faceRight.labelScale = 0.9f
                faceLeft.label = "X"; faceLeft.labelColor = xbX; faceLeft.labelScale = 0.9f
                faceTop.label = "Y"; faceTop.labelColor = xbY; faceTop.labelScale = 0.9f
                btnL1.label = "LB"; btnR1.label = "RB"
                btnL2.label = "LT"; btnR2.label = "RT"
                btnL3.label = "LS"; btnR3.label = "RS"
                btnShare.label = "VIEW"; btnShare.labelScale = 0.4f
                btnOptions.label = "MENU"; btnOptions.labelScale = 0.4f
                btnHome.label = "✕BOX"; btnHome.labelScale = 0.44f
                activeControls = listOf(
                    dpad, leftStick, rightStick,
                    faceTop, faceRight, faceBottom, faceLeft,
                    btnL1, btnL2, btnR1, btnR2, btnL3, btnR3,
                    btnShare, btnOptions, btnHome, gear
                )
            }
            Skin.SNES -> {
                faceTop.label = "X"; faceTop.labelColor = snesXColor; faceTop.labelScale = 0.9f
                faceRight.label = "A"; faceRight.labelColor = snesAColor; faceRight.labelScale = 0.9f
                faceBottom.label = "B"; faceBottom.labelColor = snesBColor; faceBottom.labelScale = 0.9f
                faceLeft.label = "Y"; faceLeft.labelColor = snesYColor; faceLeft.labelScale = 0.9f
                btnL1.label = "L"; btnR1.label = "R"
                btnShare.label = "SELECT"; btnShare.labelScale = 0.34f
                btnOptions.label = "START"; btnOptions.labelScale = 0.34f
                btnHome.label = "⌂"
                activeControls = listOf(
                    dpad, faceTop, faceRight, faceBottom, faceLeft,
                    btnL1, btnR1, btnShare, btnOptions, btnHome, gear
                )
            }
            Skin.NES, Skin.GAMEBOY -> {
                val color = if (skin == Skin.NES) nesColor else gbColor
                faceRight.label = "A"; faceRight.labelColor = color; faceRight.labelScale = 0.9f
                faceBottom.label = "B"; faceBottom.labelColor = color; faceBottom.labelScale = 0.9f
                btnShare.label = "SELECT"; btnShare.labelScale = 0.34f
                btnOptions.label = "START"; btnOptions.labelScale = 0.34f
                btnHome.label = "⌂"
                activeControls = listOf(
                    dpad, faceRight, faceBottom, btnShare, btnOptions, btnHome, gear
                )
            }
        }
    }

    private fun releaseAllPointers() {
        for (control in activeControls) {
            if (control.pressed) {
                control.pointerId = -1
                control.onUp()
            }
        }
        touchState.reset()
        notifyChanged()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w == 0 || h == 0) return
        layoutControls(w.toFloat(), h.toFloat())
    }

    private fun layoutControls(fullW: Float, fullH: Float) {
        // Lay out inside the safe area (excludes cutout + gesture bars).
        offX = safeLeft
        offY = safeTop
        val w = (fullW - safeLeft - safeRight).coerceAtLeast(1f)
        val h = (fullH - safeTop - safeBottom).coerceAtLeast(1f)
        if (extra != Extra.NONE) {
            layoutExtra(w, h)
            applySavedLayout()
            return
        }
        val portrait = h >= w
        when (skin) {
            Skin.PS -> if (portrait) layoutPsPortrait(w, h) else layoutPsLandscape(w, h)
            Skin.XBOX -> if (portrait) layoutXboxPortrait(w, h) else layoutXboxLandscape(w, h)
            Skin.SNES -> if (portrait) layoutSnesPortrait(w, h) else layoutSnesLandscape(w, h)
            Skin.NES, Skin.GAMEBOY ->
                if (portrait) layoutRetroPortrait(w, h) else layoutRetroLandscape(w, h)
        }
        applySavedLayout()
    }

    private fun place(c: Control, x: Float, y: Float, r: Float) {
        c.cx = offX + x
        c.cy = offY + y
        c.baseRadius = r
        c.radius = r * scaleFor(c)
    }

    // ------------------------------------------------------------ PS layouts
    // The top ~8% of the safe area stays empty for the status pill, and no
    // control sits inside another's halo — spacing modeled on a real pad.

    private fun layoutPsPortrait(w: Float, h: Float) {
        val unit = min(w, h * 0.46f)
        val small = unit * 0.072f
        val face = unit * 0.078f
        place(btnL1, w * 0.10f, h * 0.10f, small)
        place(btnL2, w * 0.26f, h * 0.10f, small)
        place(btnR2, w * 0.74f, h * 0.10f, small)
        place(btnR1, w * 0.90f, h * 0.10f, small)
        place(dpad, w * 0.26f, h * 0.28f, unit * 0.19f)
        val fx = w * 0.74f
        val fy = h * 0.28f
        val off = unit * 0.138f
        place(faceTop, fx, fy - off, face)
        place(faceRight, fx + off, fy, face)
        place(faceBottom, fx, fy + off, face)
        place(faceLeft, fx - off, fy, face)
        place(leftStick, w * 0.28f, h * 0.56f, unit * 0.155f)
        place(rightStick, w * 0.72f, h * 0.56f, unit * 0.155f)
        place(btnL3, w * 0.5f, h * 0.495f, small * 0.8f)
        place(btnR3, w * 0.5f, h * 0.625f, small * 0.8f)
        place(btnShare, w * 0.24f, h * 0.79f, small)
        place(btnHome, w * 0.50f, h * 0.79f, small * 1.2f)
        place(btnOptions, w * 0.76f, h * 0.79f, small)
        place(gear, w * 0.08f, h * 0.92f, small * 0.9f)
    }

    private fun layoutPsLandscape(w: Float, h: Float) {
        val unit = min(h, w * 0.46f)
        val small = unit * 0.072f
        val face = unit * 0.090f
        // Shoulders sit outboard of the clusters below them rather than
        // directly on top, so R1 does not stack over the top face button.
        place(btnL2, w * 0.048f, h * 0.112f, small)
        place(btnL1, w * 0.125f, h * 0.112f, small)
        place(btnR1, w * 0.875f, h * 0.112f, small)
        place(btnR2, w * 0.952f, h * 0.112f, small)
        place(dpad, w * 0.155f, h * 0.55f, unit * 0.205f)
        val fx = w * 0.845f
        val fy = h * 0.55f
        val off = unit * 0.165f
        place(faceTop, fx, fy - off, face)
        place(faceRight, fx + off, fy, face)
        place(faceBottom, fx, fy + off, face)
        place(faceLeft, fx - off, fy, face)
        place(leftStick, w * 0.35f, h * 0.71f, unit * 0.150f)
        place(rightStick, w * 0.65f, h * 0.71f, unit * 0.150f)
        place(btnL3, w * 0.445f, h * 0.94f, small * 0.78f)
        place(btnR3, w * 0.555f, h * 0.94f, small * 0.78f)
        place(btnShare, w * 0.38f, h * 0.22f, small)
        place(btnHome, w * 0.50f, h * 0.44f, small * 1.2f)
        place(btnOptions, w * 0.62f, h * 0.22f, small)
        place(gear, w * 0.045f, h * 0.90f, small * 0.9f)
    }

    // ----------------------------------------------------------- Xbox layouts
    // Xbox's signature offset: the left stick takes the high-left position and
    // the d-pad drops below it, mirrored by the face cluster and right stick.

    private fun layoutXboxPortrait(w: Float, h: Float) {
        val unit = min(w, h * 0.46f)
        val small = unit * 0.072f
        val face = unit * 0.078f
        place(btnL2, w * 0.10f, h * 0.075f, small)
        place(btnL1, w * 0.26f, h * 0.075f, small)
        place(btnR1, w * 0.74f, h * 0.075f, small)
        place(btnR2, w * 0.90f, h * 0.075f, small)
        place(leftStick, w * 0.26f, h * 0.29f, unit * 0.15f)
        val fx = w * 0.74f
        val fy = h * 0.29f
        val off = unit * 0.138f
        place(faceTop, fx, fy - off, face)
        place(faceRight, fx + off, fy, face)
        place(faceBottom, fx, fy + off, face)
        place(faceLeft, fx - off, fy, face)
        place(dpad, w * 0.27f, h * 0.52f, unit * 0.165f)
        place(rightStick, w * 0.73f, h * 0.52f, unit * 0.15f)
        place(btnL3, w * 0.5f, h * 0.44f, small * 0.78f)
        place(btnR3, w * 0.5f, h * 0.60f, small * 0.78f)
        place(btnShare, w * 0.26f, h * 0.79f, small)
        place(btnHome, w * 0.50f, h * 0.79f, small * 1.2f)
        place(btnOptions, w * 0.74f, h * 0.79f, small)
        place(gear, w * 0.08f, h * 0.92f, small * 0.9f)
    }

    private fun layoutXboxLandscape(w: Float, h: Float) {
        val unit = min(h, w * 0.46f)
        val small = unit * 0.072f
        val face = unit * 0.090f
        place(btnL2, w * 0.048f, h * 0.088f, small)
        place(btnL1, w * 0.125f, h * 0.088f, small)
        place(btnR1, w * 0.875f, h * 0.088f, small)
        place(btnR2, w * 0.952f, h * 0.088f, small)
        place(leftStick, w * 0.155f, h * 0.47f, unit * 0.155f)
        val fx = w * 0.845f
        val fy = h * 0.53f
        val off = unit * 0.165f
        place(faceTop, fx, fy - off, face)
        place(faceRight, fx + off, fy, face)
        place(faceBottom, fx, fy + off, face)
        place(faceLeft, fx - off, fy, face)
        place(dpad, w * 0.33f, h * 0.78f, unit * 0.16f)
        place(rightStick, w * 0.67f, h * 0.78f, unit * 0.150f)
        place(btnL3, w * 0.445f, h * 0.94f, small * 0.78f)
        place(btnR3, w * 0.555f, h * 0.94f, small * 0.78f)
        place(btnShare, w * 0.38f, h * 0.22f, small)
        place(btnHome, w * 0.50f, h * 0.44f, small * 1.2f)
        place(btnOptions, w * 0.62f, h * 0.22f, small)
        place(gear, w * 0.045f, h * 0.90f, small * 0.9f)
    }

    // ---------------------------------------------------------- SNES layouts

    private fun layoutSnesPortrait(w: Float, h: Float) {
        val unit = min(w, h * 0.46f)
        val small = unit * 0.076f
        val face = unit * 0.082f
        place(btnL1, w * 0.12f, h * 0.10f, small)
        place(btnR1, w * 0.88f, h * 0.10f, small)
        place(dpad, w * 0.26f, h * 0.30f, unit * 0.20f)
        val fx = w * 0.74f
        val fy = h * 0.30f
        val off = unit * 0.145f
        place(faceTop, fx, fy - off, face)
        place(faceRight, fx + off, fy, face)
        place(faceBottom, fx, fy + off, face)
        place(faceLeft, fx - off, fy, face)
        place(btnShare, w * 0.28f, h * 0.56f, small)
        place(btnOptions, w * 0.72f, h * 0.56f, small)
        place(btnHome, w * 0.50f, h * 0.70f, small)
        place(gear, w * 0.08f, h * 0.92f, small * 0.9f)
    }

    private fun layoutSnesLandscape(w: Float, h: Float) {
        val unit = min(h, w * 0.46f)
        val small = unit * 0.076f
        val face = unit * 0.095f
        place(btnL1, w * 0.09f, h * 0.14f, small)
        place(btnR1, w * 0.91f, h * 0.14f, small)
        place(dpad, w * 0.16f, h * 0.58f, unit * 0.22f)
        val fx = w * 0.84f
        val fy = h * 0.58f
        val off = unit * 0.172f
        place(faceTop, fx, fy - off, face)
        place(faceRight, fx + off, fy, face)
        place(faceBottom, fx, fy + off, face)
        place(faceLeft, fx - off, fy, face)
        place(btnShare, w * 0.38f, h * 0.24f, small)
        place(btnOptions, w * 0.62f, h * 0.24f, small)
        place(btnHome, w * 0.50f, h * 0.52f, small)
        place(gear, w * 0.05f, h * 0.90f, small * 0.9f)
    }

    // ------------------------------------------------- NES / Game Boy layouts

    private fun layoutRetroPortrait(w: Float, h: Float) {
        val unit = min(w, h * 0.46f)
        val small = unit * 0.08f
        val face = unit * 0.105f
        place(dpad, w * 0.26f, h * 0.32f, unit * 0.20f)
        // A high on the right, B lower-left of it — Game Boy diagonal.
        place(faceRight, w * 0.82f, h * 0.27f, face)
        place(faceBottom, w * 0.62f, h * 0.38f, face)
        place(btnShare, w * 0.28f, h * 0.58f, small)
        place(btnOptions, w * 0.72f, h * 0.58f, small)
        place(btnHome, w * 0.50f, h * 0.71f, small)
        place(gear, w * 0.08f, h * 0.92f, small * 0.9f)
    }

    private fun layoutRetroLandscape(w: Float, h: Float) {
        val unit = min(h, w * 0.46f)
        val small = unit * 0.08f
        val face = unit * 0.115f
        place(dpad, w * 0.16f, h * 0.55f, unit * 0.22f)
        place(faceRight, w * 0.89f, h * 0.44f, face)
        place(faceBottom, w * 0.76f, h * 0.63f, face)
        place(btnShare, w * 0.38f, h * 0.20f, small)
        place(btnOptions, w * 0.62f, h * 0.20f, small)
        place(btnHome, w * 0.50f, h * 0.48f, small)
        place(gear, w * 0.05f, h * 0.90f, small * 0.9f)
    }

    // ------------------------------------------------- PSP / SEGA designs

    private fun applyExtra() {
        when (extra) {
            Extra.NONE -> Unit
            Extra.PSP -> {
                faceBottom.label = "✕"; faceBottom.labelColor = psCrossColor; faceBottom.labelScale = 1f
                faceRight.label = "○"; faceRight.labelColor = psCircleColor; faceRight.labelScale = 1f
                faceLeft.label = "□"; faceLeft.labelColor = psSquareColor; faceLeft.labelScale = 1f
                faceTop.label = "△"; faceTop.labelColor = psTriangleColor; faceTop.labelScale = 1f
                btnL1.label = "L"; btnR1.label = "R"
                btnShare.label = "SELECT"; btnShare.labelScale = 0.34f
                btnOptions.label = "START"; btnOptions.labelScale = 0.34f
                btnHome.label = "⌂"
                activeControls = listOf(
                    dpad, leftStick, faceTop, faceRight, faceBottom, faceLeft,
                    btnL1, btnR1, btnShare, btnOptions, btnHome, gear
                )
            }
            Extra.MASTER_SYSTEM, Extra.GAMEGEAR -> {
                faceBottom.label = "1"; faceBottom.labelColor = segaLabel; faceBottom.labelScale = 0.9f
                faceRight.label = "2"; faceRight.labelColor = segaLabel; faceRight.labelScale = 0.9f
                btnOptions.label = if (extra == Extra.GAMEGEAR) "START" else "PAUSE"
                btnOptions.labelScale = 0.34f
                btnHome.label = "⌂"
                activeControls = listOf(dpad, faceBottom, faceRight, btnOptions, btnHome, gear)
            }
            Extra.MEGADRIVE3 -> {
                faceLeft.label = "A"; faceLeft.labelColor = segaLabel; faceLeft.labelScale = 0.9f
                faceBottom.label = "B"; faceBottom.labelColor = segaLabel; faceBottom.labelScale = 0.9f
                faceRight.label = "C"; faceRight.labelColor = segaLabel; faceRight.labelScale = 0.9f
                btnOptions.label = "START"; btnOptions.labelScale = 0.34f
                btnHome.label = "⌂"
                activeControls = listOf(
                    dpad, faceLeft, faceBottom, faceRight, btnOptions, btnHome, gear
                )
            }
            Extra.MEGADRIVE6 -> {
                faceLeft.label = "A"; faceLeft.labelColor = segaLabel; faceLeft.labelScale = 0.9f
                faceBottom.label = "B"; faceBottom.labelColor = segaLabel; faceBottom.labelScale = 0.9f
                faceRight.label = "C"; faceRight.labelColor = segaLabel; faceRight.labelScale = 0.9f
                btnL1.label = "X"; faceTop.label = "Y"; btnR1.label = "Z"
                faceTop.labelColor = segaLabel; faceTop.labelScale = 0.9f
                btnShare.label = "MODE"; btnShare.labelScale = 0.34f
                btnOptions.label = "START"; btnOptions.labelScale = 0.34f
                btnHome.label = "⌂"
                activeControls = listOf(
                    dpad, faceLeft, faceBottom, faceRight, btnL1, faceTop, btnR1,
                    btnShare, btnOptions, btnHome, gear
                )
            }
            Extra.SATURN -> {
                faceLeft.label = "A"; faceLeft.labelColor = segaLabel; faceLeft.labelScale = 0.9f
                faceBottom.label = "B"; faceBottom.labelColor = segaLabel; faceBottom.labelScale = 0.9f
                faceRight.label = "C"; faceRight.labelColor = segaLabel; faceRight.labelScale = 0.9f
                btnL1.label = "X"; faceTop.label = "Y"; btnR1.label = "Z"
                faceTop.labelColor = segaLabel; faceTop.labelScale = 0.9f
                btnL2.label = "L"; btnR2.label = "R"
                btnOptions.label = "START"; btnOptions.labelScale = 0.34f
                btnHome.label = "⌂"
                activeControls = listOf(
                    dpad, faceLeft, faceBottom, faceRight, btnL1, faceTop, btnR1,
                    btnL2, btnR2, btnOptions, btnHome, gear
                )
            }
            Extra.DREAMCAST -> {
                faceBottom.label = "A"; faceBottom.labelColor = dcA; faceBottom.labelScale = 0.9f
                faceRight.label = "B"; faceRight.labelColor = dcB; faceRight.labelScale = 0.9f
                faceLeft.label = "X"; faceLeft.labelColor = dcX; faceLeft.labelScale = 0.9f
                faceTop.label = "Y"; faceTop.labelColor = dcY; faceTop.labelScale = 0.9f
                btnL2.label = "L"; btnR2.label = "R"
                btnOptions.label = "START"; btnOptions.labelScale = 0.34f
                btnHome.label = "⌂"
                activeControls = listOf(
                    dpad, leftStick, faceTop, faceRight, faceBottom, faceLeft,
                    btnL2, btnR2, btnOptions, btnHome, gear
                )
            }
        }
    }

    private fun layoutExtra(w: Float, h: Float) {
        when (extra) {
            Extra.NONE -> Unit
            Extra.PSP -> layoutPsp(w, h)
            Extra.MASTER_SYSTEM, Extra.GAMEGEAR -> layoutTwoButton(w, h)
            Extra.MEGADRIVE3 -> layoutGenesis(w, h, six = false)
            Extra.MEGADRIVE6 -> layoutGenesis(w, h, six = true)
            Extra.SATURN -> layoutSaturn(w, h)
            Extra.DREAMCAST -> layoutDreamcast(w, h)
        }
    }

    private fun layoutPsp(w: Float, h: Float) {
        if (h >= w) {
            val unit = min(w, h * 0.46f)
            val small = unit * 0.076f
            val face = unit * 0.082f
            place(btnL1, w * 0.12f, h * 0.10f, small)
            place(btnR1, w * 0.88f, h * 0.10f, small)
            place(dpad, w * 0.26f, h * 0.30f, unit * 0.20f)
            val fx = w * 0.74f
            val fy = h * 0.30f
            val off = unit * 0.145f
            place(faceTop, fx, fy - off, face)
            place(faceRight, fx + off, fy, face)
            place(faceBottom, fx, fy + off, face)
            place(faceLeft, fx - off, fy, face)
            place(leftStick, w * 0.26f, h * 0.55f, unit * 0.14f)
            place(btnShare, w * 0.30f, h * 0.78f, small)
            place(btnOptions, w * 0.70f, h * 0.78f, small)
            place(btnHome, w * 0.50f, h * 0.66f, small)
            place(gear, w * 0.08f, h * 0.92f, small * 0.9f)
        } else {
            val unit = min(h, w * 0.46f)
            val small = unit * 0.076f
            val face = unit * 0.095f
            place(btnL1, w * 0.09f, h * 0.14f, small)
            place(btnR1, w * 0.91f, h * 0.14f, small)
            place(dpad, w * 0.16f, h * 0.42f, unit * 0.21f)
            place(leftStick, w * 0.16f, h * 0.80f, unit * 0.12f)
            val fx = w * 0.84f
            val fy = h * 0.46f
            val off = unit * 0.165f
            place(faceTop, fx, fy - off, face)
            place(faceRight, fx + off, fy, face)
            place(faceBottom, fx, fy + off, face)
            place(faceLeft, fx - off, fy, face)
            place(btnShare, w * 0.40f, h * 0.90f, small)
            place(btnOptions, w * 0.60f, h * 0.90f, small)
            place(btnHome, w * 0.50f, h * 0.50f, small)
            place(gear, w * 0.05f, h * 0.10f, small * 0.9f)
        }
    }

    /** Master System and Game Gear: d-pad plus buttons 1 and 2. */
    private fun layoutTwoButton(w: Float, h: Float) {
        if (h >= w) {
            val unit = min(w, h * 0.46f)
            val small = unit * 0.08f
            val face = unit * 0.10f
            place(dpad, w * 0.26f, h * 0.34f, unit * 0.20f)
            place(faceBottom, w * 0.62f, h * 0.40f, face)
            place(faceRight, w * 0.84f, h * 0.40f, face)
            place(btnOptions, w * 0.50f, h * 0.58f, small)
            place(btnHome, w * 0.50f, h * 0.70f, small)
            place(gear, w * 0.08f, h * 0.92f, small * 0.9f)
        } else {
            val unit = min(h, w * 0.46f)
            val small = unit * 0.08f
            val face = unit * 0.115f
            place(dpad, w * 0.16f, h * 0.55f, unit * 0.22f)
            place(faceBottom, w * 0.76f, h * 0.58f, face)
            place(faceRight, w * 0.90f, h * 0.58f, face)
            place(btnOptions, w * 0.50f, h * 0.22f, small)
            place(btnHome, w * 0.50f, h * 0.50f, small)
            place(gear, w * 0.05f, h * 0.90f, small * 0.9f)
        }
    }

    /** Mega Drive / Genesis: 3 buttons (A B C) or 6 buttons (plus X Y Z and MODE). */
    private fun layoutGenesis(w: Float, h: Float, six: Boolean) {
        if (h >= w) {
            val unit = min(w, h * 0.46f)
            val small = unit * 0.075f
            place(dpad, w * 0.24f, h * 0.34f, unit * 0.19f)
            if (!six) {
                val r = unit * 0.075f
                place(faceLeft, w * 0.54f, h * 0.46f, r)
                place(faceBottom, w * 0.70f, h * 0.40f, r)
                place(faceRight, w * 0.86f, h * 0.34f, r)
                place(btnOptions, w * 0.50f, h * 0.62f, small)
            } else {
                val r = unit * 0.065f
                place(faceLeft, w * 0.56f, h * 0.46f, r)
                place(faceBottom, w * 0.73f, h * 0.46f, r)
                place(faceRight, w * 0.90f, h * 0.46f, r)
                place(btnL1, w * 0.56f, h * 0.36f, r)
                place(faceTop, w * 0.73f, h * 0.36f, r)
                place(btnR1, w * 0.90f, h * 0.36f, r)
                place(btnShare, w * 0.30f, h * 0.64f, small)
                place(btnOptions, w * 0.70f, h * 0.64f, small)
            }
            place(btnHome, w * 0.50f, h * 0.74f, small)
            place(gear, w * 0.08f, h * 0.92f, small * 0.9f)
        } else {
            val unit = min(h, w * 0.46f)
            val small = unit * 0.08f
            place(dpad, w * 0.16f, h * 0.55f, unit * 0.22f)
            if (!six) {
                val r = unit * 0.10f
                place(faceLeft, w * 0.64f, h * 0.66f, r)
                place(faceBottom, w * 0.76f, h * 0.58f, r)
                place(faceRight, w * 0.88f, h * 0.50f, r)
                place(btnOptions, w * 0.50f, h * 0.22f, small)
            } else {
                val r = unit * 0.085f
                place(faceLeft, w * 0.66f, h * 0.68f, r)
                place(faceBottom, w * 0.77f, h * 0.68f, r)
                place(faceRight, w * 0.88f, h * 0.68f, r)
                place(btnL1, w * 0.66f, h * 0.42f, r)
                place(faceTop, w * 0.77f, h * 0.42f, r)
                place(btnR1, w * 0.88f, h * 0.42f, r)
                place(btnShare, w * 0.38f, h * 0.22f, small)
                place(btnOptions, w * 0.62f, h * 0.22f, small)
            }
            place(btnHome, w * 0.50f, h * 0.50f, small)
            place(gear, w * 0.05f, h * 0.90f, small * 0.9f)
        }
    }

    /** Saturn: A B C, X Y Z, shoulders L and R, Start. */
    private fun layoutSaturn(w: Float, h: Float) {
        if (h >= w) {
            val unit = min(w, h * 0.46f)
            val small = unit * 0.075f
            val r = unit * 0.065f
            place(btnL2, w * 0.12f, h * 0.10f, small)
            place(btnR2, w * 0.88f, h * 0.10f, small)
            place(dpad, w * 0.24f, h * 0.34f, unit * 0.19f)
            place(faceLeft, w * 0.56f, h * 0.46f, r)
            place(faceBottom, w * 0.73f, h * 0.46f, r)
            place(faceRight, w * 0.90f, h * 0.46f, r)
            place(btnL1, w * 0.56f, h * 0.36f, r)
            place(faceTop, w * 0.73f, h * 0.36f, r)
            place(btnR1, w * 0.90f, h * 0.36f, r)
            place(btnOptions, w * 0.50f, h * 0.62f, small)
            place(btnHome, w * 0.50f, h * 0.74f, small)
            place(gear, w * 0.08f, h * 0.92f, small * 0.9f)
        } else {
            val unit = min(h, w * 0.46f)
            val small = unit * 0.08f
            val r = unit * 0.085f
            place(btnL2, w * 0.09f, h * 0.14f, small)
            place(btnR2, w * 0.91f, h * 0.14f, small)
            place(dpad, w * 0.16f, h * 0.55f, unit * 0.22f)
            place(faceLeft, w * 0.68f, h * 0.70f, r)
            place(faceBottom, w * 0.78f, h * 0.70f, r)
            place(faceRight, w * 0.88f, h * 0.70f, r)
            place(btnL1, w * 0.68f, h * 0.46f, r)
            place(faceTop, w * 0.78f, h * 0.46f, r)
            place(btnR1, w * 0.88f, h * 0.46f, r)
            place(btnOptions, w * 0.50f, h * 0.30f, small)
            place(btnHome, w * 0.50f, h * 0.55f, small)
            place(gear, w * 0.05f, h * 0.90f, small * 0.9f)
        }
    }

    /** Dreamcast: d-pad, analog stick, A B X Y diamond, triggers L and R, Start. */
    private fun layoutDreamcast(w: Float, h: Float) {
        if (h >= w) {
            val unit = min(w, h * 0.46f)
            val small = unit * 0.076f
            val face = unit * 0.078f
            place(btnL2, w * 0.12f, h * 0.09f, small)
            place(btnR2, w * 0.88f, h * 0.09f, small)
            place(dpad, w * 0.26f, h * 0.26f, unit * 0.19f)
            val fx = w * 0.74f
            val fy = h * 0.28f
            val off = unit * 0.14f
            place(faceTop, fx, fy - off, face)
            place(faceRight, fx + off, fy, face)
            place(faceBottom, fx, fy + off, face)
            place(faceLeft, fx - off, fy, face)
            place(leftStick, w * 0.30f, h * 0.56f, unit * 0.155f)
            place(btnOptions, w * 0.72f, h * 0.56f, small)
            place(btnHome, w * 0.72f, h * 0.70f, small)
            place(gear, w * 0.08f, h * 0.92f, small * 0.9f)
        } else {
            val unit = min(h, w * 0.46f)
            val small = unit * 0.076f
            val face = unit * 0.09f
            place(btnL2, w * 0.09f, h * 0.14f, small)
            place(btnR2, w * 0.91f, h * 0.14f, small)
            place(dpad, w * 0.15f, h * 0.36f, unit * 0.20f)
            place(leftStick, w * 0.34f, h * 0.74f, unit * 0.15f)
            val fx = w * 0.85f
            val fy = h * 0.52f
            val off = unit * 0.165f
            place(faceTop, fx, fy - off, face)
            place(faceRight, fx + off, fy, face)
            place(faceBottom, fx, fy + off, face)
            place(faceLeft, fx - off, fy, face)
            place(btnOptions, w * 0.50f, h * 0.22f, small)
            place(btnHome, w * 0.50f, h * 0.50f, small)
            place(gear, w * 0.05f, h * 0.90f, small * 0.9f)
        }
    }

    // --------------------------------------------------------------- drawing

    private val bgPaint = Paint()
    private var bgShaderSkin: Skin? = null
    private var bgShaderHeight = 0

    private fun ensureBackground() {
        if (bgShaderSkin == skin && bgShaderHeight == height) return
        val top = if (skin == Skin.GAMEBOY) gbBgTop else bgTop
        val bottom = if (skin == Skin.GAMEBOY) gbBgBottom else bgBottom
        bgPaint.shader = LinearGradient(
            0f, 0f, 0f, height.toFloat(), top, bottom, Shader.TileMode.CLAMP
        )
        bgShaderSkin = skin
        bgShaderHeight = height
    }

    override fun onDraw(canvas: Canvas) {
        ensureBackground()
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)
        drawBackgroundImage(canvas)
        for (control in activeControls) control.draw(canvas)
        if (isEditingLayout) {
            // Accent ring marks every control as grabbable.
            strokePaint.color = accentColor
            for (control in activeControls) {
                strokePaint.alpha = if (control.pressed) 255 else 120
                canvas.drawCircle(control.cx, control.cy, control.radius * 1.08f, strokePaint)
            }
            strokePaint.alpha = 255
            drawEditUi(canvas)
        }
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        (color and 0x00FFFFFF) or (alpha shl 24)

    /**
     * Center-crop the picture to fill the screen; [bgPanX]/[bgPanY] slide it
     * inside the overflow so the user can frame it however they like. The
     * scrim on top keeps the controls readable over any photo.
     */
    private fun drawBackgroundImage(canvas: Canvas) {
        val bmp = bgBitmap ?: return
        val vw = width.toFloat()
        val vh = height.toFloat()
        if (vw <= 0f || vh <= 0f) return
        val scale = max(vw / bmp.width, vh / bmp.height)
        val dw = bmp.width * scale
        val dh = bmp.height * scale
        val dx = -(dw - vw) * bgPanX
        val dy = -(dh - vh) * bgPanY
        canvas.save()
        canvas.translate(dx, dy)
        canvas.scale(scale, scale)
        canvas.drawBitmap(bmp, 0f, 0f, bgBitmapPaint)
        canvas.restore()
        canvas.drawColor(scrimColor)
    }

    private fun drawArrow(canvas: Canvas, x: Float, y: Float, size: Float, rotation: Float) {
        arrowPath.reset()
        arrowPath.moveTo(0f, -size)
        arrowPath.lineTo(size, size * 0.7f)
        arrowPath.lineTo(-size, size * 0.7f)
        arrowPath.close()
        canvas.save()
        canvas.translate(x, y)
        canvas.rotate(rotation)
        canvas.drawPath(arrowPath, arrowPaint)
        canvas.restore()
    }

    // ----------------------------------------------------------------- touch

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (isPanningBackground) {
            handleBackgroundPanTouch(event)
            return true
        }
        if (isEditingLayout) {
            handleEditTouch(event)
            return true
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val index = event.actionIndex
                val id = event.getPointerId(index)
                // Android reuses pointer ids. If a control is somehow still
                // holding this one, release it first — otherwise two controls
                // share an id, the lift only clears one, and the other stays
                // pressed forever.
                activeControls.firstOrNull { it.pointerId == id }?.let {
                    it.pointerId = -1
                    it.onUp()
                }
                val x = event.getX(index)
                val y = event.getY(index)
                val control = activeControls.firstOrNull { !it.pressed && it.contains(x, y) }
                if (control != null) {
                    control.pointerId = id
                    control.onDown(x, y)
                    invalidate()
                }
            }

            MotionEvent.ACTION_MOVE -> {
                var moved = false
                for (i in 0 until event.pointerCount) {
                    val id = event.getPointerId(i)
                    val control = activeControls.firstOrNull { it.pointerId == id } ?: continue
                    control.onMove(event.getX(i), event.getY(i))
                    moved = true
                }
                if (moved) invalidate()
            }

            MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                // ACTION_UP is the last finger leaving, so nothing may stay
                // held; CANCEL means the gesture was taken away entirely.
                if (event.actionMasked != MotionEvent.ACTION_POINTER_UP) {
                    for (control in activeControls) {
                        if (control.pressed) {
                            control.pointerId = -1
                            control.onUp()
                        }
                    }
                } else {
                    val id = event.getPointerId(event.actionIndex)
                    activeControls.firstOrNull { it.pointerId == id }?.let {
                        it.pointerId = -1
                        it.onUp()
                    }
                }
                invalidate()
            }
        }
        return true
    }

    private var panLastX = 0f
    private var panLastY = 0f

    /** In background-pan mode, a finger drags the picture, not the buttons. */
    private fun handleBackgroundPanTouch(event: MotionEvent) {
        val bmp = bgBitmap ?: return
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                panLastX = event.x
                panLastY = event.y
            }
            MotionEvent.ACTION_MOVE -> {
                val vw = width.toFloat()
                val vh = height.toFloat()
                val scale = max(vw / bmp.width, vh / bmp.height)
                val overflowX = bmp.width * scale - vw
                val overflowY = bmp.height * scale - vh
                if (overflowX > 1f) {
                    bgPanX = (bgPanX - (event.x - panLastX) / overflowX).coerceIn(0f, 1f)
                }
                if (overflowY > 1f) {
                    bgPanY = (bgPanY - (event.y - panLastY) / overflowY).coerceIn(0f, 1f)
                }
                panLastX = event.x
                panLastY = event.y
                invalidate()
            }
        }
    }

    /** In edit mode, fingers drag controls instead of pressing them. */
    private fun handleEditTouch(event: MotionEvent) {
        // MOD: the AJUSTES pill and its panel get the first chance at a tap.
        if (event.actionMasked == MotionEvent.ACTION_DOWN ||
            event.actionMasked == MotionEvent.ACTION_POINTER_DOWN
        ) {
            val idx = event.actionIndex
            if (handleEditUiTap(event.getX(idx), event.getY(idx))) {
                invalidate()
                return
            }
        }
        // While the panel is open, nothing underneath should move.
        if (settingsOpen && activeControls.none { it.pressed }) return

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val index = event.actionIndex
                val x = event.getX(index)
                val y = event.getY(index)
                val control = activeControls.firstOrNull { !it.pressed && it.contains(x, y) }
                if (control != null) {
                    control.pointerId = event.getPointerId(index)
                    haptics.tick()
                    invalidate()
                }
            }

            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.pointerCount) {
                    val id = event.getPointerId(i)
                    val control = activeControls.firstOrNull { it.pointerId == id } ?: continue
                    control.cx = event.getX(i)
                        .coerceIn(safeLeft + control.radius, width - safeRight - control.radius)
                    control.cy = event.getY(i)
                        .coerceIn(safeTop + control.radius, height - safeBottom - control.radius)
                }
                invalidate()
            }

            MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (event.actionMasked == MotionEvent.ACTION_CANCEL) {
                    for (control in activeControls) control.pointerId = -1
                } else {
                    val id = event.getPointerId(event.actionIndex)
                    activeControls.firstOrNull { it.pointerId == id }?.pointerId = -1
                }
                invalidate()
            }
        }
    }

    /**
     * If the window loses focus mid-press — notification shade, incoming call
     * — the matching ACTION_UP may never arrive. Releasing here stops a held
     * button or a deflected stick from being transmitted indefinitely.
     */
    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (!hasWindowFocus) releaseAllPointers()
    }

    private fun notifyChanged() {
        listener?.onTouchStateChanged()
    }
}
