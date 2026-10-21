package com.totop.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 开发方案 5.1 `settings` 表：键值设置。
 *
 * 只存非敏感配置；API Key 存 EncryptedSharedPreferences，禁止写入本表（方案 6.1）。
 */
@Entity(tableName = "settings")
data class SettingsEntity(
    @PrimaryKey val key: String,
    val value: String,
    /** 最近更新时间，UTC 毫秒。 */
    val updatedAt: Long,
) {
    companion object {
        /** 种子数据版本号键：首启幂等判据。 */
        const val KEY_SEED_VERSION: String = "seed.version"

        /** 种子数据写入完成标记键。 */
        const val KEY_SEED_APPLIED_AT: String = "seed.appliedAt"

        /** 种子数据环检测失败标记键。 */
        const val KEY_SEED_REJECTED: String = "seed.rejected"
    }
}
