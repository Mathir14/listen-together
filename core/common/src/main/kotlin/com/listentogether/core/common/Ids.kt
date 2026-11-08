package com.listentogether.core.common

import java.util.UUID

object Ids {
    fun newId(): String = UUID.randomUUID().toString()
}
