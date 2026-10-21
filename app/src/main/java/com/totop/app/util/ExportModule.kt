package com.totop.app.util

import com.totop.app.domain.usecase.port.DataExporter
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * 导出能力的依赖注入（开发方案 FR-10）。
 *
 * 绑定两件事：
 * - [DataExporter] -> [ExportFileWriter]：**唯一**的写盘实现（SAF / FileProvider / MediaStore）；
 * - [Exporter] 与 [BackupManager] 直接 `@Inject` 构造，无需在此声明。
 *
 * 注意：不要把 [Exporter] 也实现成 `DataExporter`，否则 Hilt 会因重复绑定而编译失败。
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class ExportModule {
    @Binds
    @Singleton
    abstract fun bindDataExporter(impl: ExportFileWriter): DataExporter
}
