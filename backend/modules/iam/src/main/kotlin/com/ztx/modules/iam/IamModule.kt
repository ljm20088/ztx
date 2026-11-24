package com.ztx.modules.iam

import com.ztx.platform.common.ModuleDescriptor
import com.ztx.platform.common.PlatformModule

class IamModule : PlatformModule {
    override val descriptor = ModuleDescriptor(id = "iam", dependencies = setOf("postgres"))
}
