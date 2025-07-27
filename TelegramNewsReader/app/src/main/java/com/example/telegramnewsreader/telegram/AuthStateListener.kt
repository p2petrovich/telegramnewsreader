package com.example.telegramnewsreader.telegram // Убедитесь, что пакет правильный

import org.drinkless.tdlib.TdApi

/**
 * Listener for TDLib authorization state changes.
 */
interface AuthStateListener {
    /**
     * Called when the TDLib authorization state changes.
     * @param newState The new authorization state from TDLib.
     */
    fun onAuthStateChanged(newState: TdApi.AuthorizationState)
}
