package com.mainmethod.premofm.media;

import android.annotation.TargetApi;
import android.content.Context;
import android.media.PlaybackParams;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.util.Log;

import com.google.android.exoplayer2.DefaultLoadControl;
import com.google.android.exoplayer2.ExoPlaybackException;
import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.ExoPlayerFactory;
import com.google.android.exoplayer2.SimpleExoPlayer;
import com.google.android.exoplayer2.Timeline;
import com.google.android.exoplayer2.extractor.Extractor;
import com.google.android.exoplayer2.extractor.ExtractorsFactory;
import com.google.android.exoplayer2.extractor.mp3.Mp3Extractor;
import com.google.android.exoplayer2.extractor.mp4.Mp4Extractor;
import com.google.android.exoplayer2.extractor.ogg.OggExtractor;
import com.google.android.exoplayer2.extractor.wav.WavExtractor;
import com.google.android.exoplayer2.source.ExtractorMediaSource;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector;
import com.google.android.exoplayer2.trackselection.TrackSelector;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource;
import com.google.android.exoplayer2.upstream.DefaultHttpDataSourceFactory;
import com.google.android.exoplayer2.upstream.FileDataSourceFactory;
import com.google.android.exoplayer2.upstream.cache.Cache;
import com.google.android.exoplayer2.upstream.cache.CacheDataSource;
import com.google.android.exoplayer2.upstream.cache.CacheDataSourceFactory;
import com.google.android.exoplayer2.upstream.cache.LeastRecentlyUsedCacheEvictor;
import com.google.android.exoplayer2.upstream.cache.SimpleCache;
import com.mainmethod.premofm.R;
import com.mainmethod.premofm.object.DownloadStatus;
import com.mainmethod.premofm.object.Episode;

import java.io.File;

/**
 * Plays media playback on the device audio output
 * Created by evanhalley on 11/18/15.
 */
