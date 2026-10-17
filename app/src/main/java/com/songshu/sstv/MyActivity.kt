package com.songshu.sstv

import android.os.Bundle
import android.view.View
import com.songshu.sstv.ui.BottomNav

class MyActivity : ThemedActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_my)
        findViewById<View>(R.id.btnFavorites).setOnClickListener { startActivity(android.content.Intent(this, LibraryActivity::class.java)) }
        findViewById<View>(R.id.btnLikes).setOnClickListener { startActivity(android.content.Intent(this, LibraryActivity::class.java).putExtra("likes", true)) }
        findViewById<View>(R.id.btnHistory).setOnClickListener { BottomNav.open(this, HistoryActivity::class.java) }
        findViewById<View>(R.id.btnCache).setOnClickListener { BottomNav.open(this, CacheActivity::class.java) }
        findViewById<View>(R.id.btnSettings).setOnClickListener { BottomNav.open(this, SettingsActivity::class.java) }
    }
}
