package io.github.muhammedelsami.storepilot.engine.report

import io.github.muhammedelsami.storepilot.api.Artifact
import io.github.muhammedelsami.storepilot.api.LocaleTag
import io.github.muhammedelsami.storepilot.api.Problem
import io.github.muhammedelsami.storepilot.api.Release
import io.github.muhammedelsami.storepilot.api.ReleaseStatus
import io.github.muhammedelsami.storepilot.api.Rollout
import io.github.muhammedelsami.storepilot.api.StoreId
import io.github.muhammedelsami.storepilot.api.Track
import io.github.muhammedelsami.storepilot.engine.Change
import io.github.muhammedelsami.storepilot.engine.StoreResult
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class TextReportTest {
    @Test
    fun `renders uploads, release updates, and warnings`() {
        val result = StoreResult(
            store = StoreId("google-play"),
            packageName = "com.example.app",
            committed = true,
            changes = listOf(
                Change.ArtifactUpload(Artifact.of(Path.of("build/app-release.aab")), 42),
                Change.ReleaseUpdate(
                    Track.PRODUCTION,
                    before = listOf(Release(listOf(41), ReleaseStatus.COMPLETED)),
                    after = Release(
                        listOf(42),
                        ReleaseStatus.IN_PROGRESS,
                        Rollout(0.1),
                        mapOf(LocaleTag("en-US") to "Fixes.", LocaleTag("tr-TR") to "Düzeltmeler."),
                        name = "4.2",
                    ),
                ),
            ),
            warnings = listOf(Problem.warning("Something was skipped.", "stores.google-play")),
        )

        assertEquals(
            """
            google-play (com.example.app): committed
              upload app-release.aab, version code 42
              track production: version code 42, inProgress 10%, name '4.2', release notes: en-US, tr-TR
                before: version code 41, completed
              stores.google-play: warning: Something was skipped.

            """.trimIndent(),
            TextReport.render(listOf(result)),
        )
    }

    @Test
    fun `dry run shows a new release and an empty track`() {
        val result = StoreResult(
            store = StoreId("google-play"),
            packageName = "com.example.app",
            committed = false,
            changes = listOf(Change.ReleaseUpdate(Track.INTERNAL, emptyList(), Release(emptyList(), ReleaseStatus.COMPLETED))),
            warnings = emptyList(),
        )

        assertEquals(
            """
            google-play (com.example.app): dry run, nothing committed
              track internal: new release, completed
                before: no releases

            """.trimIndent(),
            TextReport.render(listOf(result)),
        )
    }
}
