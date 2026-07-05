package com.deivid22srk.chatfloat.data

import android.content.Context
import android.content.SharedPreferences
import java.util.UUID

/**
 * Manages the local user account (token + username + avatar).
 *
 * Account creation flow:
 *   1. User picks a username
 *   2. A random 8-char token is generated (e.g. "AB12CD34")
 *   3. The token + username + avatar (base64) are stored locally AND
 *      registered with the bot (a special "registration" message is sent to
 *      the group so other instances can resolve token -> username/avatar).
 *
 * Login flow:
 *   1. User pastes a token
 *   2. The app queries the bot for the registration message that matches
 *   3. On success: token + username + avatar are restored
 *
 * The token is what identifies the user across reinstalls.
 */
class AccountManager(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isLoggedIn(): Boolean = prefs.getString(KEY_TOKEN, null) != null

    fun getToken(): String? = prefs.getString(KEY_TOKEN, null)
    fun getUsername(): String? = prefs.getString(KEY_USERNAME, null)
    fun getAvatarBase64(): String? = prefs.getString(KEY_AVATAR, null)

    /**
     * Creates a new account locally. Caller should also call
     * [TelegramBotRepository.registerAccount] to publish it.
     */
    fun createAccount(username: String): String {
        val token = generateToken()
        prefs.edit()
            .putString(KEY_TOKEN, token)
            .putString(KEY_USERNAME, username)
            .remove(KEY_AVATAR)
            .apply()
        return token
    }

    /**
     * Restores an account from a token + resolved username + avatar.
     */
    fun restoreAccount(token: String, username: String, avatarBase64: String?) {
        prefs.edit()
            .putString(KEY_TOKEN, token)
            .putString(KEY_USERNAME, username)
            .putString(KEY_AVATAR, avatarBase64)
            .apply()
    }

    fun setAvatar(base64: String?) {
        prefs.edit().putString(KEY_AVATAR, base64).apply()
    }

    fun setUsername(username: String) {
        prefs.edit().putString(KEY_USERNAME, username).apply()
    }

    fun logout() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val PREFS_NAME = "chatfloat_account"
        private const val KEY_TOKEN = "token"
        private const val KEY_USERNAME = "username"
        private const val KEY_AVATAR = "avatar_base64"

        /**
         * Generates a short human-readable token like "AB12CD34".
         * Uses uppercase letters + digits, avoiding ambiguous chars.
         */
        fun generateToken(): String {
            val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789" // no I,O,0,1
            val sb = StringBuilder(8)
            repeat(8) {
                sb.append(alphabet.random())
            }
            return sb.toString()
        }

        /**
         * Generates a UUID — used as a stable message identifier for
         * registration messages stored in the Telegram group.
         */
        fun generateMessageId(): String = UUID.randomUUID().toString()
    }
}
