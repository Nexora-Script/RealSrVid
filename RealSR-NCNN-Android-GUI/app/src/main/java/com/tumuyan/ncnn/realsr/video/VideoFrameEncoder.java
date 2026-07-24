package com.tumuyan.ncnn.realsr.video;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.util.Log;
import android.view.Surface;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Comparator;

/**
 * Encodes a directory of upscaled PNG frames (frame_000001.png, frame_000002.png, ...) back into
 * an MP4, at the original frame rate, capped to a maximum output height if requested, then muxes
 * the original audio track (if any) back in untouched. Pure Android SDK — no ffmpeg.
 */
public class VideoFrameEncoder {

    private static final String TAG = "VideoFrameEncoder";
    private static final String VIDEO_MIME = "video/avc"; // H.264 — broadest hardware support

    public interface ProgressCallback {
        void onFrameEncoded(int frameIndex, int totalFrames);
    }

    /**
     * @param upscaledFrameDir folder of upscaled PNGs, same naming as produced by realsr-ncnn
     *                         batch mode over the extracted frame_XXXXXX.png sequence
     * @param audioTrackFile   file produced by VideoFrameExtractor.extractAudioTrack, or null
     * @param frameRate        original video frame rate (keeps output duration correct)
     * @param maxHeight        if > 0 and a frame's height exceeds this, frames are downscaled to
     *                         this height (width adjusted to keep aspect ratio, forced even) before
     *                         encoding. Pass 0 or a negative number to keep full upscaled resolution.
     * @param outputVideoFile  destination .mp4 path
     */
    public static void encode(File upscaledFrameDir, File audioTrackFile, float frameRate,
                               int maxHeight, File outputVideoFile,
                               ProgressCallback callback) throws Exception {

        File[] frameFiles = upscaledFrameDir.listFiles((dir, name) ->
                name.toLowerCase().endsWith(".png") || name.toLowerCase().endsWith(".jpg"));
        if (frameFiles == null || frameFiles.length == 0) {
            throw new Exception("No upscaled frames found in " + upscaledFrameDir);
        }
        Arrays.sort(frameFiles, Comparator.comparing(File::getName));

        // Peek at the first frame to determine encode dimensions.
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(frameFiles[0].getAbsolutePath(), opts);
        int srcWidth = opts.outWidth;
        int srcHeight = opts.outHeight;

        int outWidth = srcWidth;
        int outHeight = srcHeight;
        if (maxHeight > 0 && srcHeight > maxHeight) {
            outHeight = maxHeight;
            outWidth = Math.round(srcWidth * (maxHeight / (float) srcHeight));
        }
        // Encoders require even dimensions.
        outWidth = outWidth % 2 == 0 ? outWidth : outWidth - 1;
        outHeight = outHeight % 2 == 0 ? outHeight : outHeight - 1;

        long bitrate = estimateBitrate(outWidth, outHeight, frameRate);

        MediaFormat format = MediaFormat.createVideoFormat(VIDEO_MIME, outWidth, outHeight);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_BIT_RATE, (int) bitrate);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, Math.round(frameRate));
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2);

        MediaCodec encoder = MediaCodec.createEncoderByType(VIDEO_MIME);
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        Surface inputSurface = encoder.createInputSurface();
        encoder.start();

        File tempVideoOnly = new File(outputVideoFile.getParentFile(), "video_only.mp4");
        MediaMuxer muxer = new MediaMuxer(tempVideoOnly.getAbsolutePath(),
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        int muxerVideoTrack = -1;
        boolean muxerStarted = false;

        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
        long frameDurationUs = Math.round(1_000_000.0 / frameRate);
        int total = frameFiles.length;

        for (int i = 0; i < total; i++) {
            Bitmap bmp = BitmapFactory.decodeFile(frameFiles[i].getAbsolutePath());
            if (bmp == null) continue;

            Canvas canvas = inputSurface.lockCanvas(null);
            Rect dst = new Rect(0, 0, outWidth, outHeight);
            canvas.drawBitmap(bmp, null, dst, null);
            inputSurface.unlockCanvasAndPost(canvas);
            bmp.recycle();

            // Drain any encoded output that's ready so the encoder buffer never backs up.
            muxerVideoTrack = drainEncoder(encoder, muxer, bufferInfo, muxerVideoTrack, false);
            if (!muxerStarted && muxerVideoTrack >= 0) {
                muxer.start();
                muxerStarted = true;
            }

            if (callback != null) callback.onFrameEncoded(i + 1, total);
        }

        // Signal end-of-stream and drain the rest.
        encoder.signalEndOfInputStream();
        muxerVideoTrack = drainEncoder(encoder, muxer, bufferInfo, muxerVideoTrack, true);
        if (!muxerStarted && muxerVideoTrack >= 0) {
            muxer.start();
            muxerStarted = true;
        }

        encoder.stop();
        encoder.release();
        inputSurface.release();
        if (muxerStarted) {
            muxer.stop();
        }
        muxer.release();

        if (audioTrackFile != null && audioTrackFile.exists()) {
            muxVideoWithAudio(tempVideoOnly, audioTrackFile, outputVideoFile);
            //noinspection ResultOfMethodCallIgnored
            tempVideoOnly.delete();
        } else {
            if (!tempVideoOnly.renameTo(outputVideoFile)) {
                copyFile(tempVideoOnly, outputVideoFile);
                //noinspection ResultOfMethodCallIgnored
                tempVideoOnly.delete();
            }
        }

        Log.i(TAG, "Encoded " + total + " frames -> " + outputVideoFile
                + " (" + outWidth + "x" + outHeight + ")");
    }

    private static int drainEncoder(MediaCodec encoder, MediaMuxer muxer,
                                     MediaCodec.BufferInfo bufferInfo, int muxerVideoTrack,
                                     boolean drainAll) {
        while (true) {
            int outIndex = encoder.dequeueOutputBuffer(bufferInfo, drainAll ? 10000 : 0);
            if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (drainAll) continue; else break;
            } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (muxerVideoTrack < 0) {
                    muxerVideoTrack = muxer.addTrack(encoder.getOutputFormat());
                }
            } else if (outIndex >= 0) {
                ByteBuffer encodedData = encoder.getOutputBuffer(outIndex);
                if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    bufferInfo.size = 0;
                }
                if (bufferInfo.size > 0 && muxerVideoTrack >= 0 && encodedData != null) {
                    encodedData.position(bufferInfo.offset);
                    encodedData.limit(bufferInfo.offset + bufferInfo.size);
                    muxer.writeSampleData(muxerVideoTrack, encodedData, bufferInfo);
                }
                encoder.releaseOutputBuffer(outIndex, false);
                if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    break;
                }
            }
        }
        return muxerVideoTrack;
    }

    /** ~0.1 bits/pixel/frame at the target fps — a reasonable quality/size balance for x264-class output. */
    private static long estimateBitrate(int width, int height, float frameRate) {
        double bitsPerPixel = 0.1;
        return (long) (width * height * frameRate * bitsPerPixel);
    }

    /** Combines a video-only mp4 and an audio-only mp4 (same container) into one file, no re-encode. */
    private static void muxVideoWithAudio(File videoOnly, File audioOnly, File output) throws Exception {
        MediaExtractor videoExtractor = new MediaExtractor();
        videoExtractor.setDataSource(videoOnly.getAbsolutePath());
        int videoTrack = 0; // the only track in a video-only file
        MediaFormat videoFormat = videoExtractor.getTrackFormat(videoTrack);
        videoExtractor.selectTrack(videoTrack);

        MediaExtractor audioExtractor = new MediaExtractor();
        audioExtractor.setDataSource(audioOnly.getAbsolutePath());
        int audioTrack = 0;
        MediaFormat audioFormat = audioExtractor.getTrackFormat(audioTrack);
        audioExtractor.selectTrack(audioTrack);

        MediaMuxer muxer = new MediaMuxer(output.getAbsolutePath(),
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        int outVideoTrack = muxer.addTrack(videoFormat);
        int outAudioTrack = muxer.addTrack(audioFormat);
        muxer.start();

        ByteBuffer buffer = ByteBuffer.allocate(1 << 20);
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

        while (true) {
            int size = videoExtractor.readSampleData(buffer, 0);
            if (size < 0) break;
            info.offset = 0;
            info.size = size;
            info.presentationTimeUs = videoExtractor.getSampleTime();
            info.flags = videoExtractor.getSampleFlags();
            muxer.writeSampleData(outVideoTrack, buffer, info);
            videoExtractor.advance();
        }
        while (true) {
            int size = audioExtractor.readSampleData(buffer, 0);
            if (size < 0) break;
            info.offset = 0;
            info.size = size;
            info.presentationTimeUs = audioExtractor.getSampleTime();
            info.flags = audioExtractor.getSampleFlags();
            muxer.writeSampleData(outAudioTrack, buffer, info);
            audioExtractor.advance();
        }

        muxer.stop();
        muxer.release();
        videoExtractor.release();
        audioExtractor.release();
    }

    private static void copyFile(File src, File dst) throws Exception {
        try (java.io.FileInputStream in = new java.io.FileInputStream(src);
             java.io.FileOutputStream out = new java.io.FileOutputStream(dst)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
    }
}
