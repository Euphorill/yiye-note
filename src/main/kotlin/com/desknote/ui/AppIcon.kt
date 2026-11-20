package com.desknote.ui

import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Image
import java.awt.BasicStroke
import java.awt.Color
import java.awt.RenderingHints
import java.awt.geom.Line2D
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/**
 * 应用图标（C 版：胶带便签）。
 * 同一套绘制逻辑按尺寸缩放，托盘、窗口、任务栏用同一张图。
 */
object AppIcon {
    private const val BASE = 256f
    private const val PAPER = 0xFFF9DF7E.toInt()
    private const val INK = 0xE62E2A24.toInt()

    private val painter: Painter by lazy {
        val png = ByteArrayOutputStream().use { out ->
            ImageIO.write(render(128), "png", out)
            out.toByteArray()
        }
        BitmapPainter(Image.makeFromEncoded(png).toComposeImageBitmap())
    }

    fun painter(): Painter = painter

    fun image(size: Int = 32): BufferedImage = render(size)

    private fun render(size: Int): BufferedImage {
        val s = size / BASE
        val image = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)

        // 纸块
        val tile = RoundRectangle2D.Float(0f, 0f, size.toFloat(), size.toFloat(), 56f * s, 56f * s)
        g.color = Color(PAPER, true)
        g.fill(tile)
        g.color = Color(150, 118, 40, 90)
        g.stroke = BasicStroke(2.5f * s)
        g.draw(tile)

        // 顶部胶带（轻微倾斜）
        val saved = g.transform
        g.translate(128.0 * s, 40.0 * s)
        g.rotate(Math.toRadians(-5.0))
        val tape = RoundRectangle2D.Float(-74f * s, -16f * s, 148f * s, 32f * s, 6f * s, 6f * s)
        g.color = Color(255, 255, 255, 185)
        g.fill(tape)
        g.color = Color(205, 200, 180, 80)
        g.stroke = BasicStroke(2f * s)
        g.draw(tape)
        g.transform = saved

        // 三行墨线
        g.color = Color(INK, true)
        g.stroke = BasicStroke(14f * s, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        g.draw(Line2D.Float(48f * s, 112f * s, 208f * s, 112f * s))
        g.draw(Line2D.Float(48f * s, 148f * s, 208f * s, 148f * s))
        g.draw(Line2D.Float(48f * s, 184f * s, 164f * s, 184f * s))

        g.dispose()
        return image
    }
}
