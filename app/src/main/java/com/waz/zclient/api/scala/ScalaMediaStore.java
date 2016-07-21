/**
 * Wire
 * Copyright (C) 2016 Wire Swiss GmbH
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.waz.zclient.api.scala;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.support.annotation.RawRes;
import android.text.TextUtils;
import com.waz.api.ZMessagingApi;
import com.waz.zclient.R;
import com.waz.zclient.controllers.userpreferences.UserPreferencesController;
import com.waz.zclient.core.stores.media.MediaStore;
import com.waz.zclient.utils.RingtoneUtils;
import timber.log.Timber;

public class ScalaMediaStore extends MediaStore {

    private Context context;
    private ZMessagingApi zMessagingApi;

    public ScalaMediaStore(Context context, ZMessagingApi zMessagingApi) {
        this.context = context;
        this.zMessagingApi = zMessagingApi;

        final SharedPreferences preferences = context.getSharedPreferences(UserPreferencesController.USER_PREFS_TAG, Context.MODE_PRIVATE);
        setCustomSoundUrisFromPreferences(preferences);
    }

    @Override
    public void tearDown() {
        super.tearDown();
        context = null;
        zMessagingApi = null;
    }

    /**
     * Plays a sound from raw resource.
     *
     * @param resourceId Should be something like R.raw.alert
     */
    @Override
    public void playSound(@RawRes int resourceId) {
        Timber.i("playSound: %s (AVS MediaManager intensity level = %s)",
                 context.getResources().getResourceEntryName(resourceId),
                 zMessagingApi.getMediaManager().getIntensity());
        zMessagingApi.getMediaManager().playMedia(context.getResources().getResourceEntryName(resourceId));
    }

    @Override
    public void stopSound(@RawRes int resourceId) {
        Timber.i("stopSound: %s", context.getResources().getResourceEntryName(resourceId));
        zMessagingApi.getMediaManager().stopMedia(context.getResources().getResourceEntryName(resourceId));
    }

    @Override
    public void setCustomSoundUri(@RawRes int resourceId, String uri) {
        if (TextUtils.isEmpty(uri)) {
            return;
        }
        try {
            final Uri parsedUri = Uri.parse(uri);
            Timber.i("Set '%s' for sound '%s'", uri, context.getResources().getResourceEntryName(resourceId));
            zMessagingApi.getMediaManager().registerMediaFileUrl(context.getResources().getResourceEntryName(resourceId), parsedUri);
        } catch (Exception e) {
            Timber.e(e, "Could not set custom uri: %s", uri);
        }
    }

    @Override
    public void setCustomSoundUrisFromPreferences(SharedPreferences preferences) {
        String value = preferences.getString(context.getString(R.string.pref_options_ringtones_ringtone_key), null);
        if (!TextUtils.isEmpty(value)) {
            setCustomSoundUri(R.raw.ringing_from_them, value);
            if (RingtoneUtils.isDefaultValue(context, value, R.raw.ringing_from_them)) {
                setCustomSoundUri(R.raw.ringing_from_me, RingtoneUtils.getUriForRawId(context, R.raw.ringing_from_me).toString());
                setCustomSoundUri(R.raw.ringing_from_me_video, RingtoneUtils.getUriForRawId(context, R.raw.ringing_from_me_video).toString());
                setCustomSoundUri(R.raw.ringing_from_them_incall, RingtoneUtils.getUriForRawId(context, R.raw.ringing_from_them_incall).toString());
            } else {
                setCustomSoundUri(R.raw.ringing_from_me, value);
                setCustomSoundUri(R.raw.ringing_from_me_video, value);
                setCustomSoundUri(R.raw.ringing_from_them_incall, value);
            }
        }

        value = preferences.getString(context.getString(R.string.pref_options_ringtones_ping_key), null);
        if (!TextUtils.isEmpty(value)) {
            setCustomSoundUri(R.raw.ping_from_them, value);
            if (RingtoneUtils.isDefaultValue(context, value, R.raw.ping_from_them)) {
                setCustomSoundUri(R.raw.hotping_from_them, RingtoneUtils.getUriForRawId(context, R.raw.hotping_from_them).toString());
                setCustomSoundUri(R.raw.hotping_from_me, RingtoneUtils.getUriForRawId(context, R.raw.hotping_from_me).toString());
                setCustomSoundUri(R.raw.ping_from_me, RingtoneUtils.getUriForRawId(context, R.raw.ping_from_me).toString());
            } else {
                setCustomSoundUri(R.raw.hotping_from_them, value);
                setCustomSoundUri(R.raw.hotping_from_me, value);
                setCustomSoundUri(R.raw.ping_from_me, value);
            }
        }

        value = preferences.getString(context.getString(R.string.pref_options_ringtones_text_key), null);
        if (!TextUtils.isEmpty(value)) {
            setCustomSoundUri(R.raw.new_message, value);
            if (RingtoneUtils.isDefaultValue(context, value, R.raw.new_message)) {
                setCustomSoundUri(R.raw.first_message, RingtoneUtils.getUriForRawId(context, R.raw.first_message).toString());
                setCustomSoundUri(R.raw.new_message_gcm, RingtoneUtils.getUriForRawId(context, R.raw.new_message_gcm).toString());
            } else {
                setCustomSoundUri(R.raw.first_message, value);
                setCustomSoundUri(R.raw.new_message_gcm, value);
            }
        }
    }
}
