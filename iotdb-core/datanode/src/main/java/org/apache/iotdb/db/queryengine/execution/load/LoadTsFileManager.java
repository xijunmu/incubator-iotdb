/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.iotdb.db.queryengine.execution.load;

import org.apache.iotdb.common.rpc.thrift.TTimePartitionSlot;
import org.apache.iotdb.commons.concurrent.IoTDBThreadPoolFactory;
import org.apache.iotdb.commons.conf.IoTDBConstant;
import org.apache.iotdb.commons.file.SystemFileFactory;
import org.apache.iotdb.commons.service.metric.MetricService;
import org.apache.iotdb.commons.service.metric.enums.Metric;
import org.apache.iotdb.commons.service.metric.enums.Tag;
import org.apache.iotdb.commons.utils.FileUtils;
import org.apache.iotdb.db.conf.IoTDBConfig;
import org.apache.iotdb.db.conf.IoTDBDescriptor;
import org.apache.iotdb.db.exception.LoadFileException;
import org.apache.iotdb.db.queryengine.plan.planner.plan.node.load.LoadTsFilePieceNode;
import org.apache.iotdb.db.queryengine.plan.scheduler.load.LoadTsFileScheduler;
import org.apache.iotdb.db.queryengine.plan.scheduler.load.LoadTsFileScheduler.LoadCommand;
import org.apache.iotdb.db.storageengine.dataregion.DataRegion;
import org.apache.iotdb.db.storageengine.dataregion.modification.ModificationFile;
import org.apache.iotdb.db.storageengine.dataregion.tsfile.TsFileResource;
import org.apache.iotdb.db.storageengine.dataregion.utils.TsFileResourceUtils;
import org.apache.iotdb.metrics.utils.MetricLevel;
import org.apache.iotdb.tsfile.common.constant.TsFileConstant;
import org.apache.iotdb.tsfile.write.writer.TsFileIOWriter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * {@link LoadTsFileManager} is used for dealing with {@link LoadTsFilePieceNode} and {@link
 * LoadCommand}. This class turn the content of a piece of loading TsFile into a new TsFile. When
 * DataNode finish transfer pieces, this class will flush all TsFile and laod them into IoTDB, or
 * delete all.
 */
public class LoadTsFileManager {

  private static final Logger LOGGER = LoggerFactory.getLogger(LoadTsFileManager.class);

  private static final IoTDBConfig CONFIG = IoTDBDescriptor.getInstance().getConfig();

  private static final String MESSAGE_WRITER_MANAGER_HAS_BEEN_CLOSED =
      "%s TsFileWriterManager has been closed.";
  private static final String MESSAGE_DELETE_FAIL = "failed to delete {}.";

  private final File loadDir;

  private final Map<String, TsFileWriterManager> uuid2WriterManager;

  private final ScheduledExecutorService cleanupExecutors;
  private final Map<String, ScheduledFuture<?>> uuid2Future;

  public LoadTsFileManager() {
    this.loadDir = SystemFileFactory.INSTANCE.getFile(CONFIG.getLoadTsFileDir());
    this.uuid2WriterManager = new ConcurrentHashMap<>();
    this.cleanupExecutors =
        IoTDBThreadPoolFactory.newScheduledThreadPool(1, LoadTsFileManager.class.getName());
    this.uuid2Future = new ConcurrentHashMap<>();

    recover();
  }

  private void recover() {
    if (!loadDir.exists()) {
      return;
    }

    final File[] files = loadDir.listFiles();
    if (files == null) {
      return;
    }
    for (final File taskDir : files) {
      String uuid = taskDir.getName();
      TsFileWriterManager writerManager = new TsFileWriterManager(taskDir);

      uuid2WriterManager.put(uuid, writerManager);
      writerManager.close();
      uuid2Future.put(
          uuid,
          cleanupExecutors.schedule(
              () -> forceCloseWriterManager(uuid),
              LoadTsFileScheduler.LOAD_TASK_MAX_TIME_IN_SECOND,
              TimeUnit.SECONDS));
    }
  }

