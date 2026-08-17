// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package app.modes

const val RunModeTproxy = 1
const val RunModeEbpf = 2

fun Int.isRootRunMode(): Boolean {
    return this == RunModeTproxy ||
        this == RunModeEbpf
}

fun normalizeRunMode(value: Int): Int = when (value) {
    RunModeTproxy,
    RunModeEbpf,
    -> value

    else -> RunModeEbpf
}

const val SingBoxModeRule = 0
const val SingBoxModeGlobal = 1
const val SingBoxModeDirect = 2

const val ProxyAppListModeBlacklist = 0
const val ProxyAppListModeWhitelist = 1
const val ProxyAppListModeGlobal = 2

const val SingBoxProxyLayoutAuto = 0
const val SingBoxProxyLayoutSingle = 1
const val SingBoxProxyLayoutDouble = 2
const val SingBoxProxyLayoutMultiple = 3

const val SingBoxProxySortDefault = 0
const val SingBoxProxySortName = 1
const val SingBoxProxySortDelay = 2

const val OutboundListLayoutAuto = 0
const val OutboundListLayoutSingle = 1
const val OutboundListLayoutDouble = 2
const val OutboundListLayoutMultiple = 3

const val OutboundListSortDefault = 0
const val OutboundListSortName = 1
const val OutboundListSortLatency = 2
const val OutboundListSortType = 3
