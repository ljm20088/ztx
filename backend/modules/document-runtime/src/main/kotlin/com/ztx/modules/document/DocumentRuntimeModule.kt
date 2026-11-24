package com.ztx.modules.document

import com.ztx.platform.common.ModuleDescriptor
import com.ztx.platform.common.PlatformModule

class DocumentRuntimeModule : PlatformModule {
    override val descriptor = ModuleDescriptor(
        id = "document-runtime",
        dependencies = setOf("iam", "metadata", "postgres")
    )
}