  public void writeToDataRegion(DataRegion dataRegion, LoadTsFilePieceNode pieceNode, String uuid)
      throws IOException {
    if (!uuid2WriterManager.containsKey(uuid)) {
      uuid2Future.put(
          uuid,
          cleanupExecutors.schedule(
              () -> forceCloseWriterManager(uuid),
              LoadTsFileScheduler.LOAD_TASK_MAX_TIME_IN_SECOND,
              TimeUnit.SECONDS));
    }
    TsFileWriterManager writerManager =
        uuid2WriterManager.computeIfAbsent(
            uuid, o -> new TsFileWriterManager(SystemFileFactory.INSTANCE.getFile(loadDir, uuid)));
    for (TsFileData tsFileData : pieceNode.getAllTsFileData()) {
      if (!tsFileData.isModification()) {
        ChunkData chunkData = (ChunkData) tsFileData;
        writerManager.write(
            new DataPartitionInfo(dataRegion, chunkData.getTimePartitionSlot()), chunkData);
      } else {
        writerManager.writeDeletion(dataRegion, (DeletionData) tsFileData);
      }
    }
  }

  public boolean loadAll(String uuid, boolean isGeneratedByPipe)
      throws IOException, LoadFileException {
    if (!uuid2WriterManager.containsKey(uuid)) {
      return false;
    }
    uuid2WriterManager.get(uuid).loadAll(isGeneratedByPipe);
    clean(uuid);
    return true;
  }

  public boolean deleteAll(String uuid) {
    if (!uuid2WriterManager.containsKey(uuid)) {
      return false;
    }
    clean(uuid);
    return true;
  }

  private void clean(String uuid) {
    uuid2WriterManager.get(uuid).close();
    uuid2WriterManager.remove(uuid);
    uuid2Future.get(uuid).cancel(true);
    uuid2Future.remove(uuid);

    final Path loadDirPath = loadDir.toPath();
    if (!Files.exists(loadDirPath)) {
      return;
    }
    try {
      Files.delete(loadDirPath);
      LOGGER.info("Load dir {} was deleted.", loadDirPath);
    } catch (DirectoryNotEmptyException e) {
      LOGGER.info("Load dir {} is not empty, skip deleting.", loadDirPath);
    } catch (IOException e) {
      LOGGER.warn(MESSAGE_DELETE_FAIL, loadDirPath, e);
    }
  }

  private void forceCloseWriterManager(String uuid) {
    uuid2WriterManager.get(uuid).close();
    uuid2WriterManager.remove(uuid);
    uuid2Future.remove(uuid);

    final Path loadDirPath = loadDir.toPath();
    if (!Files.exists(loadDirPath)) {
      return;
    }
    try {
      Files.delete(loadDirPath);
      LOGGER.info("Load dir {} was deleted.", loadDirPath);
    } catch (DirectoryNotEmptyException e) {
      LOGGER.info("Load dir {} is not empty, skip deleting.", loadDirPath);
    } catch (IOException e) {
      LOGGER.warn(MESSAGE_DELETE_FAIL, loadDirPath, e);
    }
  }

  private static class TsFileWriterManager {
    private final File taskDir;
    private Map<DataPartitionInfo, TsFileIOWriter> dataPartition2Writer;
    private Map<DataPartitionInfo, String> dataPartition2LastDevice;
    private Map<DataPartitionInfo, ModificationFile> dataPartition2ModificationFile;
    private boolean isClosed;

    private TsFileWriterManager(File taskDir) {
      this.taskDir = taskDir;
      this.dataPartition2Writer = new HashMap<>();
      this.dataPartition2LastDevice = new HashMap<>();
      this.dataPartition2ModificationFile = new HashMap<>();
      this.isClosed = false;

      clearDir(taskDir);
    }

