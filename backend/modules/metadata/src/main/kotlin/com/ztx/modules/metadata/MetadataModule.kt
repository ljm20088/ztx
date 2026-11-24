package com.ztx.modules.metadata

import com.ztx.platform.common.ModuleDescriptor
import com.ztx.platform.common.PlatformModule

class MetadataModule : PlatformModule {
    override val descriptor = ModuleDescriptor(id = "metadata", dependencies = setOf("iam", "postgres"))
}
