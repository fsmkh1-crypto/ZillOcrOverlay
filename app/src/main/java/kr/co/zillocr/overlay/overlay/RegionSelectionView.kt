package kr.co.zillocr.overlay.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.min

class RegionSelectionView(
    context: Context,
    private val onSelected: (RectF) -> Unit,
    private val onCancelled: () -> Unit
) : View(context) {

    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.YELLOW
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }
    private val shadePaint = Paint().apply {
        color = 0x44000000
        style = Paint.Style.FILL
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 46f
        setShadowLayer(4f, 0f, 2f, Color.BLACK)
    }

    private var startX = 0f
    private var startY = 0f
    private var currentX = 0f
    private var currentY = 0f
    private var dragging = false

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), shadePaint)
        canvas.drawText("OCR할 일본어 영역을 드래그하세요", 32f, 80f, textPaint)

        if (dragging) {
            val rect = currentRect()
            canvas.drawRect(rect, outlinePaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.x
                startY = event.y
                currentX = event.x
                currentY = event.y
                dragging = true
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                currentX = event.x
                currentY = event.y
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                currentX = event.x
                currentY = event.y
                val rect = currentRect()
                dragging = false
                invalidate()

                if (rect.width() < 80f || rect.height() < 50f) {
                    onCancelled()
                } else {
                    onSelected(
                        RectF(
                            rect.left / width,
                            rect.top / height,
                            rect.right / width,
                            rect.bottom / height
                        )
                    )
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                dragging = false
                onCancelled()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun currentRect(): RectF = RectF(
        min(startX, currentX),
        min(startY, currentY),
        max(startX, currentX),
        max(startY, currentY)
    )
}
