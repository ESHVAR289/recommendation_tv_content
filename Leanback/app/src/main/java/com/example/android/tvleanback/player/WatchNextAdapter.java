/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package com.example.android.tvleanback.player;

import android.content.ContentUris;
import android.content.Context;
import android.net.Uri;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.tvprovider.media.tv.TvContractCompat;
import androidx.tvprovider.media.tv.WatchNextProgram;

import com.example.android.tvleanback.model.MockDatabase;
import com.example.android.tvleanback.model.Video;
import com.example.android.tvleanback.util.AppLinkHelper;

/**
 * Adds, updates, and removes the currently playing {@link Video} from the "Watch Next" channel.
 */
public class WatchNextAdapter {

    private static final String TAG = "WatchNextAdapter";

    public void updateProgress(
            Context context, long channelId, Video video, long position, long duration) {
        Log.d(TAG, String.format("Updating the video (%d) in watch next.", video.id));

        Video entity = video;
        if (entity == null) {
            Log.e(
                    TAG,
                    String.format(
                            "Could not find video in channel: channel id: %d, video id: %d",
                            channelId, video.id));
            return;
        }

        WatchNextProgram program = createWatchNextProgram(channelId, entity, position, duration);
        if (entity.getWatchNextId() < 1L) {
            // Need to create program.
            Uri watchNextProgramUri =
                    context.getContentResolver()
                            .insert(
                                    TvContractCompat.WatchNextPrograms.CONTENT_URI,
                                    program.toContentValues());
            long watchNextId = ContentUris.parseId(watchNextProgramUri);
            entity.setWatchNextId(watchNextId);
            MockDatabase.saveVideos(context, channelId, entity);

            Log.d(TAG, "Watch Next program added: " + watchNextId);
        } else {
            // Update the progress and last engagement time of the program.
            context.getContentResolver()
                    .update(
                            TvContractCompat.buildWatchNextProgramUri(entity.getWatchNextId()),
                            program.toContentValues(),
                            null,
                            null);

            Log.d(TAG, "Watch Next program updated: " + entity.getWatchNextId());
        }
    }

    @NonNull
    private WatchNextProgram createWatchNextProgram(
            long channelId, Video video, long position, long duration) {
        Uri posterArtUri = Uri.parse(video.cardImageUrl);
        Uri intentUri = AppLinkHelper.buildPlaybackUri(channelId, video.id, position);

        WatchNextProgram.Builder builder = new WatchNextProgram.Builder();
        builder.setType(TvContractCompat.PreviewProgramColumns.TYPE_MOVIE)
                .setWatchNextType(TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE)
                .setLastEngagementTimeUtcMillis(System.currentTimeMillis())
                .setLastPlaybackPositionMillis((int) position)
                .setDurationMillis((int) duration)
                .setTitle(video.title)
                .setDescription(video.description)
                .setPosterArtUri(posterArtUri)
                .setIntentUri(intentUri);
        return builder.build();
    }

    public void removeFromWatchNext(Context context, long channelId, Video videoId) {
        Video video = videoId;
        if (video == null || video.getWatchNextId() < 1L) {
            Log.d(TAG, "No program to remove from watch next.");
            return;
        }

        int rows =
                context.getContentResolver()
                        .delete(
                                TvContractCompat.buildWatchNextProgramUri(video.getWatchNextId()),
                                null,
                                null);
        Log.d(TAG, String.format("Deleted %d programs(s) from watch next", rows));

        // Sync our records with the system; remove reference to watch next program.
        video.setWatchNextId(-1);
        MockDatabase.saveVideos(context, channelId, video);
    }
}
