package com.maik.app.data

import com.maik.app.Fix
import com.maik.app.Stage

/** What the screen should do after a download event, besides changing its stage. */
enum class Effect { None, LoadTarget, ConfirmAndLoad }

/**
 * How a finished or failed download changes what the screen shows.
 *
 * Pure so it can be tested: an event for one model must never knock over a chat that
 * is happily using another.
 */
fun reduceDownload(stage: Stage, targetId: String, event: Download, onSetup: Boolean): Pair<Stage, Effect> =
    when (event) {
        is Download.Progress -> stage to Effect.None

        is Download.Done -> when {
            event.modelId == targetId -> stage to (if (onSetup) Effect.ConfirmAndLoad else Effect.LoadTarget)
            // The screen was showing a download that turned out to be someone else's.
            stage is Stage.Downloading -> stage to Effect.LoadTarget
            else -> stage to Effect.None
        }

        is Download.Failed -> when {
            event.modelId != targetId -> stage to Effect.None
            event.cancelled -> Stage.NeedsModel to Effect.None
            else -> Stage.Broken(event.reason, "", Fix.RESUME_DOWNLOAD) to Effect.None
        }
    }