public class LocalMediaPlayer extends MediaPlayer implements
        ProgressUpdateListener, ExoPlayer.EventListener {

    private static final String TAG = LocalMediaPlayer.class.getSimpleName();
    private static final int MAX_CACHE_SIZE = 250_000_000;
    private static final String PODCAST_CACHE_DIR = "podcast-cache";

    private final Context mContext;
    private final Handler mHandler;
    private final SimpleExoPlayer mExoPlayer;
    private Episode mEpisode;
    private boolean mIsStreaming;
    private int mMediaPlayerState;
    private boolean isUpdatingProgress;
    private Runnable mProgressUpdater;

    public LocalMediaPlayer(PremoMediaPlayerListener mediaPlayerListener,
                            ProgressUpdateListener progressUpdateListener, Context context) {
        super(mediaPlayerListener, progressUpdateListener);
        mContext = context;
        mHandler = new Handler();
        TrackSelector trackSelector = new DefaultTrackSelector(mHandler);
        DefaultLoadControl loadControl = new DefaultLoadControl();
        mExoPlayer = ExoPlayerFactory.newSimpleInstance(mContext, trackSelector, loadControl);
        mExoPlayer.addListener(this);
        mMediaPlayerState = MediaPlayerState.STATE_IDLE;
        mProgressUpdater = new ProgressUpdater();
    }

    @Override
    public int getState() {
        return mMediaPlayerState;
    }

    @Override
    public long getCurrentPosition() {
        return mExoPlayer.getCurrentPosition();
    }

    @Override
    public long getDuration() {
        return mExoPlayer.getDuration();
    }

    @Override
    public long getBufferedPosition() {
        return mExoPlayer.getBufferedPosition();
    }

    @Override
    public boolean isStreaming() {
        return mIsStreaming;
    }

    @Override
    public void loadEpisode(Episode episode) {
        mEpisode = episode;
    }

    @Override
    public void startPlayback(boolean playImmediately) {

        if (mEpisode.getProgress() > -1) {
            mExoPlayer.seekTo(mEpisode.getProgress());
        } else {
            mExoPlayer.seekTo(0);
        }
        MediaSource mMediaSource = buildMediaSource();
        mExoPlayer.prepare(mMediaSource, false, false);
        mExoPlayer.setPlayWhenReady(playImmediately);
    }

    @Override
    public void resumePlayback() {
        mExoPlayer.setPlayWhenReady(true);
    }

    @Override
    public void pausePlayback() {
        mExoPlayer.setPlayWhenReady(false);
    }

    @Override
    public void stopPlayback() {
        mExoPlayer.stop();
        mIsStreaming = false;
        mEpisode = null;
    }

    @Override
    public void seekTo(long position) {
        mExoPlayer.seekTo(position);
    }

    @Override
    public void tearDown() {
        Log.d(TAG, "Tearing down");
        super.tearDown();
        mExoPlayer.release();
        mExoPlayer.removeListener(this);
    }

    @Override
    public void onLoadingChanged(boolean isLoading) {

    }

    @Override
    public void onPlayerStateChanged(boolean playWhenReady, int playbackState) {
        String playbackStateStr;

        switch (playbackState) {
            case ExoPlayer.STATE_BUFFERING:
                mMediaPlayerState = MediaPlayerState.STATE_CONNECTING;
                playbackStateStr = "Buffering";
                break;
            case ExoPlayer.STATE_ENDED:
                mMediaPlayerState = MediaPlayerState.STATE_ENDED;
                playbackStateStr = "Ended";
                break;
            case ExoPlayer.STATE_IDLE:
                mMediaPlayerState = MediaPlayerState.STATE_IDLE;
                playbackStateStr = "Idle";
                break;
            case ExoPlayer.STATE_READY:
                mMediaPlayerState = playWhenReady ? MediaPlayerState.STATE_PLAYING :
                        MediaPlayerState.STATE_PAUSED;
                playbackStateStr = "Ready";

                if (playWhenReady) {
                    startProgressUpdater();
                } else {
                    stopProgressUpdater();
                }
                break;
            default:
                mMediaPlayerState = MediaPlayerState.STATE_IDLE;
                playbackStateStr = "Unknown";
                break;
        }
        mMediaPlayerListener.onStateChanged(mMediaPlayerState);
        Log.d(TAG, String.format("ExoPlayer state changed: %s, Play When Ready: %s",
                playbackStateStr,
                String.valueOf(playWhenReady)));
    }

    @Override
    public void onTimelineChanged(Timeline timeline, Object manifest) {
    }

    @Override
    public void onPositionDiscontinuity() {

    }

    @Override
    public void onPlayerError(ExoPlaybackException error) {
        Log.w(TAG, "Player error encountered", error);
        stopPlayback();
    }

    @Override
    public void onProgressUpdate(long progress, long bufferedProgress, long duration) {
        mProgressUpdateListener.onProgressUpdate(progress,
                isStreaming() ? bufferedProgress : duration, duration);
    }

    @TargetApi(Build.VERSION_CODES.M)
    @Override
    public void setPlaybackSpeed(float speed) {
        PlaybackParams playbackParams = new PlaybackParams();
        playbackParams.setSpeed(speed);
        mExoPlayer.setPlaybackParams(playbackParams);
    }

    private MediaSource buildMediaSource() {
        DataSource.Factory dataSourceFactory = null;
        Uri uri = null;

        // return the uri to play
        switch (mEpisode.getDownloadStatus()) {
            case DownloadStatus.DOWNLOADED:
                uri = Uri.parse(mEpisode.getLocalMediaUrl());
                dataSourceFactory = new FileDataSourceFactory();
                mIsStreaming = false;
                break;
            case DownloadStatus.DOWNLOADING:
            case DownloadStatus.NOT_DOWNLOADED:
                uri = Uri.parse(mEpisode.getRemoteMediaUrl());
                dataSourceFactory = getCacheDataSource(
                        new File(mContext.getCacheDir(), PODCAST_CACHE_DIR),
                        mContext.getString(R.string.user_agent));
                mIsStreaming = true;
                break;
        }

        if (uri != null) {
            Log.d(TAG, "Playing from URI " + uri);
            return new ExtractorMediaSource(uri, dataSourceFactory, new AudioExtractorsFactory(),
                    mHandler, null);
        }
        throw new IllegalStateException("Unable to build media source");
    }

    private void startProgressUpdater() {

        if (!isUpdatingProgress) {
            mProgressUpdater.run();
            isUpdatingProgress = true;
        }
    }

    private void stopProgressUpdater() {

        if (isUpdatingProgress) {
            mHandler.removeCallbacks(mProgressUpdater);
            isUpdatingProgress = false;
        }
    }

    private static CacheDataSourceFactory getCacheDataSource(File cacheDir, String userAgent) {
        Cache cache = new SimpleCache(cacheDir, new LeastRecentlyUsedCacheEvictor(MAX_CACHE_SIZE));
        DataSource.Factory upstream = new DefaultHttpDataSourceFactory(userAgent, null,
                DefaultHttpDataSource.DEFAULT_CONNECT_TIMEOUT_MILLIS,
                DefaultHttpDataSource.DEFAULT_READ_TIMEOUT_MILLIS, true);
        return new CacheDataSourceFactory(cache, upstream,
                CacheDataSource.FLAG_CACHE_UNBOUNDED_REQUESTS,
                CacheDataSource.DEFAULT_MAX_CACHE_FILE_SIZE);
    }

    /**
     * Defines what audio file formats can be played
     */
    private static class AudioExtractorsFactory implements ExtractorsFactory {

        @Override
        public Extractor[] createExtractors() {
            return new Extractor[]{
                    new OggExtractor(),
                    new WavExtractor(),
                    new Mp3Extractor(),
                    new Mp4Extractor()};
        }
    }

    // spins the album art like a record
    private class ProgressUpdater implements Runnable {

        private static final int TIME_UPDATE_MS = 16;

        @Override
        public void run() {
            long progress = mExoPlayer.getCurrentPosition();
            long duration = mExoPlayer.getDuration();
            mProgressUpdateListener.onProgressUpdate(progress, 0, duration);
            mHandler.postDelayed(mProgressUpdater, TIME_UPDATE_MS);
        }
    }
}