    private void clearDir(File dir) {
      if (dir.exists()) {
        FileUtils.deleteDirectory(dir);
      }
      if (dir.mkdirs()) {
        LOGGER.info("Load TsFile dir {} is created.", dir.getPath());
      }
    }

    @SuppressWarnings("squid:S3824")
    private void write(DataPartitionInfo partitionInfo, ChunkData chunkData) throws IOException {
      if (isClosed) {
        throw new IOException(String.format(MESSAGE_WRITER_MANAGER_HAS_BEEN_CLOSED, taskDir));
      }
      if (!dataPartition2Writer.containsKey(partitionInfo)) {
        File newTsFile =
            SystemFileFactory.INSTANCE.getFile(
                taskDir, partitionInfo.toString() + TsFileConstant.TSFILE_SUFFIX);
        if (!newTsFile.createNewFile()) {
          LOGGER.error("Can not create TsFile {} for writing.", newTsFile.getPath());
          return;
        }

        dataPartition2Writer.put(partitionInfo, new TsFileIOWriter(newTsFile));
      }
      TsFileIOWriter writer = dataPartition2Writer.get(partitionInfo);
      if (!chunkData.getDevice().equals(dataPartition2LastDevice.getOrDefault(partitionInfo, ""))) {
        if (dataPartition2LastDevice.containsKey(partitionInfo)) {
          writer.endChunkGroup();
        }
        writer.startChunkGroup(chunkData.getDevice());
        dataPartition2LastDevice.put(partitionInfo, chunkData.getDevice());
      }
      chunkData.writeToFileWriter(writer);
    }

    private void writeDeletion(DataRegion dataRegion, DeletionData deletionData)
        throws IOException {
      if (isClosed) {
        throw new IOException(String.format(MESSAGE_WRITER_MANAGER_HAS_BEEN_CLOSED, taskDir));
      }
      for (Map.Entry<DataPartitionInfo, TsFileIOWriter> entry : dataPartition2Writer.entrySet()) {
        final DataPartitionInfo partitionInfo = entry.getKey();
        if (partitionInfo.getDataRegion().equals(dataRegion)) {
          final TsFileIOWriter writer = entry.getValue();
          if (!dataPartition2ModificationFile.containsKey(partitionInfo)) {
            File newModificationFile =
                SystemFileFactory.INSTANCE.getFile(
                    writer.getFile().getAbsolutePath() + ModificationFile.FILE_SUFFIX);
            if (!newModificationFile.createNewFile()) {
              LOGGER.error(
                  "Can not create ModificationFile {} for writing.", newModificationFile.getPath());
              return;
            }

            dataPartition2ModificationFile.put(
                partitionInfo, new ModificationFile(newModificationFile.getAbsolutePath()));
          }
          ModificationFile modificationFile = dataPartition2ModificationFile.get(partitionInfo);
          writer.flush();
          deletionData.writeToModificationFile(modificationFile, writer.getFile().length());
        }
      }
    }

    private void loadAll(boolean isGeneratedByPipe) throws IOException, LoadFileException {
      if (isClosed) {
        throw new IOException(String.format(MESSAGE_WRITER_MANAGER_HAS_BEEN_CLOSED, taskDir));
      }
      for (Map.Entry<DataPartitionInfo, ModificationFile> entry :
          dataPartition2ModificationFile.entrySet()) {
        entry.getValue().close();
      }
      for (Map.Entry<DataPartitionInfo, TsFileIOWriter> entry : dataPartition2Writer.entrySet()) {
        TsFileIOWriter writer = entry.getValue();
        if (writer.isWritingChunkGroup()) {
          writer.endChunkGroup();
        }
        writer.endFile();

        DataRegion dataRegion = entry.getKey().getDataRegion();
        dataRegion.loadNewTsFile(generateResource(writer), true, isGeneratedByPipe);

        MetricService.getInstance()
            .count(
                getTsFileWritePointCount(writer),
                Metric.QUANTITY.toString(),
                MetricLevel.CORE,
                Tag.NAME.toString(),
                Metric.POINTS_IN.toString(),
                Tag.DATABASE.toString(),
                dataRegion.getDatabaseName(),
                Tag.REGION.toString(),
                dataRegion.getDataRegionId());
      }
    }

