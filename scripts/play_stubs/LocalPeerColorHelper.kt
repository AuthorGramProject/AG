package xyz.nextalone.nagram.helper

import org.telegram.tgnet.TLRPC

/** Play build: local Premium peer/profile color emulation is absent. */
object LocalPeerColorHelper {
    const val KEY_PREFIX = "useLocalQuoteColorData_"
    @JvmStatic fun getColorId(user: TLRPC.User): Int? = null
    @JvmStatic fun getEmojiId(user: TLRPC.User?): Long? = null
    @JvmStatic fun getProfileColorId(user: TLRPC.User): Int? = null
    @JvmStatic fun getProfileEmojiId(user: TLRPC.User?): Long? = null
    @JvmStatic fun initForUser(userId: Long, force: Boolean = false) { }
    @JvmStatic fun apply(colorId: Int, emojiId: Long, profileColorId: Int, profileEmojiId: Long) { }
}
