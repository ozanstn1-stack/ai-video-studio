package com.aivideostudio.core.common

import com.aivideostudio.data.local.JsonCodec
import com.aivideostudio.domain.model.HighlightReason
import com.aivideostudio.domain.model.PipelineStage
import com.aivideostudio.domain.model.TranscriptWord
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TimeUtilsTest {

    @Test
    fun `short durations are formatted as minutes and seconds`() {
        assertThat(TimeUtils.formatDuration(0L)).isEqualTo("0:00")
        assertThat(TimeUtils.formatDuration(9_000L)).isEqualTo("0:09")
        assertThat(TimeUtils.formatDuration(65_000L)).isEqualTo("1:05")
        assertThat(TimeUtils.formatDuration(600_000L)).isEqualTo("10:00")
    }

    @Test
    fun `long durations include the hour`() {
        assertThat(TimeUtils.formatDuration(3_725_000L)).isEqualTo("1:02:05")
    }

    @Test
    fun `timestamps keep millisecond precision for the transcript view`() {
        assertThat(TimeUtils.formatTimestamp(3_200L)).isEqualTo("00:03.200")
        assertThat(TimeUtils.formatTimestamp(74_300L)).isEqualTo("01:14.300")
    }

    @Test
    fun `compact durations read naturally`() {
        assertThat(TimeUtils.formatCompactDuration(45_000L)).isEqualTo("45s")
        assertThat(TimeUtils.formatCompactDuration(760_000L)).isEqualTo("12m 40s")
        assertThat(TimeUtils.formatCompactDuration(4_320_000L)).isEqualTo("1h 12m")
    }

    @Test
    fun `negative values never produce nonsense`() {
        assertThat(TimeUtils.formatDuration(-5L)).isEqualTo("0:00")
        assertThat(TimeUtils.formatCompactDuration(-1L)).isEqualTo("0s")
    }
}

class SizeUtilsTest {

    @Test
    fun `bytes are scaled to sensible units`() {
        assertThat(SizeUtils.formatBytes(0L)).isEqualTo("0 B")
        assertThat(SizeUtils.formatBytes(512L)).isEqualTo("512 B")
        assertThat(SizeUtils.formatBytes(1_536L)).isEqualTo("1.5 KB")
        assertThat(SizeUtils.formatBytes(19_768_000L)).isEqualTo("18.9 MB")
    }
}

/**
 * The codec is the boundary between the database and the domain, so corrupted or
 * missing values must degrade instead of throwing.
 */
class JsonCodecTest {

    @Test
    fun `string lists round trip`() {
        val values = listOf("Travel", "HiddenGem", "DJI")
        val encoded = JsonCodec.encodeStrings(values)
        assertThat(JsonCodec.decodeStrings(encoded)).containsExactlyElementsIn(values).inOrder()
    }

    @Test
    fun `an empty list encodes to an empty column`() {
        assertThat(JsonCodec.encodeStrings(emptyList())).isEmpty()
        assertThat(JsonCodec.decodeStrings(null)).isEmpty()
        assertThat(JsonCodec.decodeStrings("")).isEmpty()
    }

    @Test
    fun `corrupted json degrades to an empty list`() {
        assertThat(JsonCodec.decodeStrings("{not json")).isEmpty()
    }

    @Test
    fun `highlight reasons survive the separator encoding`() {
        val reasons = listOf(
            HighlightReason("\uD83C\uDFA4", "Clear speech"),
            HighlightReason("\uD83D\uDD25", "High energy"),
        )
        val encoded = JsonCodec.encodeReasons(reasons)
        val decoded = JsonCodec.decodeReasons(encoded)

        assertThat(decoded).hasSize(2)
        assertThat(decoded[0].badge).isEqualTo("\uD83C\uDFA4")
        assertThat(decoded[0].text).isEqualTo("Clear speech")
        assertThat(decoded[1].text).isEqualTo("High energy")
    }

    @Test
    fun `word timings round trip with their emphasis flag`() {
        val words = listOf(
            TranscriptWord("This", 0L, 300L, emphasis = false),
            TranscriptWord("INCREDIBLE", 300L, 900L, emphasis = true),
        )
        val encoded = JsonCodec.encodeWords(words)
        val decoded = JsonCodec.decodeWords(encoded)

        assertThat(decoded).hasSize(2)
        assertThat(decoded[1].text).isEqualTo("INCREDIBLE")
        assertThat(decoded[1].emphasis).isTrue()
        assertThat(decoded[1].startMs).isEqualTo(300L)
    }

    @Test
    fun `completed pipeline stages round trip`() {
        val stages = listOf(
            PipelineStage.IMPORT,
            PipelineStage.PROBE_VIDEO,
            PipelineStage.SCENE_DETECTION,
        )
        val decoded = JsonCodec.decodeStages(JsonCodec.encodeStages(stages))
        assertThat(decoded).containsExactlyElementsIn(stages).inOrder()
    }

    @Test
    fun `unknown stage names are dropped rather than throwing`() {
        val decoded = JsonCodec.decodeStages("[\"IMPORT\",\"NOT_A_STAGE\"]")
        assertThat(decoded).containsExactly(PipelineStage.IMPORT)
    }
}

/**
 * Progress is computed from the stage weights, so the bar in the analysis screen
 * must move monotonically and end exactly at 100%.
 */
class PipelineStageTest {

    @Test
    fun `completed weight increases with each stage`() {
        val stages = listOf(
            PipelineStage.IMPORT,
            PipelineStage.PROBE_VIDEO,
            PipelineStage.GENERATE_THUMBNAILS,
            PipelineStage.SCENE_DETECTION,
            PipelineStage.AUDIO_ANALYSIS,
            PipelineStage.TRANSCRIPTION,
            PipelineStage.SEMANTIC_ANALYSIS,
            PipelineStage.HIGHLIGHT_DETECTION,
            PipelineStage.SHORT_GENERATION,
            PipelineStage.RENDER,
        )
        val weights = stages.map { PipelineStage.completedWeight(it) }
        assertThat(weights).isInOrder()

        val finalWeight = weights.last()
        assertThat(finalWeight).isGreaterThan(0.9f)
        assertThat(finalWeight).isAtMost(1f)
    }

    @Test
    fun `stage weights cover the whole bar`() {
        val total = PipelineStage.entries
            .filter { it != PipelineStage.DONE }
            .sumOf { it.weight.toDouble() }
        assertThat(total).isWithin(0.0001).of(1.0)
    }

    @Test
    fun `unknown stage names fall back to the first stage`() {
        assertThat(PipelineStage.fromName("nope")).isEqualTo(PipelineStage.IMPORT)
        assertThat(PipelineStage.fromName(null)).isEqualTo(PipelineStage.IMPORT)
    }
}
