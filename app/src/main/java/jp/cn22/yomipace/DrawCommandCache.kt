package jp.cn22.yomipace

import android.graphics.Canvas
import android.graphics.Picture
import android.graphics.RenderNode
import android.os.Build
import androidx.annotation.RequiresApi

/** Retain drawing commands, not a full-size bitmap. Android 8/9 and software canvases use Picture. */
internal class DrawCommandCache(private val name: String) {
    private val picture=Picture()
    private var hardware: Hardware?=null
    private var useHardware=false
    private var width=-1
    private var height=-1
    private var signature=Long.MIN_VALUE
    var recordings=0;private set

    fun beginIfChanged(canvas: Canvas,w: Int,h: Int,key: Long): Canvas? {
        val hw=Build.VERSION.SDK_INT>=29 && canvas.isHardwareAccelerated
        if(width==w && height==h && signature==key && useHardware==hw)return null
        width=w;height=h;signature=key;useHardware=hw;recordings++
        return if(hw && Build.VERSION.SDK_INT>=29) {
            val target=hardware ?: Hardware(name).also { hardware=it }
            target.begin(w,h)
        } else picture.beginRecording(w,h)
    }
    fun endRecording() {
        if(useHardware && Build.VERSION.SDK_INT>=29)hardware!!.end() else picture.endRecording()
    }
    fun draw(canvas: Canvas) {
        if(useHardware && Build.VERSION.SDK_INT>=29)hardware!!.draw(canvas) else canvas.drawPicture(picture)
    }
    @RequiresApi(29) private class Hardware(name: String) {
        private val node=RenderNode(name)
        fun begin(w: Int,h: Int): Canvas { node.setPosition(0,0,w,h);return node.beginRecording(w,h) }
        fun end()=node.endRecording()
        fun draw(canvas: Canvas)=canvas.drawRenderNode(node)
    }
}
