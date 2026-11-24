package com.ztx.adapters.postgres

import com.ztx.platform.common.ModuleDescriptor
import com.ztx.platform.common.PlatformModule

class PostgresModule : PlatformModule {
    override val descriptor = ModuleDescriptor(id = "postgres")
}
