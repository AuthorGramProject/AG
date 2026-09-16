-keep public class com.google.android.gms.* { public *; }
-keepnames @com.google.android.gms.common.annotation.KeepName class *
-keepclassmembernames class * {
    @com.google.android.gms.common.annotation.KeepName *;
}

-keep @interface androidx.annotation.Keep
-keep @androidx.annotation.Keep class * { *; }
-keepclasseswithmembers class * { @androidx.annotation.Keep *; }

-keep class org.webrtc.* { *; }
-keep class org.webrtc.audio.* { *; }
-keep class org.webrtc.voiceengine.* { *; }
-keep class org.telegram.messenger.* { *; }
-keep class org.telegram.messenger.camera.* { *; }
-keep class org.telegram.messenger.secretmedia.* { *; }
-keep class org.telegram.messenger.support.* { *; }
-keep class org.telegram.messenger.support.* { *; }
-keep class org.telegram.messenger.time.* { *; }
-keep class org.telegram.messenger.video.* { *; }
-keep class org.telegram.messenger.voip.* { *; }
-keep class org.telegram.SQLite.** { *; }
-keep class org.telegram.tgnet.ConnectionsManager { *; }
-keep class org.telegram.tgnet.NativeByteBuffer { *; }
-keep class org.telegram.tgnet.RequestTimeDelegate { *; }
-keep class org.telegram.tgnet.RequestDelegate { *; }
-keep class com.google.android.exoplayer2.ext.** { *; }
-keep class com.google.android.exoplayer2.extractor.FlacStreamMetadata { *; }
-keep class com.google.android.exoplayer2.metadata.flac.PictureFrame { *; }
-keep class com.google.android.exoplayer2.decoder.SimpleDecoderOutputBuffer { *; }
-keep class org.telegram.ui.ActionBar.* { *; }

# Since Unsafe is using the field offsets of these inner classes, we don't want
# to have class merging or similar tricks applied to these classes and their
# fields. It's safe to allow obfuscation, since the by-name references are
# already preserved in the -keep statement above.
-keep,allowshrinking,allowobfuscation class com.google.common.util.concurrent.AbstractFuture** {
  <fields>;
}

-keepclasseswithmembers,allowobfuscation class * {
  @com.google.gson.annotations.SerializedName <fields>;
}

# okhttp crashes on Android 16
-keep class okhttp3.internal.idn.IdnaMappingTable { *; }
-keep class okhttp3.internal.idn.IdnaMappingTableInstanceKt{ *; }

# Keep all classes of Apache Commons
-keep class org.apache.commons.text.** { *; }
-dontwarn org.apache.commons.text.**

# Prevent proguard from stripping interface information from TypeAdapter, TypeAdapterFactory,
# JsonSerializer, JsonDeserializer instances (so they can be used in @JsonAdapter)
-keep class * extends com.google.gson.TypeAdapter
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer
# Prevent R8 from leaving Data object members always null
-keepclasseswithmembers class * {
    <init>(...);
    @com.google.gson.annotations.SerializedName <fields>;
}
# Retain generic signatures of TypeToken and its subclasses with R8 version 3.0 and higher.
-keep,allowobfuscation,allowshrinking class com.google.gson.reflect.TypeToken
-keep,allowobfuscation,allowshrinking class * extends com.google.gson.reflect.TypeToken

-keepclassmembernames class androidx.core.widget.NestedScrollView {
    private android.widget.OverScroller mScroller;
    private void abortAnimatedScroll();
}

-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

-assumenosideeffects class kotlin.jvm.internal.Intrinsics {
    public static void check*(...);
    public static void throw*(...);
}

-assumenosideeffects class android.util.Log {
    public static *** v(...);
    public static *** d(...);
}

-keepclassmembers enum * {
     public static **[] values();
     public static ** valueOf(java.lang.String);
}

-keepnames class androidx.recyclerview.widget.RecyclerView
-keepclassmembers class androidx.recyclerview.widget.RecyclerView {
    public void suppressLayout(boolean);
    public boolean isLayoutSuppressed();
}

-keepclassmembers class org.telegram.ui.** {
    public <init>(...);
}

-keep class org.slf4j.** { *; }
-keepnames class * implements java.io.Serializable
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    !static !transient <fields>;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}
-keepclassmembernames,allowshrinking class tw.nekomimi.nekogram.ui.cells.AccountCell { <fields>; }
-keepclassmembernames,allowshrinking class tw.nekomimi.nekogram.ui.cells.EmojiSetCell { <fields>; }
-keepclassmembernames,allowshrinking class toss.authorgram.settings.AGChatSettingsActivity$StickerSizeCell { <fields>; }

# --- Keep Gson-reflected model fields used by Regex Filters import/export ---
# Preserve fields annotated with @Expose so Gson can access them in release builds
-keepclassmembers class * {
    @com.google.gson.annotations.Expose <fields>;
}

# TransferData is an inner static class used only via Gson reflection; keep its fields
-keep class toss.authorgram.filters.AGFiltersSettingsActivity$TransferData { *; }

# Be explicit for AGFilter models to be safe
-keepclassmembers class toss.authorgram.filters.AGFilter$FilterModel { <fields>; }
-keepclassmembers class toss.authorgram.filters.AGFilter$ChatFilterEntry { <fields>; }
-keepclassmembers class toss.authorgram.filters.AGFilter$ExcludedFilterEntry { <fields>; }
-keepclassmembers class toss.authorgram.filters.AGFilter$CustomFilteredUser { <fields>; }

# https://github.com/osmdroid/osmdroid/issues/633
-dontwarn org.osmdroid.tileprovider.modules.NetworkAvailabliltyCheck
# Osmdroid
-dontwarn org.osmdroid.**

# dnsjava
-dontwarn java.net.spi.InetAddressResolverProvider
-dontwarn org.xbill.DNS.spi.DnsjavaInetAddressResolverProvider

-dontwarn java.beans.**
-dontwarn lombok.**
-dontwarn org.slf4j.**
-dontwarn sun.net.spi.nameservice.**
-dontwarn com.google.j2objc.annotations.ReflectionSupport
-dontwarn com.google.j2objc.annotations.RetainedWith
-dontwarn com.google.j2objc.annotations.Weak
-dontwarn android.support.annotation.IntRange
-dontwarn android.support.annotation.NonNull
-dontwarn android.support.annotation.Nullable
-dontwarn android.support.annotation.RequiresApi
-dontwarn android.support.annotation.Size
-dontwarn android.support.annotation.VisibleForTesting
-dontwarn android.support.v4.app.NotificationCompat$Builder

-allowaccessmodification
-keepattributes Signature,InnerClasses,EnclosingMethod
-keepattributes SourceFile,LineNumberTable
-keepattributes *Annotation*
-dontoptimize
-dontobfuscate
