package com.movieshell.data.novel

import com.movieshell.domain.model.HealthTone
import com.movieshell.domain.model.SourceHealth
import com.movieshell.domain.model.TestSearchResult
import com.movieshell.domain.model.shortLabel
import com.movieshell.domain.model.toSourceHealth
import com.movieshell.domain.model.tone
import org.junit.Assert.assertEquals
import org.junit.Test

/** v1.4.27 解缠②：探/测统一模型——探测文案与颜色口径逐字不变，测试五档有唯一落点。 */
class SourceHealthTest {

    @Test fun probeRecordMapsToSameLabelsAsBefore() {
        // 改版前 bookProbeText：null→未测、FAIL→不通、SLOW→慢 Nms、OK→通 Nms、其他→未测
        assertEquals("未测", (null as BookProbeRecord?).toSourceHealth().shortLabel)
        assertEquals("不通", BookProbeRecord("FAIL", 0L).toSourceHealth().shortLabel)
        assertEquals("慢 4200ms", BookProbeRecord("SLOW", 4200L).toSourceHealth().shortLabel)
        assertEquals("通 120ms", BookProbeRecord("OK", 120L).toSourceHealth().shortLabel)
        assertEquals("未测", BookProbeRecord("WHATEVER", 5L).toSourceHealth().shortLabel)
    }

    @Test fun probeRecordMapsToSameColorsAsBefore() {
        // 改版前 bookProbeDotColor：OK 绿、SLOW 黄、FAIL 红、其他灰
        assertEquals(HealthTone.GOOD, BookProbeRecord("OK", 1L).toSourceHealth().tone)
        assertEquals(HealthTone.WARN, BookProbeRecord("SLOW", 4000L).toSourceHealth().tone)
        assertEquals(HealthTone.BAD, BookProbeRecord("FAIL").toSourceHealth().tone)
        assertEquals(HealthTone.NEUTRAL, (null as BookProbeRecord?).toSourceHealth().tone)
    }

    @Test fun testResultTiers() {
        assertEquals(SourceHealth.Ok(800L), TestSearchResult.Ok(3).toSourceHealth(800L))
        assertEquals(SourceHealth.Slow(3001L), TestSearchResult.Ok(3).toSourceHealth(3001L))
        assertEquals(SourceHealth.Ok(3000L), TestSearchResult.Ok(3).toSourceHealth(3000L))
        assertEquals(SourceHealth.Unverified, TestSearchResult.Zero.toSourceHealth(10L))
        assertEquals(SourceHealth.Fail, TestSearchResult.Failed.toSourceHealth(10L))
        assertEquals(SourceHealth.Timeout, TestSearchResult.Timeout.toSourceHealth(30_000L))
        assertEquals(SourceHealth.Unconfigured, TestSearchResult.NotConfigured.toSourceHealth(0L))
    }

    @Test fun testTiersShareProbeTones() {
        assertEquals(HealthTone.GOOD, TestSearchResult.Ok(1).toSourceHealth(10L).tone)
        assertEquals(HealthTone.WARN, TestSearchResult.Zero.toSourceHealth(10L).tone)
        assertEquals(HealthTone.BAD, TestSearchResult.Failed.toSourceHealth(10L).tone)
        assertEquals(HealthTone.BAD, TestSearchResult.Timeout.toSourceHealth(10L).tone)
        assertEquals(HealthTone.NEUTRAL, TestSearchResult.NotConfigured.toSourceHealth(10L).tone)
    }
}
