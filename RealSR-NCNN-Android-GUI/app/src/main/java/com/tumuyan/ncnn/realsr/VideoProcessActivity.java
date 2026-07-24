package com.tumuyan.ncnn.realsr;

import static com.tumuyan.ncnn.realsr.UriUntils.getFPUriToPath;
import static com.tumuyan.ncnn.realsr.UriUntils.getFileName;

import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.IBinder;
import android.util.Log;
import android.view.MenuItem;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.tumuyan.ncnn.realsr.video.VideoFrameEncoder;
import com.tumuyan.ncnn.realsr.video.VideoFrameExtractor;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Video upscaling screen. Mirrors DirectoryProcessActivity's flow, but the "directory" being
 * batch-upscaled is a folder of frames this activity extracts from the picked video first, and
 * the result is re-encoded back into a video afterward. The realsr-ncnn batch step itself is
 * unmodified — same ProcessingService, same command list, same binaries.
 */
public class VideoProcessActivity extends AppCompatActivity {

    private static final String TAG = "VideoProcessActivity";

    private TextView tvSelectedFile, tvLog;
    private Button btnSelectVideo, btnStartProcess, btnStopProcess;
    private Spinner spinnerModel;
    private CheckBox cbCap1080p;
    private EditText etMaxHeight;

    private ProcessingService processingService;
    private boolean isBound = false;
    private boolean isProcessing = false;

    private String dir;          // realsr-ncnn working dir (binaries live here) — same as MainActivity
    private String cache_dir;
    private int tileSize;
    private boolean useCPU;
    private String threadCount;
    private int mnnBackend;
    private int notifySetting;

    private CommandListManager commandListManager;
    private String[] commandList;
    private ProgressLogHelper progressLog;

    private Uri pickedVideoUri;
    private String pickedVideoPath;
    private File workRoot;        // cache_dir/video_work/<timestamp>
    private File framesInDir;
    private File framesOutDir;
    private File audioTrackFile;
    private VideoFrameExtractor.VideoInfo videoInfo;

    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();

