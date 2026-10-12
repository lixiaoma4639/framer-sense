package com.framer.sense.feature.camera.vlm.data

import kotlinx.coroutines.withTimeoutOrNull

internal const val LOCAL_PHOTOGRAPHY_DEADLINE_MS = 120_000L

/** 共用整轮时限，包含排队、首 token、一次生成与预览，不随 token 重置。 */
internal suspend fun <T : Any> withLocalPhotographyDeadline(block: suspend () -> T): T =
    withTimeoutOrNull(LOCAL_PHOTOGRAPHY_DEADLINE_MS) { block() }
        ?: throw ModelFailure("LOCAL_PHOTOGRAPHY_DEADLINE", "", false)
