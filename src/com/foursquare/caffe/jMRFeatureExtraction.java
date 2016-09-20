package com.foursquare.caffe;

import caffe.*;
import com.google.protobuf.*;
import java.io.*;
import java.lang.*;
import java.util.*;

public class jMRFeatureExtraction {
  private native int startFeatureExtraction(String pretrainedBinaryProto, String featureExtractionProto);

  private native int runFeatureExtraction(String pretrainedBinaryProto, String featureExtractionProto);

  private native String processBatch(String batchFilePath);

  private native void stopFeatureExtraction();

  private Thread featureExtractionThread = null;
  private volatile int featureExtractionReturnCode = -1;

  public native String getInputPipePath();
  public native String getOutputPipePath();

  public final static int batchSize = 50;

  public jMRFeatureExtraction() throws Exception { }

  public List<Caffe.Datum> processBatch(Iterator<Caffe.Datum> batch) throws Exception {
    String batchFileName = currentToNNBatchFileNamePrefix + UUID.randomUUID();
    FileOutputStream batchStream = new FileOutputStream(batchFileName);
    int batchSize = 0;

    while (batch.hasNext()) {
      Caffe.Datum datum = batch.next();
      datum.writeDelimitedTo(batchStream);
      ++batchSize;
    }

    batchStream.close();

    int retry = 0;
    String resultFileName = processBatch(batchFileName);
    while ((batchFileName == null || batchFileName == "") && retry++ < 10) {
      Thread.sleep(1000);
      resultFileName = processBatch(batchFileName);
    }

    if (retry == 10) {
      throw new Exception("Failed to process batch");
    }

    FileInputStream resultFileStream = new FileInputStream(resultFileName);
    List<Caffe.Datum> results = new ArrayList<Caffe.Datum>();
    Caffe.Datum datum = Caffe.Datum.parseDelimitedFrom(resultFileStream);

    while (datum != null) {
      results.add(datum);

      datum = Caffe.Datum.parseDelimitedFrom(resultFileStream);
    }

    resultFileStream.close();

    new File(batchFileName).delete();
    new File(resultFileName).delete();

    if (results.size() != batchSize) {
      throw new Exception("Input size and output size do not match.");
    }

    return results;
  }

  public RandomAccessFile toNNFile = null;
  public RandomAccessFile fromNNFile = null;

  private int currentToNNBatchId = 0;
  private int currentToNNBatchIndex = -1;
  private String currentToNNBatchFileNamePrefix = "/dev/shm/foursquare_pcv1_in_";
  private FileOutputStream currentToNNBatchFileStream = null;

  private Boolean init = false;
  private void init() throws Exception {
    toNNFile = new RandomAccessFile(getInputPipePath(), "rw");
    fromNNFile = new RandomAccessFile(getOutputPipePath(), "rw");
    currentToNNBatchFileStream =
      new FileOutputStream(currentToNNBatchFileNamePrefix + currentToNNBatchId);

    init = true;
  }

  public void writeDatum(Caffe.Datum datum) throws Exception {
    if (!init) {
      init();
    }

    if (currentToNNBatchIndex == batchSize - 1) {
      currentToNNBatchFileStream.close();
      toNNFile.write((currentToNNBatchFileNamePrefix + currentToNNBatchId + '\n').getBytes());

      currentToNNBatchIndex = -1;
      ++currentToNNBatchId;

      // Throttling, never pile up more than 30 batches in share memory
      while (currentToNNBatchId - currentFromNNBatchId > 30) {
        Thread.sleep(100);
      }

      currentToNNBatchFileStream =
        new FileOutputStream(currentToNNBatchFileNamePrefix + currentToNNBatchId);
    }

    datum.writeDelimitedTo(currentToNNBatchFileStream);
    ++currentToNNBatchIndex;
  }

  private String fileName = null;
  private FileInputStream currentFromNNBatchFileStream = null;
  private int currentFromNNBatchId = -1;

  public Caffe.Datum readDatum() throws Exception {
    if (!init) {
      init();
    }

    if (currentFromNNBatchFileStream == null) {
      if (fileName != null) {
        new File(fileName).delete();
      }

      fileName = fromNNFile.readLine();
      
      currentFromNNBatchFileStream = new FileInputStream(fileName);

      String[] parts = fileName.split("_");
      currentFromNNBatchId = Integer.parseInt(parts[parts.length - 1]);
    }

    Caffe.Datum datum = Caffe.Datum.parseDelimitedFrom(currentFromNNBatchFileStream);

    if (datum == null) {
      currentFromNNBatchFileStream.close();
      currentFromNNBatchFileStream = null;

      return readDatum();
    }

    return datum;
  }

  public void start(String pretrainedBinaryProto, String featureExtractionProto) {
    startFeatureExtraction(pretrainedBinaryProto, featureExtractionProto);
  }

  public void startAsync(String pretrainedBinaryProto, String featureExtractionProto) {
    featureExtractionThread = new Thread(new Runnable() {
      public void run() {
        try {
          featureExtractionReturnCode = runFeatureExtraction(pretrainedBinaryProto, featureExtractionProto);
        } catch(Exception e) {
          featureExtractionReturnCode = 1;
        }
      }
    });

    featureExtractionThread.start();
  }

  public int stop() throws Exception {
    stopFeatureExtraction();
    Thread.sleep(1000);

    try {
      featureExtractionThread.join(5000);
    } catch(Exception e) {
      return -1;
    } finally { 
      System.err.println("Closing pipes");

      toNNFile.close();
      fromNNFile.close();
    }

    return featureExtractionReturnCode;
  }

  static {
    try {
      System.loadLibrary("caffe");
      System.loadLibrary("caffe_jni");
    } catch (Exception e) { }
  }
}
