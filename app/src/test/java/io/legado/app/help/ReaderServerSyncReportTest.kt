package io.legado.app.help

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderServerSyncReportTest {
    @Test
    fun unsupportedWorkDoesNotTurnTotalFailureIntoPartialSuccess() {
        val report = ReaderServerSyncReport(listOf(
            ReaderServerSyncItem.skipped("书源", "新版 Reader 不支持"),
            ReaderServerSyncItem.failed("书架", "登录失败")
        ))
        assertEquals(ReaderServerSyncReport.Outcome.FAILURE, report.outcome)
        assertTrue(report.summary().contains("书源（跳过）：新版 Reader 不支持"))
        assertTrue(report.summary().contains("书架（失败）：登录失败"))
    }

    @Test
    fun unsupportedAndDisabledOnlyIsSkipped() {
        val report = ReaderServerSyncReport(listOf(
            ReaderServerSyncItem.skipped("书源", "新版 Reader 不支持"),
            ReaderServerSyncItem.skipped("书架", "已关闭同步")
        ))
        assertEquals(ReaderServerSyncReport.Outcome.SKIPPED, report.outcome)
        assertEquals(ReaderServerSyncReport.Outcome.SKIPPED, ReaderServerSyncReport(emptyList()).outcome)
    }

    @Test
    fun successWithNoChangesIsStillACompletedSync() {
        val report = ReaderServerSyncReport(listOf(
            ReaderServerSyncItem.completed("书架", "上传: 0, 下载: 0, 更新: 0"),
            ReaderServerSyncItem.skipped("分组", "新版 Reader 不支持")
        ))
        assertEquals(ReaderServerSyncReport.Outcome.SUCCESS, report.outcome)
    }

    @Test
    fun completedAndFailedTasksArePartialFailure() {
        val report = ReaderServerSyncReport(listOf(
            ReaderServerSyncItem.completed("书架", "下载: 2"),
            ReaderServerSyncItem.failed("书源", "上传失败")
        ))
        assertEquals(ReaderServerSyncReport.Outcome.PARTIAL_FAILURE, report.outcome)
    }

    @Test
    fun perBookFailureRetainsSuccessfulChangesInTheSameTask() {
        val report = ReaderServerSyncReport(listOf(
            ReaderServerSyncItem.failed("书架", "下载: 2；同名冲突", hasSuccessfulChanges = true)
        ))
        assertEquals(ReaderServerSyncReport.Outcome.PARTIAL_FAILURE, report.outcome)
    }
}