    private final ActivityResultLauncher<String> pickVideoLauncher =
            registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
                if (uri == null) return;
                pickedVideoUri = uri;
                String name = getFileName(uri, this);
                tvSelectedFile.setText(name != null ? name : uri.toString());
                updateStartButtonState();
            });

    private ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName className, IBinder service) {
            ProcessingService.LocalBinder binder = (ProcessingService.LocalBinder) service;
            processingService = binder.getService();
            isBound = true;
        }

        @Override
        public void onServiceDisconnected(ComponentName arg0) {
            isBound = false;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_video_process);

        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        cache_dir = getCacheDir().getAbsolutePath();
        dir = cache_dir + "/realsr";

        initViews();
        loadSettings();
        setupListeners();

        Intent serviceIntent = new Intent(this, ProcessingService.class);
        bindService(serviceIntent, connection, BIND_AUTO_CREATE);
    }

    private void initViews() {
        tvSelectedFile = findViewById(R.id.tv_selected_video);
        tvLog = findViewById(R.id.tv_video_log);
        btnSelectVideo = findViewById(R.id.btn_select_video);
        btnStartProcess = findViewById(R.id.btn_start_video_process);
        btnStopProcess = findViewById(R.id.btn_stop_video_process);
        spinnerModel = findViewById(R.id.spinner_video_model);
        cbCap1080p = findViewById(R.id.cb_cap_1080p);
        etMaxHeight = findViewById(R.id.et_max_height);

        setTitle(R.string.video_process_title);
        btnStopProcess.setEnabled(false);
        etMaxHeight.setText("1080");
    }

    private void loadSettings() {
        SharedPreferences sp = getSharedPreferences("config", MODE_PRIVATE);
        tileSize = sp.getInt("tileSize", 0);
        useCPU = sp.getBoolean("useCPU", false);
        threadCount = sp.getString("threadCount", "");
        mnnBackend = sp.getInt("mnnBackend", 3);
        notifySetting = sp.getInt("notify", 2);

        // Reuse whichever cap-to-1080p preference the rest of the app already stores, if present.
        boolean defaultCap = sp.getBoolean("videoCap1080p", true);
        cbCap1080p.setChecked(defaultCap);

        String[] presetLabels = getResources().getStringArray(R.array.style_array);
        boolean useCustomLabel = sp.getBoolean("useCustomLabel", false);
        commandListManager = new CommandListManager(presetLabels,
                sp.getString("extraPath", "").trim(),
                sp.getString("extraCommand", "").trim(),
                sp.getString("classicalFilters", getString(R.string.default_classical_filters)).split("\\s+"),
                sp.getString("magickFilters", getString(R.string.default_magick_filters)).split("\\s+"));
        commandListManager.loadCustomLabels(sp.getString("customLabels", ""));

        Set<String> hiddenPrograms = sp.getStringSet("hiddenPrograms", new HashSet<String>());
        // Only ncnn upscalers make sense frame-by-frame at video speed/quality tradeoffs —
        // directory-supported commands already filters to batch-capable programs.
        commandList = commandListManager.getDirectorySupportedCommands(hiddenPrograms);
        String[] displayLabels = commandListManager.getDirectorySupportedLabels(useCustomLabel, hiddenPrograms);

        if (commandList.length == 0) {
            Toast.makeText(this, R.string.dir_no_supported_commands, Toast.LENGTH_LONG).show();
        }

        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, displayLabels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerModel.setAdapter(adapter);
        spinnerModel.setSelection(0);
    }

    private void setupListeners() {
        btnSelectVideo.setOnClickListener(v -> pickVideoLauncher.launch("video/*"));
        btnStartProcess.setOnClickListener(v -> startVideoProcess());
        btnStopProcess.setOnClickListener(v -> stopVideoProcess());

        spinnerModel.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, android.view.View view, int pos, long id) {
                updateStartButtonState();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    private void updateStartButtonState() {
        btnStartProcess.setEnabled(pickedVideoUri != null && !isProcessing && commandList.length > 0);
    }

    // ---------------------------------------------------------------------------------------
    // Pipeline: copy -> extract frames -> batch upscale (existing pipeline) -> encode -> save
    // ---------------------------------------------------------------------------------------

    private void startVideoProcess() {
        if (!isBound || processingService == null) {
            Toast.makeText(this, R.string.dir_service_error, Toast.LENGTH_SHORT).show();
            return;
        }
        int modelIndex = spinnerModel.getSelectedItemPosition();
        if (modelIndex < 0 || modelIndex >= commandList.length) {
            Toast.makeText(this, R.string.dir_model_error, Toast.LENGTH_SHORT).show();
            return;
        }

        isProcessing = true;
        btnStartProcess.setEnabled(false);
        btnStopProcess.setEnabled(true);

        progressLog = new ProgressLogHelper();
        progressLog.reset();
        progressLog.appendLine(getString(R.string.video_log_preparing));
        tvLog.setText(progressLog.getDisplayText());

        String baseCommand = commandList[modelIndex];

        ioExecutor.submit(() -> {
            try {
                runPipeline(baseCommand);
            } catch (Exception e) {
                Log.e(TAG, "Video pipeline failed", e);
                runOnUiThread(() -> {
                    progressLog.appendLine("Error: " + e.getMessage());
                    tvLog.setText(progressLog.getDisplayText());
                    finishProcessing(false);
                });
            }
        });
    }

    private void runPipeline(String baseCommand) throws Exception {
        // 1. Resolve a real filesystem path for the picked content:// video.
        pickedVideoPath = getFPUriToPath(pickedVideoUri, this);
        if (pickedVideoPath == null) {
            throw new Exception("Could not resolve a file path for the selected video");
        }

        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        workRoot = new File(cache_dir + "/video_work", timestamp);
        framesInDir = new File(workRoot, "input.png");   // "input.png" name matches the existing
        framesOutDir = new File(workRoot, "output.png");  // directory-batch command substitution
        audioTrackFile = new File(workRoot, "audio.m4a");
        if (!framesInDir.mkdirs() || !framesOutDir.mkdirs()) {
            throw new Exception("Could not create working directories under " + workRoot);
        }

        // 2. Extract frames (same idea as the existing GIF handling: video == frame sequence).
        publishStatus(getString(R.string.video_log_extracting));
        videoInfo = VideoFrameExtractor.extractFrames(pickedVideoPath, framesInDir,
                frameIndex -> {
                    if (frameIndex % 15 == 0) {
                        publishStatus(getString(R.string.video_log_extracted_n, frameIndex));
                    }
                });

        // Audio is optional — copy it if present, encode step just skips muxing if null.
        if (videoInfo.hasAudio) {
            publishStatus(getString(R.string.video_log_audio));
            VideoFrameExtractor.extractAudioTrack(pickedVideoPath, audioTrackFile);
        }

        // 3. Hand the frame folder to the EXISTING, unmodified batch-upscale pipeline —
        //    identical command construction to DirectoryProcessActivity.startBatchProcess().
        publishStatus(getString(R.string.video_log_upscaling));
        boolean upscaleOk = runBatchUpscaleAndWait(baseCommand, framesInDir, framesOutDir);
        if (!upscaleOk) {
            throw new Exception(getString(R.string.video_log_upscale_failed));
        }

        // 4. Re-encode the upscaled frames back into a video, capping resolution if requested.
        publishStatus(getString(R.string.video_log_encoding));
        boolean cap = cbCap1080p.isChecked();
        int maxHeight = cap ? parseMaxHeight() : -1;

        String savePath = resolveSavePath();
        File outFile = new File(savePath);
        //noinspection ResultOfMethodCallIgnored
        outFile.getParentFile().mkdirs();

        VideoFrameEncoder.encode(framesOutDir, videoInfo.hasAudio ? audioTrackFile : null,
                videoInfo.frameRate, maxHeight, outFile,
                (frameIndex, total) -> {
                    if (frameIndex % 15 == 0 || frameIndex == total) {
                        publishStatus(getString(R.string.video_log_encoded_n, frameIndex, total));
                    }
                });

        // 5. Clean up frame/audio scratch space; keep only the final video.
        deleteRecursive(workRoot);

        runOnUiThread(() -> {
            progressLog.appendLine(getString(R.string.video_log_done, outFile.getAbsolutePath()));
            tvLog.setText(progressLog.getDisplayText());
            Toast.makeText(this, R.string.save_succeed, Toast.LENGTH_LONG).show();
            finishProcessing(true);
        });
    }

    /**
     * Runs the SAME command construction DirectoryProcessActivity uses for batch image folders,
     * against our extracted-frames folder, and blocks (via a latch) until ProcessingService
     * reports completion — so this background thread can move on to the encode step afterward.
     */
    private boolean runBatchUpscaleAndWait(String baseCommand, File inputDir, File outputDir) throws InterruptedException {
        StringBuilder cmdBuilder = new StringBuilder(baseCommand);
        if (baseCommand.matches("./(realsr|srmd|waifu2x|realcugan|mnnsr)-ncnn.+")) {
            if (tileSize > 0 && !baseCommand.contains(" -t "))
                cmdBuilder.append(" -t ").append(tileSize);
            if (!threadCount.isEmpty() && !baseCommand.contains(" -j "))
                cmdBuilder.append(" -j ").append(threadCount);
            if (useCPU && !baseCommand.startsWith("./srmd") && !baseCommand.startsWith("./mnnsr")
                    && !baseCommand.contains(" -g "))
                cmdBuilder.append(" -g -1");
            if (baseCommand.startsWith("./mnnsr") && !baseCommand.contains(" -b ")) {
                cmdBuilder.append(" -b ").append(mnnBackend);
            }
        }
        String finalCmd = cmdBuilder.toString();

        String safeInputPath = ShellUtils.escapeShellArgument(inputDir.getAbsolutePath() + "/");
        String safeOutputPath = ShellUtils.escapeShellArgument(outputDir.getAbsolutePath());
        String execCmd = finalCmd.replace("input.png", safeInputPath)
                .replace("output.png", safeOutputPath);

        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        boolean[] resultHolder = new boolean[]{false};

        runOnUiThread(() -> processingService.startTask(execCmd, dir, notifySetting,
                new ImageProcessor.ProcessCallback() {
                    @Override
                    public void onProgress(String line) {
                        runOnUiThread(() -> {
                            progressLog.appendLine(line);
                            tvLog.setText(progressLog.getDisplayText());
                        });
                    }

                    @Override
                    public void onCompleted(String result, boolean success) {
                        resultHolder[0] = success;
                        latch.countDown();
                    }

                    @Override
                    public void onError(String error) {
                        runOnUiThread(() -> {
                            progressLog.appendLine("Error: " + error);
                            tvLog.setText(progressLog.getDisplayText());
                        });
                        resultHolder[0] = false;
                        latch.countDown();
                    }
                }));

        latch.await();
        return resultHolder[0];
    }

    private int parseMaxHeight() {
        try {
            int h = Integer.parseInt(etMaxHeight.getText().toString().trim());
            return h > 0 ? h : 1080;
        } catch (NumberFormatException e) {
            return 1080;
        }
    }

    private String resolveSavePath() {
        SharedPreferences sp = getSharedPreferences("config", MODE_PRIVATE);
        String galleryPath = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
                + File.separator + "RealSR";
        String base = sp.getString("videoSavePath", "");
        if (base.isEmpty()) base = galleryPath;

        String name = pickedVideoUri != null ? getFileName(pickedVideoUri, this) : "video";
        String stem = name != null && name.contains(".") ? name.substring(0, name.lastIndexOf('.')) : "video";
        String suffix = cbCap1080p.isChecked() ? "_upscaled_1080p" : "_upscaled";
        return base + File.separator + stem + suffix + ".mp4";
    }

    private void publishStatus(String text) {
        runOnUiThread(() -> {
            progressLog.appendLine(text);
            tvLog.setText(progressLog.getDisplayText());
        });
    }

    private void stopVideoProcess() {
        if (processingService != null) {
            processingService.cancelTask();
        }
        progressLog.appendLine(getString(R.string.video_log_stopped));
        tvLog.setText(progressLog.getDisplayText());
        finishProcessing(false);
    }

    private void finishProcessing(boolean success) {
        isProcessing = false;
        btnStartProcess.setEnabled(pickedVideoUri != null);
        btnStopProcess.setEnabled(false);
    }

    private static void deleteRecursive(File file) {
        if (file == null || !file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) deleteRecursive(child);
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (isBound) {
            unbindService(connection);
            isBound = false;
        }
        ioExecutor.shutdownNow();
    }
}
