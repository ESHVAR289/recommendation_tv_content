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
package com.example.android.tvleanback;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.PersistableBundle;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.tvprovider.media.tv.Channel;
import androidx.tvprovider.media.tv.PreviewProgram;
import androidx.tvprovider.media.tv.TvContractCompat;

import com.example.android.tvleanback.model.MockDatabase;
import com.example.android.tvleanback.model.MockVideoService;
import com.example.android.tvleanback.model.Subscription;
import com.example.android.tvleanback.model.Video;
import com.example.android.tvleanback.util.AppLinkHelper;
import com.example.android.tvleanback.util.TvUtil;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Syncs programs for a channel. A channel id is required to be passed via the {@link
 * JobParameters}. This service is scheduled to listen to changes to a channel. Once the job
 * completes, it will reschedule itself to listen for the next change to the channel. See {@link
 * TvUtil#scheduleSyncingProgramsForChannel(Context, long)} for more details about the scheduling.
 */
public class SyncProgramsJobService extends JobService {

    private static final String TAG = "SyncProgramsJobService";

    private SyncProgramsTask mSyncProgramsTask;

    @Override
    public boolean onStartJob(final JobParameters jobParameters) {
        Log.d(TAG, "onStartJob(): " + jobParameters);

        final long channelId = getChannelId(jobParameters);
        if (channelId == -1L) {
            return false;
        }
        Log.d(TAG, "onStartJob(): Scheduling syncing for programs for channel " + channelId);

        mSyncProgramsTask =
                new SyncProgramsTask(getApplicationContext()) {
                    @Override
                    protected void onPostExecute(Boolean finished) {
                        super.onPostExecute(finished);
                        // Daisy chain listening for the next change to the channel.
                        TvUtil.scheduleSyncingProgramsForChannel(
                                SyncProgramsJobService.this, channelId);
                        mSyncProgramsTask = null;
                        jobFinished(jobParameters, !finished);
                    }
                };
        mSyncProgramsTask.execute(channelId);

        return true;
    }

    @Override
    public boolean onStopJob(JobParameters jobParameters) {
        if (mSyncProgramsTask != null) {
            mSyncProgramsTask.cancel(true);
        }
        return true;
    }

    private long getChannelId(JobParameters jobParameters) {
        PersistableBundle extras = jobParameters.getExtras();
        if (extras == null) {
            return -1L;
        }

        return extras.getLong(TvContractCompat.EXTRA_CHANNEL_ID, -1L);
    }

    /*
     * Syncs programs by querying the given channel id.
     *
     * If the channel is not browsable, the programs will be removed to avoid showing
     * stale programs when the channel becomes browsable in the future.
     *
     * If the channel is browsable, then it will check if the channel has any programs.
     *      If the channel does not have any programs, new programs will be added.
     *      If the channel does have programs, then a fresh list of programs will be fetched and the
     *          channel's programs will be updated.
     */
    private void syncPrograms(long channelId, List<Video> initialVideos) {
        Log.d(TAG, "Sync programs for channel: " + channelId);
        List<Video> videos = new ArrayList<>(initialVideos);

        try (Cursor cursor =
                     getContentResolver()
                             .query(
                                     TvContractCompat.buildChannelUri(channelId),
                                     null,
                                     null,
                                     null,
                                     null)) {
            if (cursor != null && cursor.moveToNext()) {
                Channel channel = Channel.fromCursor(cursor);
                if (!channel.isBrowsable()) {
                    Log.d(TAG, "Channel is not browsable: " + channelId);
                    deletePrograms(channelId, videos);
                } else {
                    Log.d(TAG, "Channel is browsable: " + channelId);
                    if (videos.isEmpty()) {
                        videos = createPrograms(channelId, MockVideoService.getList());
                    } else {
                        videos = updatePrograms(channelId, videos);
                    }
                    MockDatabase.saveVideos(getApplicationContext(), channelId, videos);
                }
            }
        }
    }

    private List<Video> createPrograms(long channelId, List<Video> videos) {

        List<Video> moviesAdded = new ArrayList<>(videos.size());
        for (Video video : videos) {
            PreviewProgram previewProgram = buildProgram(channelId, video);

            Uri programUri =
                    getContentResolver()
                            .insert(
                                    TvContractCompat.PreviewPrograms.CONTENT_URI,
                                    previewProgram.toContentValues());
            long programId = ContentUris.parseId(programUri);
            Log.d(TAG, "Inserted new program: " + programId);
            video.setProgramId(programId);
            moviesAdded.add(video);
        }

        return moviesAdded;
    }

    private List<Video> updatePrograms(long channelId, List<Video> videos) {

        // By getting a fresh list, we should see a visible change in the home screen.
        List<Video> updateMovies = MockVideoService.getFreshList();
        for (int i = 0; i < videos.size(); ++i) {
            Video old = videos.get(i);
            Video update = updateMovies.get(i);
            long programId = old.getProgramId();

            getContentResolver()
                    .update(
                            TvContractCompat.buildPreviewProgramUri(programId),
                            buildProgram(channelId, update).toContentValues(),
                            null,
                            null);
            Log.d(TAG, "Updated program: " + programId);
            update.setProgramId(programId);
        }

        return updateMovies;
    }

    private void deletePrograms(long channelId, List<Video> videos) {
        if (videos.isEmpty()) {
            return;
        }

        int count = 0;
        for (Video video : videos) {
            count +=
                    getContentResolver()
                            .delete(
                                    TvContractCompat.buildPreviewProgramUri(video.getProgramId()),
                                    null,
                                    null);
        }
        Log.d(TAG, "Deleted " + count + " programs for  channel " + channelId);

        // Remove our local records to stay in sync with the TV Provider.
        MockDatabase.removeVideos(getApplicationContext(), channelId);
    }

    @NonNull
    private PreviewProgram buildProgram(long channelId, Video video) {
        Uri posterArtUri = Uri.parse(video.cardImageUrl);
        Uri appLinkUri = AppLinkHelper.buildPlaybackUri(channelId, video.id);
        Uri previewVideoUri = Uri.parse(video.videoUrl);

        PreviewProgram.Builder builder = new PreviewProgram.Builder();
        builder.setChannelId(channelId)
                .setType(TvContractCompat.PreviewProgramColumns.TYPE_CLIP)
                .setTitle(video.title)
                .setDescription(video.description)
                .setPosterArtUri(posterArtUri)
                .setPreviewVideoUri(previewVideoUri)
                .setIntentUri(appLinkUri);
        return builder.build();
    }

    private class SyncProgramsTask extends AsyncTask<Long, Void, Boolean> {

        private final Context mContext;

        private SyncProgramsTask(Context context) {
            this.mContext = context;
        }

        @Override
        protected Boolean doInBackground(Long... channelIds) {
            List<Long> params = Arrays.asList(channelIds);
            if (!params.isEmpty()) {
                for (Long channelId : params) {
                    Subscription subscription =
                            MockDatabase.findSubscriptionByChannelId(mContext, channelId);
                    if (subscription != null) {
                        List<Video> cachedVideos = MockDatabase.getVideos(mContext, channelId);
                        syncPrograms(channelId, cachedVideos);
                    }
                }
            }
            return true;
        }
    }
}