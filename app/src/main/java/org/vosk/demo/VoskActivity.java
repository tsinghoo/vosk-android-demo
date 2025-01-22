// Copyright 2019 Alpha Cephei Inc.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//       http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package org.vosk.demo;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.text.method.ScrollingMovementMethod;
import android.widget.Button;
import android.widget.TextView;
import android.widget.ToggleButton;

import be.tarsos.dsp.AudioDispatcher;
import be.tarsos.dsp.AudioEvent;
import be.tarsos.dsp.io.android.AudioDispatcherFactory;
import be.tarsos.dsp.pitch.PitchDetectionHandler;
import be.tarsos.dsp.pitch.PitchDetectionResult;
import be.tarsos.dsp.pitch.PitchProcessor;
import be.tarsos.dsp.pitch.PitchProcessor.PitchEstimationAlgorithm;

import org.jtransforms.fft.DoubleFFT_1D;
import org.vosk.LibVosk;
import org.vosk.LogLevel;
import org.vosk.Model;
import org.vosk.Recognizer;
import org.vosk.android.RecognitionListener;
import org.vosk.android.SpeechService;
import org.vosk.android.SpeechStreamService;
import org.vosk.android.StorageService;

import java.io.IOException;
import java.io.InputStream;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.res.TypedArrayUtils;

