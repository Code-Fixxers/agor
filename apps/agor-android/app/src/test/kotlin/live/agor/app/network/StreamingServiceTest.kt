package live.agor.app.network

import live.agor.app.models.SessionStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingServiceTest {
    @Test
    fun streamSampleIntervalMatchesSpecTarget() {
        assertTrue(StreamingService.STREAM_SAMPLE_INTERVAL_MS == 50L)
    }

    @Test
    fun clearsStreamsWhenSessionLeavesActiveStatus() {
        assertFalse(shouldClearStreamsForSessionStatus(SessionStatus.RUNNING))
        assertFalse(shouldClearStreamsForSessionStatus(SessionStatus.STOPPING))
        assertFalse(shouldClearStreamsForSessionStatus(SessionStatus.AWAITING_PERMISSION))
        assertFalse(shouldClearStreamsForSessionStatus(SessionStatus.AWAITING_INPUT))

        assertTrue(shouldClearStreamsForSessionStatus(SessionStatus.IDLE))
        assertTrue(shouldClearStreamsForSessionStatus(SessionStatus.COMPLETED))
        assertTrue(shouldClearStreamsForSessionStatus(SessionStatus.FAILED))
        assertTrue(shouldClearStreamsForSessionStatus(SessionStatus.TIMED_OUT))
    }
}
