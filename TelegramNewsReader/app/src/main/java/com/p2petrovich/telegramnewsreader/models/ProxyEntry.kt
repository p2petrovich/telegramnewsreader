package com.p2petrovich.telegramnewsreader.models

data class ProxyEntry(
    val id: String = java.util.UUID.randomUUID().toString(),
    val host: String,
    val port: Int,
    val secret: String,
    var isEnabled: Boolean = false
)