public class VoskActivity extends Activity implements
        RecognitionListener {

    static private final int STATE_START = 0;
    static private final int STATE_READY = 1;
    static private final int STATE_DONE = 2;
    static private final int STATE_FILE = 3;
    static private final int STATE_MIC = 4;
    static double threshold = 2000; // 拍手声音阈值
    /* Used to handle permission request */
    private static final int PERMISSIONS_REQUEST_RECORD_AUDIO = 1;
    public static final int SAMPLE_RATE_IN_HZ = 22050;

    private Model model;
    private SpeechService speechService;
    private SpeechStreamService speechStreamService;
    private TextView resultView;

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.main);

        // Setup layout
        resultView = findViewById(R.id.result_text);
        setUiState(STATE_START);

        findViewById(R.id.recognize_file).setOnClickListener(view -> recognizeFile());
        findViewById(R.id.recognize_mic).setOnClickListener(view -> recognizeMicrophone());
        findViewById(R.id.vad).setOnClickListener(view -> vad1());
        ((ToggleButton) findViewById(R.id.pause)).setOnCheckedChangeListener((view, isChecked) -> pause(isChecked));

        LibVosk.setLogLevel(LogLevel.INFO);

        // Check if user has given permission to record audio, init the model after permission is granted
        int permissionCheck = ContextCompat.checkSelfPermission(getApplicationContext(), Manifest.permission.RECORD_AUDIO);
        if (permissionCheck != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.RECORD_AUDIO}, PERMISSIONS_REQUEST_RECORD_AUDIO);
        } else {
            initModel();
        }
    }

    private void initModel() {
        StorageService.unpack(this, "vosk-model-small-cn-0.22", "model",
                (model) -> {
                    this.model = model;
                    setUiState(STATE_READY);
                },
                (exception) -> setErrorState("Failed to unpack the model" + exception.getMessage()));
    }

    private void vad() {

        AudioDispatcher dispatcher = AudioDispatcherFactory.fromDefaultMicrophone(SAMPLE_RATE_IN_HZ, 1024, 0);
        dispatcher.addAudioProcessor(new PitchProcessor(PitchEstimationAlgorithm.FFT_YIN, SAMPLE_RATE_IN_HZ, 1024, new PitchDetectionHandler() {

            @Override
            public void handlePitch(PitchDetectionResult pitchDetectionResult,
                                    AudioEvent audioEvent) {
                final float pitchInHz = pitchDetectionResult.getPitch();
                double amplitude = audioEvent.getRMS();
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        resultView.setText("" + pitchInHz + "\n");
                    }
                });

            }
        }));
        new Thread(dispatcher, "Audio Dispatcher").start();
    }

    private double calculateMaxFrequency(short[] buffer, int read, int sampleRate) {
        // 将short数组转换为double数组
        double[] audioData = new double[read];
        for (int i = 0; i < read; i++) {
            audioData[i] = buffer[i];
        }

        // 创建FFT实例
        DoubleFFT_1D fft = new DoubleFFT_1D(read);
        fft.realForward(audioData);

        // 计算频率幅值
        double maxAmplitude = -1;
        int maxIndex = -1;
        for (int i = 0; i < read / 2; i++) {
            double real = audioData[2 * i];
            double imaginary = audioData[2 * i + 1];
            double amplitude = Math.sqrt(real * real + imaginary * imaginary);

            if (amplitude > maxAmplitude) {
                maxAmplitude = amplitude;
                maxIndex = i;
            }
        }

        // 计算最大频率
        return (double) maxIndex * sampleRate / read;
    }

    private void vad1() {

        final long MAX_CLAP_INTERVAL = 1000; // 两次拍手之间的最大间隔（毫秒）
        final int REQUIRED_CLAPS = 3; // 需要检测的连续拍手次数
        final long[] clapTimes = new long[REQUIRED_CLAPS];
        final int[] clapCount = {0};

        int bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE_IN_HZ, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);

        // Check for audio recording permission
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.RECORD_AUDIO}, PERMISSIONS_REQUEST_RECORD_AUDIO);
            return;
        }

        AudioRecord audioRecord = null;
        try {
            audioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE_IN_HZ, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize);
            audioRecord.startRecording();
        } catch (SecurityException e) {
            setErrorState("Audio recording permission not granted.");
            return;
        }

        AudioRecord finalAudioRecord = audioRecord;
        new Thread(() -> {
            short[] buffer = new short[bufferSize];
            double max = 0;
            double[] arr = new double[3];
            while (true) {
                runOnUiThread(() -> {
                    TextView thresholdView = findViewById(R.id.threshold);
                     threshold = Double.parseDouble(thresholdView.getText().toString());
                    // 使用thresholdValue进行后续操作
                });
                int read = finalAudioRecord.read(buffer, 0, buffer.length);
                if (read > 0) {
                    //double amplitude = calculateMaxFrequency(buffer, read, SAMPLE_RATE_IN_HZ);
                    double amplitude = calculateRMS(buffer, read);
                    long currentTime = System.currentTimeMillis();

                    /*
                    int minIndex = 0;
                    for (int i = 1; i < arr.length; i++) {
                        if (arr[i] < arr[minIndex]) {
                            minIndex = i;
                        }
                    }
                    if (amplitude > arr[minIndex]) {
                        arr[minIndex] = amplitude;

                        runOnUiThread(() ->{
                                    resultView.setText("\n"+arr[0]);
                                    resultView.append("\n"+arr[1]);
                                    resultView.append("\n"+arr[2]);
                        });
                    }
                    */


                    if (amplitude > threshold) {
                        if (clapCount[0] == 0 ||
                                (currentTime - clapTimes[clapCount[0] - 1] < MAX_CLAP_INTERVAL &&
                                        currentTime - clapTimes[clapCount[0] - 1] > 200)) { // 防止同一个拍手被多次检测

                            clapTimes[clapCount[0]] = currentTime;
                            clapCount[0]++;

                            final String message = "检测到第 " + clapCount[0] + " 次拍手:" + amplitude;
                            runOnUiThread(() -> resultView.append(message + "\n"));

                            if (clapCount[0] >= REQUIRED_CLAPS) {
                                // 检测到连续三次拍手
                                runOnUiThread(() -> {
                                    resultView.append("成功检测到连续三次拍手！\n");
                                });
                                clapCount[0] = 0; // 重置计数
                            }
                        }
                    }

                    // 如果超过最大间隔时间，重置计数
                    if (clapCount[0] > 0 && currentTime - clapTimes[clapCount[0] - 1] > MAX_CLAP_INTERVAL) {
                        clapCount[0] = 0;
                        runOnUiThread(() -> resultView.append("重置拍手计数\n"));
                    }
                }
            }
        }).start();
    }

    private double calculateRMS(short[] buffer, int read) {
        double sum = 0;
        for (int i = 0; i < read; i++) {
            sum += buffer[i] * buffer[i];
        }
        return Math.sqrt(sum / read);
    }


    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == PERMISSIONS_REQUEST_RECORD_AUDIO) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // Recognizer initialization is a time-consuming and it involves IO,
                // so we execute it in async task
                initModel();
            } else {
                finish();
            }
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        if (speechService != null) {
            speechService.stop();
            speechService.shutdown();
        }

        if (speechStreamService != null) {
            speechStreamService.stop();
        }
    }

    @Override
    public void onResult(String hypothesis) {
        resultView.append(hypothesis + "\n");
    }

    @Override
    public void onFinalResult(String hypothesis) {
        resultView.append(hypothesis + "\n");
        setUiState(STATE_DONE);
        if (speechStreamService != null) {
            speechStreamService = null;
        }
    }

    @Override
    public void onPartialResult(String hypothesis) {
        resultView.append(hypothesis + "\n");
    }

    @Override
    public void onError(Exception e) {
        setErrorState(e.getMessage());
    }

    @Override
    public void onTimeout() {
        setUiState(STATE_DONE);
    }

    private void setUiState(int state) {
        switch (state) {
            case STATE_START:
                resultView.setText(R.string.preparing);
                resultView.setMovementMethod(new ScrollingMovementMethod());
                findViewById(R.id.recognize_file).setEnabled(false);
                findViewById(R.id.recognize_mic).setEnabled(false);
                findViewById(R.id.pause).setEnabled((false));
                break;
            case STATE_READY:
                resultView.setText(R.string.ready);
                ((Button) findViewById(R.id.recognize_mic)).setText(R.string.recognize_microphone);
                findViewById(R.id.recognize_file).setEnabled(true);
                findViewById(R.id.recognize_mic).setEnabled(true);
                findViewById(R.id.pause).setEnabled((false));
                break;
            case STATE_DONE:
                ((Button) findViewById(R.id.recognize_file)).setText(R.string.recognize_file);
                ((Button) findViewById(R.id.recognize_mic)).setText(R.string.recognize_microphone);
                findViewById(R.id.recognize_file).setEnabled(true);
                findViewById(R.id.recognize_mic).setEnabled(true);
                findViewById(R.id.pause).setEnabled((false));
                ((ToggleButton) findViewById(R.id.pause)).setChecked(false);
                break;
            case STATE_FILE:
                ((Button) findViewById(R.id.recognize_file)).setText(R.string.stop_file);
                resultView.setText(getString(R.string.starting));
                findViewById(R.id.recognize_mic).setEnabled(false);
                findViewById(R.id.recognize_file).setEnabled(true);
                findViewById(R.id.pause).setEnabled((false));
                break;
            case STATE_MIC:
                ((Button) findViewById(R.id.recognize_mic)).setText(R.string.stop_microphone);
                resultView.setText(getString(R.string.say_something));
                findViewById(R.id.recognize_file).setEnabled(false);
                findViewById(R.id.recognize_mic).setEnabled(true);
                findViewById(R.id.pause).setEnabled((true));
                break;
            default:
                throw new IllegalStateException("Unexpected value: " + state);
        }
    }

    private void setErrorState(String message) {
        resultView.setText(message);
        ((Button) findViewById(R.id.recognize_mic)).setText(R.string.recognize_microphone);
        findViewById(R.id.recognize_file).setEnabled(false);
        findViewById(R.id.recognize_mic).setEnabled(false);
    }

    private void recognizeFile() {
        if (speechStreamService != null) {
            setUiState(STATE_DONE);
            speechStreamService.stop();
            speechStreamService = null;
        } else {
            setUiState(STATE_FILE);
            try {
                Recognizer rec = new Recognizer(model, 16000.f, "[\"one zero zero zero one\", " +
                        "\"oh zero one two three four five six seven eight nine\", \"[unk]\"]");

                InputStream ais = getAssets().open(
                        "10001-90210-01803.wav");
                if (ais.skip(44) != 44) throw new IOException("File too short");

                speechStreamService = new SpeechStreamService(rec, ais, 16000);
                speechStreamService.start(this);
            } catch (IOException e) {
                setErrorState(e.getMessage());
            }
        }
    }

    private void recognizeMicrophone() {
        if (speechService != null) {
            setUiState(STATE_DONE);
            speechService.stop();
            speechService = null;
        } else {
            setUiState(STATE_MIC);
            try {
                Recognizer rec = new Recognizer(model, 16000.0f);
                speechService = new SpeechService(rec, 16000.0f);
                speechService.startListening(this);
            } catch (IOException e) {
                setErrorState(e.getMessage());
            }
        }
    }


    private void pause(boolean checked) {
        if (speechService != null) {
            speechService.setPause(checked);
        }
    }

}
