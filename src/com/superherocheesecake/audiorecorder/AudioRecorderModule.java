/**
 * This file was auto-generated by the Titanium Module SDK helper for Android
 * Appcelerator Titanium Mobile
 * Copyright (c) 2009-2013 by Appcelerator, Inc. All Rights Reserved.
 * Licensed under the terms of the Apache Public License
 * Please see the LICENSE included with this distribution for details.
 *
 */
package com.superherocheesecake.audiorecorder;

import java.io.*;
import java.util.Arrays;
import java.util.HashMap;

import org.appcelerator.kroll.KrollDict;
import org.appcelerator.kroll.KrollFunction;
import org.appcelerator.kroll.KrollModule;
import org.appcelerator.kroll.annotations.Kroll;

import org.appcelerator.titanium.TiApplication;

import android.content.Context;
import android.media.MediaRecorder;
import android.media.AudioRecord;
import android.media.AudioFormat;
import android.os.Environment;

import com.superherocheesecake.wavehelper.*;

@Kroll.module(name="AudioRecorder", id="com.superherocheesecake.audiorecorder")
public class AudioRecorderModule extends KrollModule
{
    @Kroll.constant public static final String Storage_INTERNAL = "internal";
    @Kroll.constant public static final String Storage_EXTERNAL = "external";

    private DataOutputStream fileStream = null;
    private Thread recordingThread      = null;
    
    private String      outputFile  = null;
    private AudioRecord recorder    = null;

    private KrollFunction successCallback = null;
    private KrollFunction errorCallback   = null;

    private String AUDIO_RECORDER_FOLDER = "audio_recorder";

    // Standard Debugging variables
    private static final String TAG = "AudioRecorderModule";

    // You can define constants with @Kroll.constant, for example:
    // @Kroll.constant public static final String EXTERNAL_NAME = value;
    
    public AudioRecorderModule()
    {
        super();
    }

    @Kroll.onAppCreate
    public static void onAppCreate(TiApplication app)
    {
        // NOOP
    }

    /**
     * Checks if external storage is mounted for R/W operations
     * @return
     */
    @Kroll.method
    public boolean isExternalStorageWritable() {
        String state = Environment.getExternalStorageState();
        if (Environment.MEDIA_MOUNTED.equals(state)) {
            return true;
        }

        return false;
    }

    /**
     * Check if the recorder is recording
     * @return Boolean
     */
    @Kroll.method
    public Boolean isRecording() {
        return (recorder instanceof AudioRecord && recorder.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING);
    }

    @SuppressWarnings("deprecation")
    private KrollFunction getCallback(final KrollDict options, final String name) {
        return (KrollFunction) options.get(name);
    }

    /**
     * Registers callbacks
     * @param args Arguments for callbacks
     */
    @Kroll.method
    public void registerCallbacks(HashMap args) {
        Object callback;

        // Save the callback functions, verifying that they are of the correct type
        if (args.containsKey("success")) {
            callback = args.get("success");
            if (callback instanceof KrollFunction) {
                successCallback = (KrollFunction) callback;
            }
        }

        if (args.containsKey("error")) {
            callback = args.get("error");
            if (callback instanceof KrollFunction) {
                errorCallback = (KrollFunction) callback;
            }
        }
    }

    @Kroll.method
    public void startRecording(HashMap args) throws Exception {
        if(isRecording()){
            sendErrorEvent("Another audio record is inprogress");
        } else {
            KrollDict options = new KrollDict(args);
            
            String filename      = (String) options.get("filename");
            String fileDirectory = (String) options.get("directoryName");
            String fileLocation  = (options.containsKey("fileLocation")) ? (String) options.get("fileLocation") : Storage_EXTERNAL;

            outputFile = getOutputFilename(filename, fileDirectory, fileLocation);
            if(outputFile == null || outputFile == ""){
                sendErrorEvent("External storage not available");
                return;
            }

            registerCallbacks(args);

            try {
                recordingThread = new Thread(new Runnable() {
                    public void run() {
                        recordAudio();
                    }
                });

                recordingThread.start();
            } catch (Exception e) {
                e.printStackTrace();
                sendErrorEvent(e.toString());
            }
        }
    }

    @Kroll.method
    public void stopRecording() throws Exception {
        if (recorder instanceof AudioRecord && recorder.getState() != AudioRecord.STATE_UNINITIALIZED) {
            try {
                recorder.stop();
                recorder.release();

                // Flush all remaining data into the file and close
                fileStream.flush();
                fileStream.close();

                // Remove recorder and filestream objects
                recorder        = null;
                fileStream      = null;
                recordingThread = null;

                // Convert the raw PCM file into a readable WAVE file and send success back to the app
                makeWaveFile();
                sendSuccessEvent(outputFile);
            } catch (IllegalStateException e) {
                try {
                    deleteRecordedFile();
                } catch (Exception subE) {
                    subE.printStackTrace();
                    sendErrorEvent(subE.toString());
                }

                e.printStackTrace();
                sendErrorEvent(e.toString());
            }
        }
    }

