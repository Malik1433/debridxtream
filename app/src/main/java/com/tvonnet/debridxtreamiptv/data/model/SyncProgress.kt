package com.tvonnet.debridxtreamiptv.data.model

enum class SyncState {
    IDLE,
    RUNNING,
    SUCCESS,
    ERROR
}

enum class SyncType {
    INITIAL,
    BACKGROUND
}

data class SyncProgress(
    val type: SyncType,
    val state: SyncState,
    val percent: Int,
    val liveCount: Int,
    val vodCount: Int,
    val seriesCount: Int,
    val stage: String,
    val errorMessage: String? = null
) {
    companion object {
        fun idle(): SyncProgress = SyncProgress(
            type = SyncType.INITIAL,
            state = SyncState.IDLE,
            percent = 0,
            liveCount = 0,
            vodCount = 0,
            seriesCount = 0,
            stage = "Idle"
        )
    }
}
