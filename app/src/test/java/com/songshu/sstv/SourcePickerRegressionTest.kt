package com.songshu.sstv

import android.app.Application
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import com.songshu.sstv.data.Source
import com.songshu.sstv.ui.SourcePicker
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class SourcePickerRegressionTest {
    private fun views(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()

    @Test fun sourceSheetFiltersNamesAndReturnsTheChosenEndpoint() {
        val controller = Robolectric.buildActivity(MyActivity::class.java).setup()
        try {
            val first = Source("片源甲", "https://a.example")
            val second = Source("片源乙", "https://b.example")
            var selected: Source? = null
            SourcePicker.show(controller.get(), listOf(first, second), first.api) { selected = it }
            val dialog = ShadowDialog.getLatestDialog()
            val root = dialog.findViewById<View>(android.R.id.content)
            views(root).filterIsInstance<EditText>().single().setText("乙")
            val options = views(root).filterIsInstance<TextView>().filter { it.text.toString().startsWith("片源") }
            assertEquals(1, options.size)
            options.single().performClick()
            assertEquals(second, selected)
            assertFalse(dialog.isShowing)
        } finally { controller.pause().stop().destroy() }
    }
}
