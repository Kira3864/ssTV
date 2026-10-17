package com.songshu.sstv.ui

import android.content.Context
import androidx.appcompat.app.AlertDialog
import com.songshu.sstv.data.*

object LibraryActions {
    fun group(c: Context, existing: FavoriteGroup? = null, done: (FavoriteGroup) -> Unit) {
        Dlg.input(c, if (existing == null) "新建分组" else "编辑分组", listOf("分组名", "备注"),
            listOf(existing?.name.orEmpty(), existing?.note.orEmpty())) { values ->
            val result = if (existing == null) LibraryStore.createGroup(c, values[0], values[1]) else {
                LibraryStore.editGroup(c, existing.id, values[0], values[1])
                LibraryStore.groups(c).first { it.id == existing.id }
            }
            done(result)
        }
    }
    fun chooseGroup(c: Context, done: (FavoriteGroup) -> Unit) {
        val groups = LibraryStore.groups(c)
        AlertDialog.Builder(c).setTitle("选择收藏分组")
            .setItems((groups.map { it.name } + "＋ 新建分组").toTypedArray()) { _, index ->
                if (index == groups.size) group(c, done = done) else done(groups[index])
            }.setNegativeButton("取消", null).show()
    }
    fun favorite(c: Context, media: MediaRef, changed: () -> Unit = {}) {
        val existing = LibraryStore.favorites(c).any { it.media.key == media.key }
        if (!existing) chooseGroup(c) { LibraryStore.addFavorite(c, media, it.id); changed() }
        else AlertDialog.Builder(c).setTitle(media.title).setItems(arrayOf("移动到分组", "取消收藏")) { _, i ->
            if (i == 0) chooseGroup(c) { LibraryStore.moveFavorite(c, media.key, it.id); changed() }
            else { LibraryStore.removeFavorite(c, media.key); changed() }
        }.show()
    }
}
