package jp.cn22.yomipace

import android.graphics.Canvas
import android.graphics.Paint

/** Disconnected neon segments use GPU line primitives instead of retessellating a large Path. */
internal class EffectLines(capacity: Int=4096) {
    private var points=FloatArray(capacity)
    private var count=0
    private var x=0f
    private var y=0f
    fun reset() { count=0 }
    fun moveTo(px: Float,py: Float) { x=px;y=py }
    fun lineTo(px: Float,py: Float) {
        if(count+4>points.size)points=points.copyOf(points.size*2)
        points[count++]=x;points[count++]=y;points[count++]=px;points[count++]=py;x=px;y=py
    }
    fun draw(canvas: Canvas,paint: Paint) { if(count>0)canvas.drawLines(points,0,count,paint) }
}
