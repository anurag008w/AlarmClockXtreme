package com.sysadmindoc.alarmclock.ui.components

import com.sysadmindoc.alarmclock.R
import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.UnknownHostException

/**
 * The mapping only, not the copy. The function hands back a resource id now,
 * so the wording lives in strings.xml and the interesting question is which
 * message each failure picks.
 */
class YouTubeDialogErrorMessageTest {

    @Test fun suppressedPrimaryFailureIsClassified() {
        val fallback = IllegalStateException("fallback failed")
        fallback.addSuppressed(UnknownHostException("private-query-secret"))
        assertEquals(R.string.youtube_error_no_connection, youTubeDialogErrorMessage(fallback, YouTubeDialogAction.Search))
    }
    @Test fun olderAndroidPermissionHasSpecificRecovery() {
        assertEquals(R.string.youtube_error_storage_permission, youTubeDialogErrorMessage(
            SecurityException("permission"), YouTubeDialogAction.Download))
    }
    @Test fun wrappedSearchNetworkErrorKeepsNetworkRecovery() {
        assertEquals(R.string.youtube_error_no_connection,
            youTubeDialogErrorMessage(IllegalStateException("Search failed",
                UnknownHostException("youtube.com")), YouTubeDialogAction.Search))
    }

    @Test fun responseCode429IsReportedAsBlockedSearch() {
        assertEquals(R.string.youtube_error_blocked,
            youTubeDialogErrorMessage(IllegalStateException("response code 429"),
                YouTubeDialogAction.Search))
    }

    @Test
    fun networkErrorsUsePlainRecoveryCopy() {
        assertEquals(
            R.string.youtube_error_no_connection,
            youTubeDialogErrorMessage(
                UnknownHostException("Unable to resolve host \"youtube.com\""),
                YouTubeDialogAction.Search
            )
        )
    }

    @Test
    fun httpErrorsDoNotExposeRawStatusText() {
        assertEquals(
            R.string.youtube_error_blocked,
            youTubeDialogErrorMessage(
                IllegalStateException("HTTP 403 Forbidden"),
                YouTubeDialogAction.Preview
            )
        )
    }

    @Test
    fun extractorErrorsSuggestEngineUpdateWithoutRawException() {
        assertEquals(
            R.string.youtube_error_extractor,
            youTubeDialogErrorMessage(
                RuntimeException("ExtractorError: player response is invalid"),
                YouTubeDialogAction.Download
            )
        )
    }

    @Test
    fun unavailableBuildMessageIsClear() {
        assertEquals(
            R.string.youtube_error_unavailable_build,
            youTubeDialogErrorMessage(
                IllegalStateException("YouTube download is not available in this build"),
                YouTubeDialogAction.Download
            )
        )
    }

    @Test
    fun anUnrecognisedFailureFallsBackToTheActionItCameFrom() {
        val plain = RuntimeException("something went sideways")
        assertEquals(
            R.string.youtube_error_preview,
            youTubeDialogErrorMessage(plain, YouTubeDialogAction.Preview)
        )
        assertEquals(
            R.string.youtube_error_search,
            youTubeDialogErrorMessage(plain, YouTubeDialogAction.Search)
        )
        assertEquals(
            R.string.youtube_error_engine_update,
            youTubeDialogErrorMessage(plain, YouTubeDialogAction.EngineUpdate)
        )
    }
}
