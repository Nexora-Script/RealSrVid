package com.tumuyan.ncnn.realsr.video;

import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.util.Locale;

/**
 * Decodes a video into a numbered PNG frame sequence (input.png/000001.png, 000002.png, ...)
 * using MediaCodec in surfaceless/YUV mode, and separately extracts the original audio track
 * (compressed samples copied as-is, no re-encode) so it can be muxed back after upscaling.
 *
 * No ffmpeg / native binary involved — this is pure Android SDK.
 */
public class VideoFrameExtractor {

    private static final String TAG = "VideoFrameExtractor";

    public static class VideoInfo {
        public int width;
        public int height;
        public int rotationDegrees;
        public float frameRate;      // best-effort; falls back to 30 if the container doesn't report it
        public long durationUs;
        public boolean hasAudio;
    }

    public interface ProgressCallback {
        /** Called after each frame is written. frameIndex is 1-based. */
        void onFrameExtracted(int frameIndex);
    }

    /**
     * Extracts every decodable video frame from {@code inputVideoPath} into
     * {@code outFrameDir} as zero-padded PNGs (frame_000001.png ...).
     *
     * @return the VideoInfo describing the source, needed later to re-encode at the same fps.
     */
    public static VideoInfo extractFrames(String inputVideoPath, File outFrameDir,
                                           ProgressCallback callback) throws Exception {
        if (!outFrameDir.exists() && !outFrameDir.mkdirs()) {
            throw new Exception("Could not create frame directory: " + outFrameDir);
        }

        MediaExtractor extractor = new MediaExtractor();
        extractor.setDataSource(inputVideoPath);

        int videoTrackIndex = -1;
        MediaFormat videoFormat = null;
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            MediaFormat format = extractor.getTrackFormat(i);
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("video/")) {
                videoTrackIndex = i;
                videoFormat = format;
                break;
            }
        }
        if (videoTrackIndex < 0 || videoFormat == null) {
            extractor.release();
            throw new Exception("No video track found in " + inputVideoPath);
        }

        VideoInfo info = new VideoInfo();
        info.width = videoFormat.getInteger(MediaFormat.KEY_WIDTH);
        info.height = videoFormat.getInteger(MediaFormat.KEY_HEIGHT);
        info.rotationDegrees = videoFormat.containsKey(MediaFormat.KEY_ROTATION)
                ? videoFormat.getInteger(MediaFormat.KEY_ROTATION) : 0;
        info.durationUs = videoFormat.containsKey(MediaFormat.KEY_DURATION)
                ? videoFormat.getLong(MediaFormat.KEY_DURATION) : 0;
        info.frameRate = 30f;
        if (videoFormat.containsKey(MediaFormat.KEY_FRAME_RATE)) {
            try {
                info.frameRate = videoFormat.getInteger(MediaFormat.KEY_FRAME_RATE);
            } catch (ClassCastException e) {
                info.frameRate = videoFormat.getFloat(MediaFormat.KEY_FRAME_RATE);
            }
        }
        info.hasAudio = findTrackIndex(extractor, "audio/") >= 0;

        extractor.selectTrack(videoTrackIndex);

        String mime = videoFormat.getString(MediaFormat.KEY_MIME);
        MediaCodec decoder = MediaCodec.createDecoderByType(mime);
        decoder.configure(videoFormat, null, null, 0);
        decoder.start();

        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
        boolean inputDone = false;
        boolean outputDone = false;
        int frameIndex = 0;

        while (!outputDone) {
            if (!inputDone) {
                int inIndex = decoder.dequeueInputBuffer(10000);
                if (inIndex >= 0) {
                    ByteBuffer inputBuffer = decoder.getInputBuffer(inIndex);
                    int sampleSize = extractor.readSampleData(inputBuffer, 0);
                    if (sampleSize < 0) {
                        decoder.queueInputBuffer(inIndex, 0, 0, 0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        inputDone = true;
                    } else {
                        long presentationTimeUs = extractor.getSampleTime();
                        decoder.queueInputBuffer(inIndex, 0, sampleSize, presentationTimeUs, 0);
                        extractor.advance();
                    }
                }
            }

            int outIndex = decoder.dequeueOutputBuffer(bufferInfo, 10000);
            if (outIndex >= 0) {
                if (bufferInfo.size > 0) {
                    Image image = decoder.getOutputImage(outIndex);
                    if (image != null) {
                        frameIndex++;
                        Bitmap bmp = yuv420888ToBitmap(image, info.rotationDegrees);
                        File outFile = new File(outFrameDir,
                                String.format(Locale.US, "frame_%06d.png", frameIndex));
                        try (FileOutputStream fos = new FileOutputStream(outFile)) {
                            bmp.compress(Bitmap.CompressFormat.PNG, 100, fos);
                        }
                        bmp.recycle();
                        image.close();
                        if (callback != null) callback.onFrameExtracted(frameIndex);
                    }
                }
                decoder.releaseOutputBuffer(outIndex, false);
                if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    outputDone = true;
                }
            }
        }

        decoder.stop();
        decoder.release();
        extractor.release();

        if (frameIndex == 0) {
            throw new Exception("No frames could be decoded from " + inputVideoPath);
        }

        Log.i(TAG, "Extracted " + frameIndex + " frames at " + info.width + "x" + info.height
                + " @ " + info.frameRate + "fps");
        return info;
    }

    /**
     * Copies the audio track from {@code inputVideoPath} into its own small container file,
     * sample-for-sample (no decode/re-encode), so it can be muxed back in after re-encoding video.
     * Returns null if the source has no audio track.
     */
    public static File extractAudioTrack(String inputVideoPath, File outAudioFile) throws Exception {
        MediaExtractor extractor = new MediaExtractor();
        extractor.setDataSource(inputVideoPath);

        int audioTrackIndex = findTrackIndex(extractor, "audio/");
        if (audioTrackIndex < 0) {
            extractor.release();
            return null;
        }
        MediaFormat audioFormat = extractor.getTrackFormat(audioTrackIndex);
        extractor.selectTrack(audioTrackIndex);

        MediaMuxer muxer = new MediaMuxer(outAudioFile.getAbsolutePath(),
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        int muxerAudioTrack = muxer.addTrack(audioFormat);
        muxer.start();

        ByteBuffer buffer = ByteBuffer.allocate(1 << 20); // 1MB scratch buffer
        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
        while (true) {
            int sampleSize = extractor.readSampleData(buffer, 0);
            if (sampleSize < 0) break;
            bufferInfo.offset = 0;
            bufferInfo.size = sampleSize;
            bufferInfo.presentationTimeUs = extractor.getSampleTime();
            bufferInfo.flags = extractor.getSampleFlags();
            muxer.writeSampleData(muxerAudioTrack, buffer, bufferInfo);
            extractor.advance();
        }

        muxer.stop();
        muxer.release();
        extractor.release();
        return outAudioFile;
    }

    private static int findTrackIndex(MediaExtractor extractor, String mimePrefix) {
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            MediaFormat format = extractor.getTrackFormat(i);
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith(mimePrefix)) return i;
        }
        return -1;
    }

    /**
     * Converts a YUV_420_888 Image to an ARGB Bitmap via an NV21 -> JPEG(q100) -> Bitmap round trip.
     * This avoids per-device plane-stride bugs that hand-rolled RGB conversion is prone to, at the
     * cost of one (visually lossless at q100) JPEG re-compression per frame.
     */
    private static Bitmap yuv420888ToBitmap(Image image, int rotationDegrees) {
        byte[] nv21 = yuv420888ToNv21(image);
        YuvImage yuvImage = new YuvImage(nv21, ImageFormat.NV21, image.getWidth(), image.getHeight(), null);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        yuvImage.compressToJpeg(new Rect(0, 0, image.getWidth(), image.getHeight()), 100, baos);
        byte[] jpegBytes = baos.toByteArray();
        Bitmap bmp = android.graphics.BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.length);

        if (rotationDegrees != 0 && bmp != null) {
            android.graphics.Matrix matrix = new android.graphics.Matrix();
            matrix.postRotate(rotationDegrees);
            Bitmap rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), matrix, true);
            bmp.recycle();
            return rotated;
        }
        return bmp;
    }

    private static byte[] yuv420888ToNv21(Image image) {
        int width = image.getWidth();
        int height = image.getHeight();
        Image.Plane[] planes = image.getPlanes();

        byte[] nv21 = new byte[width * height * 3 / 2];

        ByteBuffer yBuffer = planes[0].getBuffer();
        int yRowStride = planes[0].getRowStride();
        int pos = 0;
        for (int row = 0; row < height; row++) {
            yBuffer.position(row * yRowStride);
            yBuffer.get(nv21, pos, width);
            pos += width;
        }

        ByteBuffer uBuffer = planes[1].getBuffer();
        ByteBuffer vBuffer = planes[2].getBuffer();
        int uvRowStride = planes[1].getRowStride();
        int uvPixelStride = planes[1].getPixelStride();
        int chromaWidth = width / 2;
        int chromaHeight = height / 2;

        for (int row = 0; row < chromaHeight; row++) {
            for (int col = 0; col < chromaWidth; col++) {
                int uIndex = row * uvRowStride + col * uvPixelStride;
                int vIndex = row * uvRowStride + col * uvPixelStride;
                // NV21 wants V then U, interleaved
                nv21[pos + row * width + col * 2] = vBuffer.get(vIndex);
                nv21[pos + row * width + col * 2 + 1] = uBuffer.get(uIndex);
            }
        }
        return nv21;
    }
}
