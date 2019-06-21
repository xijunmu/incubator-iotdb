/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.iotdb.db.query.reader.sequence;

import java.io.IOException;
import org.apache.iotdb.db.engine.filenodeV2.TsFileResourceV2;
import org.apache.iotdb.db.query.control.FileReaderManager;
import org.apache.iotdb.db.query.reader.mem.MemChunkReaderByTimestamp;
import org.apache.iotdb.db.query.reader.merge.EngineReaderByTimeStamp;
import org.apache.iotdb.tsfile.read.TsFileSequenceReader;
import org.apache.iotdb.tsfile.read.common.Path;
import org.apache.iotdb.tsfile.read.controller.ChunkLoader;
import org.apache.iotdb.tsfile.read.controller.ChunkLoaderImpl;
import org.apache.iotdb.tsfile.read.reader.series.SeriesReaderByTimestamp;

public class UnSealedTsFilesReaderByTimestampV2 implements EngineReaderByTimeStamp {

  protected Path seriesPath;
  private SeriesReaderByTimestamp unSealedReader;
  private EngineReaderByTimeStamp memSeriesReader;
  private boolean unSealedReaderEnded;

  /**
   * Construct funtion for UnSealedTsFileReader.
   *
   * @param tsFileResource -unclosed tsfile resource
   */
  public UnSealedTsFilesReaderByTimestampV2(TsFileResourceV2 tsFileResource) throws IOException {
    TsFileSequenceReader unClosedTsFileReader = FileReaderManager.getInstance()
        .get(tsFileResource.getFile().getPath(), false);
    ChunkLoader chunkLoader = new ChunkLoaderImpl(unClosedTsFileReader);
    unSealedReader = new SeriesReaderByTimestamp(chunkLoader,
        tsFileResource.getChunkMetaDatas());

    memSeriesReader = new MemChunkReaderByTimestamp(tsFileResource.getReadOnlyMemChunk());
    unSealedReaderEnded = false;
  }

  @Override
  public Object getValueInTimestamp(long timestamp) throws IOException {
    Object value = null;
    if (!unSealedReaderEnded) {
      value = unSealedReader.getValueInTimestamp(timestamp);
    }
    if (value != null || unSealedReader.hasNext()) {
      return value;
    } else {
      unSealedReaderEnded = true;
    }
    return memSeriesReader.getValueInTimestamp(timestamp);
  }

  @Override
  public boolean hasNext() throws IOException {
    if (unSealedReaderEnded) {
      return memSeriesReader.hasNext();
    }
    return (unSealedReader.hasNext() || memSeriesReader.hasNext());
  }

}
