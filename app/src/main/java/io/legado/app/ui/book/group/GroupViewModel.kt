package io.legado.app.ui.book.group

import android.app.Application
import io.legado.app.base.BaseViewModel
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookGroup
import io.legado.app.utils.toastOnUi
import splitties.init.appCtx

class GroupViewModel(application: Application) : BaseViewModel(application) {

    fun upGroup(vararg bookGroup: BookGroup, finally: (() -> Unit)? = null) {
        execute {
            appDb.bookGroupDao.update(*bookGroup)
        }.onFinally {
            finally?.invoke()
        }
    }

    fun addGroup(
        groupName: String,
        bookSort: Int,
        enableRefresh: Boolean,
        cover: String?,
        finally: () -> Unit
    ) {
        execute {
            // Allocation and insertion must not race Reader's group importer.
            appDb.runInTransaction {
                val groupId = appDb.bookGroupDao.getUnusedId()
                val bookGroup = BookGroup(
                    groupId = groupId,
                    groupName = groupName,
                    cover = cover,
                    bookSort = bookSort,
                    enableRefresh = enableRefresh,
                    order = appDb.bookGroupDao.maxOrder.plus(1)
                )
                appDb.bookGroupDao.getByID(groupId) ?: appDb.bookDao.removeGroup(groupId)
                appDb.bookGroupDao.insert(bookGroup)
            }
        }.onError {
            appCtx.toastOnUi(it.localizedMessage ?: "添加分组失败")
        }.onFinally {
            finally()
        }
    }

    fun delGroup(bookGroup: BookGroup, finally: () -> Unit) {
        execute {
            appDb.runInTransaction {
                appDb.bookGroupDao.delete(bookGroup)
                appDb.bookDao.removeGroup(bookGroup.groupId)
            }
        }.onFinally {
            finally()
        }
    }


}
