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
@Config(sdk=[34],qualifiers="w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RenderingCacheTest {
    @Test fun drawingCommandsAreReusedAndReplacedWhenTheKeyOrSizeChanges() {
        val cache=DrawCommandCache("test");val bitmap=Bitmap.createBitmap(100,100,Bitmap.Config.ARGB_8888);val canvas=Canvas(bitmap)
        fun draw(key:Long,color:Int,w:Int=100) {
            cache.beginIfChanged(canvas,w,100,key)?.let { c->c.drawColor(color);cache.endRecording() }
            cache.draw(canvas)
        }
        draw(1,Color.RED);draw(1,Color.BLUE)
        assertEquals(1,cache.recordings);assertEquals(Color.RED,bitmap.getPixel(50,50))
        draw(2,Color.BLUE);assertEquals(2,cache.recordings);assertEquals(Color.BLUE,bitmap.getPixel(50,50))
        draw(2,Color.GREEN,90);assertEquals(3,cache.recordings);assertEquals(Color.GREEN,bitmap.getPixel(50,50));bitmap.recycle()
    }
    @Test fun readerRetainsGlyphsAcrossEffectFramesAndRefreshesForClockStyleFontAndAnchor() {
        val script=ScriptParser.parse("#1\n原稿の読みやすさを維持しながら背景だけが動きます。\n次の文へ進みます。\n#2\n後半の原稿です。")
        val timeline=Timeline.build(script,120.0,0.0)
        val reader=ScriptReaderView(ApplicationProvider.getApplicationContext<Application>())
        fun bind(seconds:Double=0.0,font:Int=18,anchor:ReadingAnchor=ReadingAnchor(),dark:Boolean=false) = reader.bind(script,timeline,font,seconds,anchor,dark)
        bind();reader.measure(View.MeasureSpec.makeMeasureSpec(360,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(400,View.MeasureSpec.EXACTLY));reader.layout(0,0,360,400)
        val engine=DopamineEngine({},{}).apply { configure(true,false,true,true,DopamineLevel.at(100.0,120.0)) }
        var now=10000L;reader.bindDopamine(engine) { now }
        val bitmap=Bitmap.createBitmap(360,400,Bitmap.Config.ARGB_8888)
        repeat(60) { now+=17;reader.draw(Canvas(bitmap)) }
        assertEquals(1,reader.foregroundRecordings);assertEquals(1,reader.plateRecordings)
        bind(120.0);reader.draw(Canvas(bitmap));assertEquals(2,reader.foregroundRecordings)
        engine.configure(false,false,true,false,engine.level);reader.draw(Canvas(bitmap));assertEquals(3,reader.foregroundRecordings)
        bind(120.0,dark=true);reader.draw(Canvas(bitmap));assertEquals(4,reader.foregroundRecordings)
        bind(120.0,22);reader.draw(Canvas(bitmap));assertEquals(5,reader.foregroundRecordings)
        bind(120.0,22,ReadingAnchor(script.items.last().id,0));reader.draw(Canvas(bitmap));assertEquals(6,reader.foregroundRecordings)
        bitmap.recycle()
    }
    @Test fun rootReusesTheFieldForSlidePassAndRebuildsClippingOnlyForLayoutChanges() {
        val renderer=DopamineRenderer();val bitmap=Bitmap.createBitmap(360,800,Bitmap.Config.ARGB_8888)
        val engine=DopamineEngine({},{}).apply { configure(true,false,true,true,DopamineLevel.at(90.0,100.0)) }
        val clock=listOf(RectF(0f,200f,360f,320f));val slides=listOf(RectF(0f,0f,360f,190f))
        renderer.draw(Canvas(bitmap),360f,800f,1f,10000,engine,clock,slides)
        assertEquals(1,renderer.fieldRecordings);assertEquals(1,renderer.clipBuilds)
        renderer.draw(Canvas(bitmap),360f,800f,1f,10017,engine,clock,slides)
        assertEquals(2,renderer.fieldRecordings);assertEquals(1,renderer.clipBuilds)
        renderer.draw(Canvas(bitmap),360f,800f,1f,10017,engine,clock,listOf(RectF(0f,0f,360f,180f)))
        assertEquals(2,renderer.fieldRecordings);assertEquals(2,renderer.clipBuilds);bitmap.recycle()
    }
    @Test fun sharedFramesNotifyOnceAndUnsubscriptionStopsDelivery() {
        val engine=DopamineEngine({},{});var calls=0;val listener:()->Unit={calls++}
        engine.addFrameListener(listener);engine.addFrameListener(listener);engine.dispatchFrame(1000)
        assertEquals(1,calls);assertEquals(1000L,engine.frameTime)
        engine.removeFrameListener(listener);engine.dispatchFrame(1017);assertEquals(1,calls)
    }
}
