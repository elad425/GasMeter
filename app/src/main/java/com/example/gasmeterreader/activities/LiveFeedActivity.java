package com.example.gasmeterreader.activities;

import android.animation.ObjectAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.gasmeterreader.R;
import com.example.gasmeterreader.adapters.ReadSelectorAdapter;
import com.example.gasmeterreader.entities.Read;
import com.example.gasmeterreader.viewModels.LiveFeedViewModel;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class LiveFeedActivity extends AppCompatActivity {
    private static final long ANIMATION_DURATION = 300;
    private static final float SCALE_FACTOR = 1.2f;

    private PreviewView previewView;
    private MaterialButton flashButton;
    private MaterialButton nextButton;
    private MaterialButton selectReadButton;
    private TextView dataResultText;
    private ImageView detectionStatusIcon;
    private TextView serialText;
    private TextView apartmentText;
    private TextView lastReadText;

    private Camera camera;
    private LiveFeedViewModel viewModel;
    private ReadSelectorAdapter readSelectorAdapter;
    private BottomSheetDialog bottomSheetDialog;
    private ExecutorService cameraExecutor;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_live_feed);
        getWindow().setStatusBarColor(ContextCompat.getColor(this, R.color.transparent));
        overridePendingTransition(R.animator.slide_in_right, R.animator.slide_out_left);
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                finish();
                overridePendingTransition(R.animator.slide_in_left, R.animator.slide_out_right);
            }
        });

        initializeViewModel();
        initializeViews();
        setupObservers();
        initializeCameraExecutor();
        startCamera();
    }

    private void initializeViewModel() {
        viewModel = new ViewModelProvider(this).get(LiveFeedViewModel.class);
        viewModel.setBuilding(getIntent().getIntExtra("building_center", -1));
    }

    private void initializeViews() {
        previewView = findViewById(R.id.viewFinder);
        flashButton = findViewById(R.id.flashButton);
        nextButton = findViewById(R.id.reset);
        dataResultText = findViewById(R.id.dataResultText);
        detectionStatusIcon = findViewById(R.id.detectionStatusIcon);
        serialText = findViewById(R.id.serial);
        apartmentText = findViewById(R.id.apartment);
        lastReadText = findViewById(R.id.lastRead);
        selectReadButton = findViewById(R.id.selectReadButton);

        setupButtonListeners();
    }

    private void setupButtonListeners() {
        selectReadButton.setOnClickListener(v -> {
            animateButton(selectReadButton);
            showReadSelector();
        });

        flashButton.setOnClickListener(v -> {
            animateButton(flashButton);
            viewModel.toggleFlash();
        });

        nextButton.setOnClickListener(v -> {
            animateButton(nextButton);
            viewModel.nextRead();
            viewModel.resetError();
        });
    }

    private void setupObservers() {
        viewModel.getDetectionStatusIcon().observe(this, detectionStatusIcon::setImageResource);

        viewModel.getListPlace().observe(this, place -> {
            updateReadDisplay(place);
            viewModel.resetError();
        });

        viewModel.getDataResultText().observe(this, text -> {
            if (!text.contentEquals(dataResultText.getText())) {
                dataResultText.setText(text);
                animateText(dataResultText);
            }
        });

        viewModel.getIsDetected().observe(this, isDetected -> {
            if (Boolean.TRUE.equals(isDetected)) {
                animateDetection();
                triggerVibration();
                viewModel.enterRead();
            }
        });

        viewModel.getIsFlashOn().observe(this, this::updateFlashState);

        viewModel.getErrorCount().observe(this, this::handleErrorCount);
    }

    private void updateFlashState(Boolean isFlashOn) {
        if (camera != null && camera.getCameraInfo().hasFlashUnit()) {
            camera.getCameraControl().enableTorch(isFlashOn);
            flashButton.setIconResource(isFlashOn ? R.drawable.ic_flash_off : R.drawable.ic_flash_on);
        }
    }

    private void handleErrorCount(Integer errorCount) {
        if (errorCount > 150) {
            Toast.makeText(this, "קריאה מחוץ לטווח", Toast.LENGTH_SHORT).show();
            viewModel.setPaused(true);
            showNumberInputDialog();
            viewModel.resetError();
        }
    }

    private void showNumberInputDialog() {
        View viewInflated = LayoutInflater.from(this).inflate(R.layout.dialog_number_input, null);
        EditText input = viewInflated.findViewById(R.id.input);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("קריאה לא בטווח")
                .setView(viewInflated)
                .setPositiveButton("אישור", (dialogInterface, which) -> {
                    String enteredNumber = input.getText().toString();
                    if (!enteredNumber.isEmpty()) {
                        viewModel.setReadManual(enteredNumber);
                        viewModel.incrementListPlace();
                    }
                    viewModel.setPaused(false);
                })
                .setNegativeButton("ביטול", (dialogInterface, which) -> viewModel.setPaused(false))
                .setCancelable(false)
                .create();

        dialog.setOnDismissListener(dialogInterface -> viewModel.setPaused(false));
        dialog.show();
    }

    private void showReadSelector() {
        if (bottomSheetDialog == null) {
            initializeBottomSheetDialog();
        } else {
            resetBottomSheetSearch();
        }

        viewModel.setPaused(true);
        readSelectorAdapter.setReads(Objects.requireNonNull(viewModel.getReadList().getValue()));
        bottomSheetDialog.show();
    }

    private void initializeBottomSheetDialog() {
        bottomSheetDialog = new BottomSheetDialog(this);
        View bottomSheetView = getLayoutInflater().inflate(R.layout.bottom_sheet_read_selector, null);
        bottomSheetDialog.setContentView(bottomSheetView);
        bottomSheetDialog.setOnDismissListener(dialog -> viewModel.setPaused(false));

        setupRecyclerView(bottomSheetView);
        setupSearchField(bottomSheetView);
    }

    private void setupRecyclerView(View bottomSheetView) {
        RecyclerView recyclerView = bottomSheetView.findViewById(R.id.readsRecyclerView);
        recyclerView.setLayoutManager(new GridLayoutManager(this, 2));

        readSelectorAdapter = new ReadSelectorAdapter(this, position -> {
            viewModel.setListPlace(position);
            bottomSheetDialog.dismiss();
        });
        recyclerView.setAdapter(readSelectorAdapter);
    }

    private void setupSearchField(View bottomSheetView) {
        TextInputEditText searchEdit = bottomSheetView.findViewById(R.id.searchEdit);
        searchEdit.clearFocus();

        searchEdit.setOnClickListener(v -> {
            searchEdit.requestFocus();
            ((InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE))
                    .showSoftInput(searchEdit, InputMethodManager.SHOW_IMPLICIT);
        });

        searchEdit.addTextChangedListener(createSearchTextWatcher());
    }

    private TextWatcher createSearchTextWatcher() {
        return new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                readSelectorAdapter.filter(s.toString());
            }

            @Override
            public void afterTextChanged(android.text.Editable s) {}
        };
    }

    private void resetBottomSheetSearch() {
        TextInputEditText searchEdit = bottomSheetDialog.findViewById(R.id.searchEdit);
        if (searchEdit != null) {
            searchEdit.setText("");
            searchEdit.clearFocus();
        }
    }

    private void updateReadDisplay(int place) {
        List<Read> readList = Objects.requireNonNull(viewModel.getReadList().getValue());
        Read currentRead = readList.get(place);

        serialText.setText(String.valueOf(currentRead.getMeter_id()));
        apartmentText.setText("דירה " + currentRead.getApartment());
        lastReadText.setText(String.valueOf(currentRead.getLast_read()));

        animateText(serialText);
        animateText(apartmentText);
        animateText(lastReadText);
    }

    private void animateButton(MaterialButton button) {
        ObjectAnimator.ofFloat(button, "scaleX", 1f, 0.9f, 1f)
                .setDuration(200)
                .start();
        ObjectAnimator.ofFloat(button, "scaleY", 1f, 0.9f, 1f)
                .setDuration(200)
                .start();
    }

    private void animateDetection() {
        ObjectAnimator.ofFloat(detectionStatusIcon, "scaleX", 1f, SCALE_FACTOR, 1f)
                .setDuration(ANIMATION_DURATION)
                .start();
        ObjectAnimator.ofFloat(detectionStatusIcon, "scaleY", 1f, SCALE_FACTOR, 1f)
                .setDuration(ANIMATION_DURATION)
                .start();
    }

    private void animateText(TextView textView) {
        ObjectAnimator.ofFloat(textView, "alpha", 0f, 1f)
                .setDuration(ANIMATION_DURATION)
                .start();
    }

    private void triggerVibration() {
        Vibrator vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        if (vibrator != null && vibrator.hasVibrator()) {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(200, VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                vibrator.vibrate(200);
            }
        }
    }

    private void initializeCameraExecutor() {
        cameraExecutor = Executors.newSingleThreadExecutor();
    }

    private void startCamera() {
        ProcessCameraProvider.getInstance(this).addListener(() -> {
            try {
                bindPreview(ProcessCameraProvider.getInstance(this).get());
            } catch (Exception ignored) {}
        }, ContextCompat.getMainExecutor(this));
    }

    private void bindPreview(ProcessCameraProvider cameraProvider) {
        Preview preview = new Preview.Builder().build();
        CameraSelector cameraSelector = new CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                .build();

        ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build();

        imageAnalysis.setAnalyzer(cameraExecutor, imageProxy -> {
            if (Boolean.TRUE.equals(viewModel.getIsPaused().getValue())) {
                imageProxy.close();
                return;
            }

            processCameraImage(imageProxy);
        });

        preview.setSurfaceProvider(previewView.getSurfaceProvider());

        try {
            cameraProvider.unbindAll();
            camera = cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis);
            flashButton.setVisibility(camera.getCameraInfo().hasFlashUnit() ? View.VISIBLE : View.GONE);
        } catch (Exception ignored) {}
    }

    private void processCameraImage(ImageProxy imageProxy) {
        Bitmap bitmapBuffer = Bitmap.createBitmap(
                imageProxy.getWidth(),
                imageProxy.getHeight(),
                Bitmap.Config.ARGB_8888
        );

        bitmapBuffer.copyPixelsFromBuffer(imageProxy.getPlanes()[0].getBuffer());
        imageProxy.close();

        Matrix matrix = new Matrix();
        matrix.postRotate(imageProxy.getImageInfo().getRotationDegrees());

        Bitmap rotatedBitmap = Bitmap.createBitmap(
                bitmapBuffer, 0, 0,
                bitmapBuffer.getWidth(),
                bitmapBuffer.getHeight(),
                matrix, true
        );

        viewModel.processImage(rotatedBitmap);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        cameraExecutor.shutdown();
    }

}