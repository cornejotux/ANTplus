package cl.cornejotux.antplus

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import kotlin.math.max
import kotlin.math.min

class HeartRateChartView(context: Context) : View(context) {
    private val points = ArrayDeque<Pair<Long, Int>>()
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(21, 101, 192); strokeWidth = 5f; style = Paint.Style.STROKE }
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.LTGRAY; strokeWidth = 1f }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.DKGRAY; textSize = 28f }

    fun add(timestamp: Long, bpm: Int) {
        points.addLast(timestamp to bpm)
        val cutoff = timestamp - 5 * 60 * 1000L
        while (points.firstOrNull()?.first ?: timestamp < cutoff) points.removeFirst()
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat(); val h = height.toFloat()
        canvas.drawColor(Color.WHITE)
        for (i in 1..4) canvas.drawLine(0f, h * i / 5f, w, h * i / 5f, grid)
        if (points.isEmpty()) { canvas.drawText("Esperando datos...", 24f, h / 2f, text); return }
        val minBpm = max(40, (points.minOf { it.second } / 10) * 10 - 10)
        val maxBpm = max(minBpm + 40, (points.maxOf { it.second } / 10) * 10 + 10)
        val start = points.first().first; val end = max(start + 1, points.last().first)
        var previous: Pair<Float, Float>? = null
        for ((time, value) in points) {
            val x = if (end == start) w else ((time - start).toFloat() / (end - start)) * w
            val y = h - ((value - minBpm).toFloat() / (maxBpm - minBpm)) * h
            previous?.let { canvas.drawLine(it.first, it.second, x, y, line) }
            previous = x to min(h, max(0f, y))
        }
        canvas.drawText("$minBpm", 8f, h - 8f, text)
        canvas.drawText("$maxBpm BPM", 8f, 28f, text)
    }
}
