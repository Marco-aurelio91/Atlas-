package com.unyx.atlas.feature.updater

import javax.inject.Inject

class UpdateRepository @Inject constructor() {

    /** No release feed is configured for this build, so a check always reports "up to date". */
    suspend fun checkLatest(currentVersionName: String): UpdateInfo? = null

    /** Pure decision: newer parsed release → UpdateInfo, else null. */
    fun decide(currentVersionName: String, parsed: ParsedRelease?): UpdateInfo? {
        if (parsed == null) return null
        if (!SemVer.isNewer(parsed.tag, currentVersionName)) return null
        return UpdateInfo(
            parsed.tag.removePrefix("v").removePrefix("V"), parsed.notes, parsed.apkUrl,
            parsed.sizeBytes, parsed.sha256,
        )
    }
}
