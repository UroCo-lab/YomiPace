package jp.cn22.yomipace

import android.app.Application
import android.graphics.*
import android.view.View
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import jp.cn22.yomipace.core.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DopamineReaderTest {
    private fun reader(dark: Boolean = false): ScriptReaderView {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val script = ScriptParser.parse("#1\n読み上げ原稿の文字は、動く背景よりも手前に描画します。\n予定の進み方と自分の位置を見比べます。")
        return ScriptReaderView(app).apply {
            bind(script, Timeline.build(script,120.0,0.0),18,0.0,ReadingAnchor(),dark)
            measure(View.MeasureSpec.makeMeasureSpec(360,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(400,View.MeasureSpec.EXACTLY))
            layout(0,0,360,400)
        }
    }
    private fun draw(reader: ScriptReaderView): IntArray {
        val bitmap=Bitmap.createBitmap(360,400,Bitmap.Config.ARGB_8888)
        reader.draw(Canvas(bitmap))
        return IntArray(360*400).also { bitmap.getPixels(it,0,360,0,0,360,400);bitmap.recycle() }
    }
    @Test fun animatedBackgroundMovesWhileOpaqueForegroundGlyphsStayFixed() {
        var now=10_000L
        val engine=DopamineEngine({},{}).apply { configure(true,false,true,true,DopamineLevel.at(110.0,120.0)) }
        val reader=reader(); val anchor=reader.logicalAnchorForTest()
        reader.bindDopamine(engine) { now }
        val first=draw(reader);now+=400;val second=draw(reader)
        val ink=Color.rgb(250,253,255)
        val glyph=first.indices.filter { first[it]==ink }
        assertTrue("No opaque foreground glyphs",glyph.size>200)
        glyph.forEach { assertEquals("Effect overpainted foreground glyph at $it",ink,second[it]) }
        assertTrue("Background is static",first.indices.count { first[it]!=second[it] }>3000)
        assertEquals(anchor,reader.logicalAnchorForTest())
    }
    @Test fun disabledOrUnavailableEffectsPreserveTheNormalReaderExactly() {
        for(dark in listOf(false,true)) {
            val reader=reader(dark);val baseline=draw(reader)
            val engine=DopamineEngine({},{}).apply { configure(false,true,true,true,DopamineLevel.at(110.0,120.0)) }
            reader.bindDopamine(engine);assertArrayEquals(baseline,draw(reader))
            engine.configure(true,true,false,true,engine.level);assertArrayEquals(baseline,draw(reader))
        }
    }
}
