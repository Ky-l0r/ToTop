package com.totop.app.data.prefs

import com.totop.app.data.repo.SettingsRepository
import com.totop.app.domain.model.SubjectScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 「我学哪几科」的读写口。
 *
 * 只做搬运：解析与格式化在 [SubjectScope] 里，这里不重复任何规则，
 * 否则界面与统计会各自长出第二份判断。
 *
 * 为什么存 settings 表而不是 DataStore：
 * 本项目所有偏好都在 settings 表里（讲解模式、TTS、语音引擎），
 * 再引一个存储介质会让「设置页改了不生效」这类问题多一个排查方向。
 */
@Singleton
class SubjectPrefs
    @Inject
    constructor(
        private val settings: SettingsRepository,
    ) {
        /** 当前选中的科目集合；没选过时返回全选。 */
        suspend fun allowed(): Set<String> = SubjectScope.parse(settings.get(SettingsRepository.Keys.SUBJECTS))

        /** 观察变化：设置页改完回来，列表要立刻按新口径刷新。 */
        fun observe(): Flow<Set<String>> =
            settings.observe(SettingsRepository.Keys.SUBJECTS).map { raw -> SubjectScope.parse(raw) }

        /** 是否已经选过（决定首次启动是否进选择页）。 */
        suspend fun hasPicked(): Boolean = settings.getBoolean(SettingsRepository.Keys.SUBJECTS_PICKED, false)

        /**
         * 保存选择。
         *
         * 空集合按 [SubjectScope.ALL] 存：界面上「一个都不选」等于退回全部，
         * 但保存时会拦住（见选择页的按钮可用性），这里只是兜底。
         */
        suspend fun save(codes: Set<String>) {
            val normalized = codes.ifEmpty { SubjectScope.ALL }
            settings.put(SettingsRepository.Keys.SUBJECTS, SubjectScope.format(normalized))
            settings.putBoolean(SettingsRepository.Keys.SUBJECTS_PICKED, true)
        }
    }
