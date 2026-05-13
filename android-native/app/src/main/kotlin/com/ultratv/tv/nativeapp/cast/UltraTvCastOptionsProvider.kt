package com.ultratv.tv.nativeapp.cast

import android.content.Context
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionProvider
import com.google.android.gms.cast.framework.media.CastMediaOptions

/**
 * Required by the Google Cast SDK. The receiverApplicationId is the default
 * media receiver — works for plain MP4/HLS/DASH playback without us hosting a
 * custom receiver. If a future custom Ultra TV receiver app is registered,
 * swap this for the new app id.
 *
 * Declared in AndroidManifest:
 *   <meta-data android:name="com.google.android.gms.cast.framework.OPTIONS_PROVIDER_CLASS_NAME"
 *              android:value="com.ultratv.tv.nativeapp.cast.UltraTvCastOptionsProvider" />
 */
class UltraTvCastOptionsProvider : OptionsProvider {
    override fun getCastOptions(context: Context): CastOptions =
        CastOptions.Builder()
            .setReceiverApplicationId("CC1AD845")  // CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID
            .setCastMediaOptions(CastMediaOptions.Builder().build())
            .build()

    override fun getAdditionalSessionProviders(context: Context): MutableList<SessionProvider>? = null
}
