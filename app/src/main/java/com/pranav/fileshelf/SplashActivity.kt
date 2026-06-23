package com.pranav.fileshelf

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import androidx.core.animation.doOnEnd
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlin.math.cos
import kotlin.math.sin

/**
 * Native splash screen with file stacking animation.
 * Replicates the HTML design in pure Kotlin with Canvas rendering.
 * Total animation: 3.5s, transitions to MainActivity at 3.5s.
 */
class SplashActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Edge-to-edge immersive display
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        
        val splashView = SplashAnimationView(this)
        setContentView(splashView)
        
        // Start animation and transition to MainActivity after 3.5s
        splashView.startAnimation {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            // Smooth transition without default activity animation (API 34+ uses overrideActivityTransition)
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }
}

/**
 * Custom view that renders the splash animation using Canvas.
 * Pure hardware-accelerated drawing, no XML layouts or WebView overhead.
 */
private class SplashAnimationView(context: android.content.Context) : View(context) {
    
    // Animation duration constants (milliseconds)
    private val ANIMATION_DURATION = 3500L
    
    // Colors matching app aesthetic
    private val COLOR_BACKGROUND = android.graphics.Color.parseColor("#FFFFFF")
    private val COLOR_GLASS = android.graphics.Color.parseColor("#F5FFFFFF")
    private val COLOR_BORDER = android.graphics.Color.parseColor("#0F000000")
    private val COLOR_ACCENT = android.graphics.Color.parseColor("#007AFF")
    private val COLOR_SHELF = android.graphics.Color.parseColor("#E6E6EB")
    private val COLOR_TEXT = android.graphics.Color.parseColor("#1C1C1E")
    
    // File card data
    private data class FileCard(
        var x: Float = 0f,
        var y: Float = 0f,
        var scale: Float = 0f,
        var rotation: Float = 0f,
        var alpha: Int = 0,
        val label: String,
        val finalOffsetY: Float // Final stacking offset
    )
    
    private val files = listOf(
        FileCard(label = "DOC", finalOffsetY = 0f),
        FileCard(label = "PDF", finalOffsetY = -12f),
        FileCard(label = "IMG", finalOffsetY = -24f),
        FileCard(label = "MP4", finalOffsetY = -36f)
    )
    
    // Shelf and brand state
    private var shelfAlpha = 0
    private var shelfScale = 0.9f
    private var brandAlpha = 0
    private var brandOffsetY = 8f
    private var fadeOutAlpha = 255
    
