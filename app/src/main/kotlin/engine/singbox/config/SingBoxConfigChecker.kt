// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package engine.singbox.config

internal object SingBoxConfigChecker {
    fun check(content: String) {
        val root = parseSingBoxJson(content)
        SingBoxDeprecatedConfigValidator.validate(root)
    }

    fun format(content: String): String {
        val source = parseSingBoxJson(content)
        SingBoxDeprecatedConfigValidator.validate(source)
        return encodeSingBoxJson(source)
    }
}
