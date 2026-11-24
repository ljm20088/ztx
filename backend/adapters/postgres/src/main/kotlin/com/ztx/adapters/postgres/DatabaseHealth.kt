package com.ztx.adapters.postgres

import io.vertx.core.Future

interface DatabaseHealth {
    fun ping(): Future<Void>
}