    private TsFileResource generateResource(TsFileIOWriter writer) throws IOException {
      TsFileResource tsFileResource = TsFileResourceUtils.generateTsFileResource(writer);
      tsFileResource.serialize();
      return tsFileResource;
    }

    private long getTsFileWritePointCount(TsFileIOWriter writer) {
      return writer.getChunkGroupMetadataList().stream()
          .flatMap(chunkGroupMetadata -> chunkGroupMetadata.getChunkMetadataList().stream())
          .mapToLong(chunkMetadata -> chunkMetadata.getStatistics().getCount())
          .sum();
    }

    private void close() {
      if (isClosed) {
        return;
      }
      if (dataPartition2Writer != null) {
        for (Map.Entry<DataPartitionInfo, TsFileIOWriter> entry : dataPartition2Writer.entrySet()) {
          try {
            final TsFileIOWriter writer = entry.getValue();
            if (writer.canWrite()) {
              writer.close();
            }
            final Path writerPath = writer.getFile().toPath();
            if (Files.exists(writerPath)) {
              Files.delete(writerPath);
            }
          } catch (IOException e) {
            LOGGER.warn("Close TsFileIOWriter {} error.", entry.getValue().getFile().getPath(), e);
          }
        }
      }
      if (dataPartition2ModificationFile != null) {
        for (Map.Entry<DataPartitionInfo, ModificationFile> entry :
            dataPartition2ModificationFile.entrySet()) {
          try {
            final ModificationFile modificationFile = entry.getValue();
            modificationFile.close();
            final Path modificationFilePath = new File(modificationFile.getFilePath()).toPath();
            if (Files.exists(modificationFilePath)) {
              Files.delete(modificationFilePath);
            }
          } catch (IOException e) {
            LOGGER.warn("Close ModificationFile {} error.", entry.getValue().getFilePath(), e);
          }
        }
      }
      try {
        Files.delete(taskDir.toPath());
      } catch (DirectoryNotEmptyException e) {
        LOGGER.info("Task dir {} is not empty, skip deleting.", taskDir.getPath());
      } catch (IOException e) {
        LOGGER.warn(MESSAGE_DELETE_FAIL, taskDir.getPath(), e);
      }
      dataPartition2Writer = null;
      dataPartition2LastDevice = null;
      dataPartition2ModificationFile = null;
      isClosed = true;
    }
  }

  private static class DataPartitionInfo {

    private final DataRegion dataRegion;
    private final TTimePartitionSlot timePartitionSlot;

    private DataPartitionInfo(DataRegion dataRegion, TTimePartitionSlot timePartitionSlot) {
      this.dataRegion = dataRegion;
      this.timePartitionSlot = timePartitionSlot;
    }

    public DataRegion getDataRegion() {
      return dataRegion;
    }

    public TTimePartitionSlot getTimePartitionSlot() {
      return timePartitionSlot;
    }

    @Override
    public String toString() {
      return String.join(
          IoTDBConstant.FILE_NAME_SEPARATOR,
          dataRegion.getDatabaseName(),
          dataRegion.getDataRegionId(),
          Long.toString(timePartitionSlot.getStartTime()));
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      DataPartitionInfo that = (DataPartitionInfo) o;
      return Objects.equals(dataRegion, that.dataRegion)
          && timePartitionSlot.getStartTime() == that.timePartitionSlot.getStartTime();
    }

    @Override
    public int hashCode() {
      return Objects.hash(dataRegion, timePartitionSlot.getStartTime());
    }
  }
}