    // Paint objects (reusable for performance)
    private val filePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_GLASS
        style = Paint.Style.FILL
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_BORDER
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.parseColor("#0A000000")
        style = Paint.Style.FILL
    }
    private val accentLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.parseColor("#33007AFF")
        style = Paint.Style.FILL
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_ACCENT
        textSize = 32f
        typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val shelfPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_SHELF
        style = Paint.Style.FILL
    }
    private val brandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_TEXT
        textSize = 56f
        typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val accentDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_ACCENT
        textSize = 56f
        typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
        textAlign = Paint.Align.LEFT
    }
    // Declared at class level — allocating Paint inside onDraw causes GC pressure
    // on every frame of the 3.5s animation, producing jank on mid-range devices.
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.parseColor("#0A007AFF")
        style = Paint.Style.FILL
    }
    
    private val fileRect = RectF()
    private val shelfRect = RectF()
    
    init {
        setBackgroundColor(COLOR_BACKGROUND)
    }
    
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        val centerX = width / 2f
        val centerY = height / 2f
        
        // Draw ambient glow (subtle radial gradient effect via layered circles)
        drawAmbientGlow(canvas, centerX, centerY)
        
        // Draw glass shelf
        drawShelf(canvas, centerX, centerY + 145f)
        
        // Draw file cards
        files.forEach { file ->
            drawFileCard(canvas, file, centerX, centerY)
        }
        
        // Draw brand text
        drawBrand(canvas, centerX, centerY + 230f)
        
        // Final fade-out overlay
        if (fadeOutAlpha < 255) {
            canvas.drawColor(android.graphics.Color.argb(255 - fadeOutAlpha, 255, 255, 255))
        }
    }
    
    private fun drawAmbientGlow(canvas: Canvas, centerX: Float, centerY: Float) {
        canvas.drawCircle(centerX, centerY, 200f, glowPaint)
    }
    
    private fun drawFileCard(canvas: Canvas, file: FileCard, centerX: Float, centerY: Float) {
        if (file.alpha == 0) return
        
        canvas.save()
        
        // Apply transformations
        canvas.translate(centerX + file.x, centerY + file.y)
        canvas.rotate(file.rotation)
        canvas.scale(file.scale, file.scale)
        
        val fileWidth = 136f
        val fileHeight = 176f
        val cornerRadius = 28f
        
        fileRect.set(-fileWidth / 2, -fileHeight / 2, fileWidth / 2, fileHeight / 2)
        
        // Draw shadow
        shadowPaint.alpha = (file.alpha * 0.4f).toInt()
        canvas.drawRoundRect(
            fileRect.left, fileRect.top + 4f, fileRect.right, fileRect.bottom + 4f,
            cornerRadius, cornerRadius, shadowPaint
        )
        
        // Draw glass card background
        filePaint.alpha = file.alpha
        canvas.drawRoundRect(fileRect, cornerRadius, cornerRadius, filePaint)
        
        // Draw border
        borderPaint.alpha = file.alpha
        canvas.drawRoundRect(fileRect, cornerRadius, cornerRadius, borderPaint)
        
        // Draw accent line at bottom
        accentLinePaint.alpha = (file.alpha * 0.3f).toInt()
        val lineRect = RectF(-26f, fileHeight / 2 - 36f, 26f, fileHeight / 2 - 30f)
        canvas.drawRoundRect(lineRect, 4f, 4f, accentLinePaint)
        
        // Draw file label
        textPaint.alpha = (file.alpha * 0.9f).toInt()
        canvas.drawText(file.label, 0f, -16f, textPaint)
        
        canvas.restore()
    }
    
    private fun drawShelf(canvas: Canvas, centerX: Float, centerY: Float) {
        if (shelfAlpha == 0) return
        
        canvas.save()
        canvas.translate(centerX, centerY)
        canvas.scale(shelfScale, shelfScale)
        
        val shelfWidth = 260f
        val shelfHeight = 12f
        val cornerRadius = 8f
        
        shelfRect.set(-shelfWidth / 2, -shelfHeight / 2, shelfWidth / 2, shelfHeight / 2)
        
        // Draw shelf shadow
        shadowPaint.alpha = (shelfAlpha * 0.2f).toInt()
        canvas.drawRoundRect(
            shelfRect.left, shelfRect.top + 3f, shelfRect.right, shelfRect.bottom + 3f,
            cornerRadius, cornerRadius, shadowPaint
        )
        
        // Draw shelf
        shelfPaint.alpha = shelfAlpha
        canvas.drawRoundRect(shelfRect, cornerRadius, cornerRadius, shelfPaint)
        
        canvas.restore()
    }
    
    private fun drawBrand(canvas: Canvas, centerX: Float, centerY: Float) {
        if (brandAlpha == 0) return
        
        brandPaint.alpha = brandAlpha
        accentDotPaint.alpha = brandAlpha
        
        val brandText = "FileShelf"
        val textWidth = brandPaint.measureText(brandText)
        
        canvas.drawText(brandText, centerX, centerY + brandOffsetY, brandPaint)
        canvas.drawText(".", centerX + textWidth / 2, centerY + brandOffsetY, accentDotPaint)
    }
    
    fun startAnimation(onComplete: () -> Unit) {
        val animators = mutableListOf<Animator>()
        
        // File animations (0-2.6s)
        files.forEachIndexed { index, file ->
            animators.add(createFileAnimation(file, index))
        }
        
        // Shelf animation (1.0-1.6s)
        animators.add(createShelfAnimation())
        
        // Brand animation (2.3-2.9s)
        animators.add(createBrandAnimation())
        
        // Fade-out animation (3.1-3.5s)
        animators.add(createFadeOutAnimation())
        
        // Run all animations together
        val masterSet = AnimatorSet()
        masterSet.playTogether(animators)
        masterSet.duration = ANIMATION_DURATION
        masterSet.doOnEnd { onComplete() }
        masterSet.start()
    }
    
    private fun createFileAnimation(file: FileCard, index: Int): Animator {
        // Initial positions (entering from edges)
        val startPositions = listOf(
            Triple(-120f, -240f, -4f), // DOC: from top-left
            Triple(120f, -200f, 4f),   // PDF: from top-right
            Triple(-140f, 40f, -3f),   // IMG: from bottom-left
            Triple(140f, 60f, 3f)      // MP4: from bottom-right
        )
        
        val (startX, startY, startRot) = startPositions[index]
        
        val animator = ValueAnimator.ofFloat(0f, 1f)
        animator.addUpdateListener { anim ->
            val progress = anim.animatedValue as Float
            
            when {
                // Phase 1: Enter from edges (0-20%)
                progress < 0.20f -> {
                    val p = progress / 0.20f
                    file.x = startX * (1 - p * 0.5f)
                    file.y = startY * (1 - p * 0.33f)
                    file.scale = 0.9f + p * 0.12f
                    file.rotation = startRot * (1 - p * 0.5f)
                    file.alpha = (255 * p).toInt()
                }
                // Phase 2: Hold/hover (20-40%)
                progress < 0.40f -> {
                    val p = (progress - 0.20f) / 0.20f
                    file.x = startX * (0.5f - p * 0.1f)
                    file.y = startY * (0.67f - p * 0.05f)
                    file.scale = 1.02f - p * 0.02f
                    file.rotation = startRot * (0.5f - p * 0.5f)
                    file.alpha = 255
                }
                // Phase 3: Converge and drop (40-65%)
                progress < 0.65f -> {
                    val p = (progress - 0.40f) / 0.25f
                    file.x = file.x * (1 - p)
                    file.y = easeOutBack(p) * file.finalOffsetY
                    file.scale = 1f - p * 0.05f
                    file.rotation = 0f
                    file.alpha = 255
                }
                // Phase 4: Spring bounce (65-75%)
                progress < 0.75f -> {
                    val p = (progress - 0.65f) / 0.10f
                    file.x = 0f
                    file.y = file.finalOffsetY + sin(p * Math.PI).toFloat() * -4f
                    file.scale = 0.95f + p * 0.05f
                    file.rotation = 0f
                    file.alpha = 255
                }
                // Phase 5: Settle (75-100%)
                else -> {
                    file.x = 0f
                    file.y = file.finalOffsetY
                    file.scale = 1f
                    file.rotation = 0f
                    file.alpha = 255
                }
            }
            
            invalidate()
        }
        animator.interpolator = DecelerateInterpolator()
        return animator
    }
    
    private fun createShelfAnimation(): Animator {
        val animator = ValueAnimator.ofFloat(0f, 1f)
        animator.addUpdateListener { anim ->
            val progress = anim.animatedValue as Float
            
            when {
                progress < 0.30f -> {
                    shelfAlpha = 0
                    shelfScale = 0.9f
                }
                progress < 0.45f -> {
                    val p = (progress - 0.30f) / 0.15f
                    shelfAlpha = (255 * p).toInt()
                    shelfScale = 0.9f + p * 0.12f
                }
                else -> {
                    shelfAlpha = 255
                    shelfScale = 1.02f - (progress - 0.45f) / 0.55f * 0.02f
                }
            }
            
            invalidate()
        }
        return animator
    }
    
    private fun createBrandAnimation(): Animator {
        val animator = ValueAnimator.ofFloat(0f, 1f)
        animator.addUpdateListener { anim ->
            val progress = anim.animatedValue as Float
            
            when {
                progress < 0.65f -> {
                    brandAlpha = 0
                    brandOffsetY = 8f
                }
                progress < 0.80f -> {
                    val p = (progress - 0.65f) / 0.15f
                    brandAlpha = (255 * p).toInt()
                    brandOffsetY = 8f * (1 - p)
                }
                else -> {
                    brandAlpha = 255
                    brandOffsetY = 0f
                }
            }
            
            invalidate()
        }
        return animator
    }
    
    private fun createFadeOutAnimation(): Animator {
        val animator = ValueAnimator.ofInt(255, 0)
        animator.addUpdateListener { anim ->
            val progress = (anim.animatedValue as Int) / 255f
            
            if (progress < 0.89f) {
                fadeOutAlpha = 255
            } else {
                fadeOutAlpha = ((1f - (progress - 0.89f) / 0.11f) * 255).toInt()
            }
            
            invalidate()
        }
        return animator
    }
    
    // Easing function for spring bounce effect
    private fun easeOutBack(t: Float): Float {
        val c1 = 1.70158f
        val c3 = c1 + 1f
        return 1f + c3 * Math.pow((t - 1).toDouble(), 3.0).toFloat() + c1 * Math.pow((t - 1).toDouble(), 2.0).toFloat()
    }
}
