package com.songshu.sstv

import android.app.Application
import android.view.View
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "zh-rCN-w393dp-h851dp-port-xhdpi")
class PlayerLayoutRegressionTest {
    @Test fun portraitVideoIsAtTopWithDetailsAndNavigationBelow() {
        ThemeStore.setReducedMotion(RuntimeEnvironment.getApplication(), true)
        val controller = Robolectric.buildActivity(PlayerActivity::class.java).setup()
        try {
            val activity = controller.get()
            val root = activity.findViewById<android.view.ViewGroup>(android.R.id.content).getChildAt(0)
            val width = 786; val height = 1702
            root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, width, height)
            val stage = activity.findViewById<View>(R.id.videoStage)
            val details = activity.findViewById<View>(R.id.playerDetails)
            val nav = activity.findViewById<View>(R.id.playerNavigation)
            assertEquals(0, stage.top)
            assertEquals(width * 9f / 16, stage.height.toFloat(), 2f)
            assertEquals(stage.bottom, details.top)
            assertEquals(View.VISIBLE, nav.visibility)
            val tabs = nav as android.widget.LinearLayout
            assertEquals(4, tabs.childCount)
            assertTrue(tabs.getChildAt(2).isSelected)
            tabs.getChildAt(3).performClick()
            assertEquals(MyActivity::class.java.name,
                org.robolectric.Shadows.shadowOf(activity).nextStartedActivity.component!!.className)
            assertTrue(details.height > stage.height)
            assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.watchFind).visibility)
            val next = android.content.Intent(activity, PlayerActivity::class.java)
                .putExtra("title", "新影片")
                .putStringArrayListExtra("eps", arrayListOf("第一集\u0001file:/example.mp4"))
            controller.newIntent(next)
            assertEquals("新影片", activity.findViewById<android.widget.TextView>(R.id.watchTitle).text.toString())
            assertEquals(View.GONE, activity.findViewById<View>(R.id.watchFind).visibility)
            RuntimeEnvironment.setQualifiers("zh-rCN-w851dp-h393dp-land-xhdpi")
            activity.onConfigurationChanged(activity.resources.configuration)
            assertEquals(View.GONE, details.visibility)
            assertEquals(View.GONE, nav.visibility)
            assertEquals(-1, stage.layoutParams.height)
        } finally { controller.pause().stop().destroy() }
    }
}
