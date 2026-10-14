// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package engine.singbox.config

import kotlinx.serialization.json.JsonObject

internal object SingBoxConfigChecker {
    fun check(content: String) = check(parseSingBoxJson(content))

    fun check(root: JsonObject, @Suppress("UNUSED_PARAMETER") encoded: String? = null) {
        SingBoxDeprecatedConfigValidator.validate(root)
    }

    fun format(content: String): String {
        val source = parseSingBoxJson(content)
        SingBoxDeprecatedConfigValidator.validate(source)
        return encodeSingBoxJson(source)
    }
}
