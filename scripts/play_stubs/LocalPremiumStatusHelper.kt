package xyz.nextalone.nagram.helper

import org.telegram.tgnet.TLRPC

/** Play build: local Premium emoji-status emulation is absent. */
object LocalPremiumStatusHelper {
    const val KEY_PREFIX = "useLocalEmojiStatusData_"
    @JvmStatic fun getDocumentId(user: TLRPC.User?): Long? = null
    @JvmStatic fun initForUser(userId: Long, force: Boolean = false) { }
    @JvmStatic fun apply(status: TLRPC.EmojiStatus?) { }
}