    /**
     * Returns the full file path
     * @param  filename The name of the file
     * @param  dirname  The directory of the file
     * @param  location The location (internal or external)
     * @return String Returns the output file name
     * @throws Exception If invalid storage type is supplied
     */
    private String getOutputFilename(String filename, String dirname, String location) throws Exception {
        dirname  = (dirname != null && dirname.length() > 0) ? dirname : AUDIO_RECORDER_FOLDER;
        filename = (String)filename + ".pcm";

        if (!checkStorageType(location)) {
            throw new Exception("Invalid storage type supplied");
        }

        if(location.equals(Storage_INTERNAL)){
            File audioDirectory = TiApplication.getAppRootOrCurrentActivity().getDir(dirname, Context.MODE_WORLD_READABLE);

            if (!audioDirectory.exists()) {
                audioDirectory.mkdirs();
            }
            return (audioDirectory.getAbsolutePath() + "/" + filename);
        } else {
            if(isExternalStorageWritable()){
                String packageName = TiApplication.getAppRootOrCurrentActivity().getPackageName();

                String sdCardPath = Environment.getExternalStorageDirectory().getPath();
                File audioDirectory = new File(sdCardPath, packageName+ "/" +dirname);

                if (!audioDirectory.exists()) {
                    audioDirectory.mkdirs();
                }

                return (audioDirectory.getAbsolutePath() + "/" + filename);
            } else {
                return null;
            }
        }
    }

    /**
     * Checks if storage type is supported
     * @param type The supplied type
     * @return Boolean
     */
    private Boolean checkStorageType(String type)
    {
        String[] types = {Storage_INTERNAL, Storage_EXTERNAL};
        return Arrays.asList(types).contains(type);
    }

    /**
     * Starts the recording
     */
    private void recordAudio() {
        int audioFormat       = AudioFormat.ENCODING_PCM_16BIT;
        int sampleRate        = 44100;
        int channelConfig     = AudioFormat.CHANNEL_IN_MONO;
        int bufferSizeInBytes = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat);
        byte[] buffer         = new byte[bufferSizeInBytes];

        try {
            File audioFile = new File(outputFile);
            fileStream     = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(audioFile)));

            if (!audioFile.exists()) {
                audioFile.createNewFile();
            }

            recorder = new AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    channelConfig,
                    audioFormat,
                    bufferSizeInBytes
            );

            recorder.startRecording();
            while (isRecording()) {
                int bufferReadResult = recorder.read(buffer, 0, bufferSizeInBytes);
                for (int i = 0; i < bufferReadResult; i++) {
                    fileStream.write(buffer[i]);
                }
            }
        } catch(Exception e) {
            e.printStackTrace();
            sendErrorEvent(e.toString());
        }
    }

    /**
     * Attempts to delete the recorded file
     * @return
     */
    private Boolean deleteRecordedFile() {
        File file = new File(outputFile);
        if (file.exists()) {
            try {
                return file.delete();
            } catch(Exception e) {
                e.printStackTrace();
                // Do nothing, it'll just return false
            }
        }
        return false;
    }

    /**
     * Converts a raw PCM file to wav
     */
    private void makeWaveFile()
    {
        File inputFile     = new File(outputFile);
        File wavOutputFile = new File(outputFile.replace(".pcm",".wav"));

        try {
            PcmAudioHelper.convertRawToWav(
                WavAudioFormat.mono16Bit(44100),
                inputFile,
                wavOutputFile
            );

            inputFile.delete();
        } catch(Exception e) {
            e.printStackTrace();
            sendErrorEvent(e.toString());
        }
    }

    //////////////////////////////////////
    //      EVENT HANDLERS              //
    //////////////////////////////////////

    /**
     * Sends success event and calls the success callback
     * @param filepath [description]
     */
    private void sendSuccessEvent(String filepath) {
        if (successCallback != null) {
            HashMap<String, String> event = new HashMap<String, String>();
            event.put("outputFile", outputFile);

            // Fire an event directly to the specified listener (callback)
            successCallback.call(getKrollObject(), event);
        }
    }

    /**
     * Sends an error event and calls the error callback
     * @param message Error message
     */
    private void sendErrorEvent(String message) {
        if (recorder instanceof AudioRecord) {
            if (recorder.getState() != AudioRecord.STATE_UNINITIALIZED) {
                recorder.release();
                recorder.stop();
            }

            // Reset the recorder
            recorder        = null;
            recordingThread = null;
            fileStream      = null;
        }

        deleteRecordedFile();
        if (errorCallback != null) {
            HashMap<String, String> event = new HashMap<String, String>();
            event.put("message", message);

            // Fire an event directly to the specified listener (callback)
            errorCallback.call(getKrollObject(), event);
        }
    }

}

