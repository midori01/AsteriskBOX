// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.outbound

import engine.singbox.config.encodeSingBoxJson
import engine.singbox.config.parseSingBoxJson

internal fun interface SingBoxOutboundConfigFormatter {
    fun format(content: String): String
}

internal object LibboxSingBoxOutboundConfigFormatter : SingBoxOutboundConfigFormatter {
    override fun format(content: String): String = encodeSingBoxJson(parseSingBoxJson(content))
}
