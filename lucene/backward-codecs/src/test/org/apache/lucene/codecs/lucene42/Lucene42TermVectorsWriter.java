package org.apache.lucene.codecs.lucene42;

/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import static org.apache.lucene.codecs.lucene42.Lucene42TermVectorsReader.BLOCK_SIZE;
import static org.apache.lucene.codecs.lucene42.Lucene42TermVectorsReader.CODEC_SFX_DAT;
import static org.apache.lucene.codecs.lucene42.Lucene42TermVectorsReader.CODEC_SFX_IDX;
import static org.apache.lucene.codecs.lucene42.Lucene42TermVectorsReader.FLAGS_BITS;
import static org.apache.lucene.codecs.lucene42.Lucene42TermVectorsReader.OFFSETS;
import static org.apache.lucene.codecs.lucene42.Lucene42TermVectorsReader.PAYLOADS;
import static org.apache.lucene.codecs.lucene42.Lucene42TermVectorsReader.POSITIONS;
import static org.apache.lucene.codecs.lucene42.Lucene42TermVectorsReader.VECTORS_EXTENSION;
import static org.apache.lucene.codecs.lucene42.Lucene42TermVectorsReader.VECTORS_INDEX_EXTENSION;
import static org.apache.lucene.codecs.lucene42.Lucene42TermVectorsReader.VERSION_CURRENT;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.Iterator;
import java.util.SortedSet;
import java.util.TreeSet;

import org.apache.lucene.codecs.CodecUtil;
import org.apache.lucene.codecs.TermVectorsWriter;
import org.apache.lucene.codecs.compressing.CompressionMode;
import org.apache.lucene.codecs.compressing.Compressor;
import org.apache.lucene.codecs.lucene41.Lucene41StoredFieldsIndexWriter;
import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.index.FieldInfos;
import org.apache.lucene.index.IndexFileNames;
import org.apache.lucene.index.SegmentInfo;
import org.apache.lucene.store.DataInput;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.IOContext;
import org.apache.lucene.store.IndexOutput;
import org.apache.lucene.util.ArrayUtil;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.codecs.compressing.GrowableByteArrayDataOutput;
import org.apache.lucene.util.IOUtils;
import org.apache.lucene.util.StringHelper;
import org.apache.lucene.util.packed.BlockPackedWriter;
import org.apache.lucene.util.packed.PackedInts;

/**
 * Writer for 4.2 term vectors format for testing
 * @deprecated for test purposes only
 */
@Deprecated
final class Lucene42TermVectorsWriter extends TermVectorsWriter {

  // hard limit on the maximum number of documents per chunk
  static final int MAX_DOCUMENTS_PER_CHUNK = 128;

  private final Directory directory;
  private final String segment;
  private final String segmentSuffix;
  private Lucene41StoredFieldsIndexWriter indexWriter;
  private IndexOutput vectorsStream;

  private final Compressor compressor;
  private final int chunkSize;

  /** a pending doc */
  private class DocData {
    final int numFields;
    final Deque<FieldData> fields;
    final int posStart, offStart, payStart;
    DocData(int numFields, int posStart, int offStart, int payStart) {
      this.numFields = numFields;
      this.fields = new ArrayDeque<>(numFields);
      this.posStart = posStart;
      this.offStart = offStart;
      this.payStart = payStart;
    }
    FieldData addField(int fieldNum, int numTerms, boolean positions, boolean offsets, boolean payloads) {
      final FieldData field;
      if (fields.isEmpty()) {
        field = new FieldData(fieldNum, numTerms, positions, offsets, payloads, posStart, offStart, payStart);
      } else {
        final FieldData last = fields.getLast();
        final int posStart = last.posStart + (last.hasPositions ? last.totalPositions : 0);
        final int offStart = last.offStart + (last.hasOffsets ? last.totalPositions : 0);
        final int payStart = last.payStart + (last.hasPayloads ? last.totalPositions : 0);
        field = new FieldData(fieldNum, numTerms, positions, offsets, payloads, posStart, offStart, payStart);
      }
      fields.add(field);
      return field;
    }
  }

  private DocData addDocData(int numVectorFields) {
    FieldData last = null;
    for (Iterator<DocData> it = pendingDocs.descendingIterator(); it.hasNext(); ) {
      final DocData doc = it.next();
      if (!doc.fields.isEmpty()) {
        last = doc.fields.getLast();
        break;
      }
    }
    final DocData doc;
    if (last == null) {
      doc = new DocData(numVectorFields, 0, 0, 0);
    } else {
      final int posStart = last.posStart + (last.hasPositions ? last.totalPositions : 0);
      final int offStart = last.offStart + (last.hasOffsets ? last.totalPositions : 0);
      final int payStart = last.payStart + (last.hasPayloads ? last.totalPositions : 0);
      doc = new DocData(numVectorFields, posStart, offStart, payStart);
    }
    pendingDocs.add(doc);
    return doc;
  }

  /** a pending field */
  private class FieldData {
    final boolean hasPositions, hasOffsets, hasPayloads;
    final int fieldNum, flags, numTerms;
    final int[] freqs, prefixLengths, suffixLengths;
    final int posStart, offStart, payStart;
    int totalPositions;
    int ord;
    FieldData(int fieldNum, int numTerms, boolean positions, boolean offsets, boolean payloads,
        int posStart, int offStart, int payStart) {
      this.fieldNum = fieldNum;
      this.numTerms = numTerms;
      this.hasPositions = positions;
      this.hasOffsets = offsets;
      this.hasPayloads = payloads;
      this.flags = (positions ? POSITIONS : 0) | (offsets ? OFFSETS : 0) | (payloads ? PAYLOADS : 0);
      this.freqs = new int[numTerms];
      this.prefixLengths = new int[numTerms];
      this.suffixLengths = new int[numTerms];
      this.posStart = posStart;
      this.offStart = offStart;
      this.payStart = payStart;
      totalPositions = 0;
      ord = 0;
    }
    void addTerm(int freq, int prefixLength, int suffixLength) {
      freqs[ord] = freq;
      prefixLengths[ord] = prefixLength;
      suffixLengths[ord] = suffixLength;
      ++ord;
    }
    void addPosition(int position, int startOffset, int length, int payloadLength) {
      if (hasPositions) {
        if (posStart + totalPositions == positionsBuf.length) {
          positionsBuf = ArrayUtil.grow(positionsBuf);
        }
        positionsBuf[posStart + totalPositions] = position;
      }
      if (hasOffsets) {
        if (offStart + totalPositions == startOffsetsBuf.length) {
          final int newLength = ArrayUtil.oversize(offStart + totalPositions, 4);
          startOffsetsBuf = Arrays.copyOf(startOffsetsBuf, newLength);
          lengthsBuf = Arrays.copyOf(lengthsBuf, newLength);
        }
        startOffsetsBuf[offStart + totalPositions] = startOffset;
        lengthsBuf[offStart + totalPositions] = length;
      }
      if (hasPayloads) {
        if (payStart + totalPositions == payloadLengthsBuf.length) {
          payloadLengthsBuf = ArrayUtil.grow(payloadLengthsBuf);
        }
        payloadLengthsBuf[payStart + totalPositions] = payloadLength;
      }
      ++totalPositions;
    }
  }

  private int numDocs; // total number of docs seen
  private final Deque<DocData> pendingDocs; // pending docs
  private DocData curDoc; // current document
  private FieldData curField; // current field
  private final BytesRef lastTerm;
  private int[] positionsBuf, startOffsetsBuf, lengthsBuf, payloadLengthsBuf;
  private final GrowableByteArrayDataOutput termSuffixes; // buffered term suffixes
  private final GrowableByteArrayDataOutput payloadBytes; // buffered term payloads
  private final BlockPackedWriter writer;

  /** Sole constructor. */
  public Lucene42TermVectorsWriter(Directory directory, SegmentInfo si, String segmentSuffix, IOContext context,
      String formatName, CompressionMode compressionMode, int chunkSize) throws IOException {
    assert directory != null;
    this.directory = directory;
    this.segment = si.name;
    this.segmentSuffix = segmentSuffix;
    this.compressor = compressionMode.newCompressor();
    this.chunkSize = chunkSize;

    numDocs = 0;
    pendingDocs = new ArrayDeque<>();
    termSuffixes = new GrowableByteArrayDataOutput(ArrayUtil.oversize(chunkSize, 1));
    payloadBytes = new GrowableByteArrayDataOutput(ArrayUtil.oversize(1, 1));
    lastTerm = new BytesRef(ArrayUtil.oversize(30, 1));

    boolean success = false;
    IndexOutput indexStream = directory.createOutput(IndexFileNames.segmentFileName(segment, segmentSuffix, VECTORS_INDEX_EXTENSION), 
                                                                     context);
    try {
      vectorsStream = directory.createOutput(IndexFileNames.segmentFileName(segment, segmentSuffix, VECTORS_EXTENSION),
                                                     context);

      final String codecNameIdx = formatName + CODEC_SFX_IDX;
      final String codecNameDat = formatName + CODEC_SFX_DAT;
      CodecUtil.writeHeader(indexStream, codecNameIdx, VERSION_CURRENT);
      CodecUtil.writeHeader(vectorsStream, codecNameDat, VERSION_CURRENT);
      assert CodecUtil.headerLength(codecNameDat) == vectorsStream.getFilePointer();
      assert CodecUtil.headerLength(codecNameIdx) == indexStream.getFilePointer();

      indexWriter = new Lucene41StoredFieldsIndexWriter(indexStream);
      indexStream = null;

      vectorsStream.writeVInt(PackedInts.VERSION_CURRENT);
      vectorsStream.writeVInt(chunkSize);
      writer = new BlockPackedWriter(vectorsStream, BLOCK_SIZE);

      positionsBuf = new int[1024];
      startOffsetsBuf = new int[1024];
      lengthsBuf = new int[1024];
      payloadLengthsBuf = new int[1024];

      success = true;
    } finally {
      if (!success) {
        IOUtils.closeWhileHandlingException(vectorsStream, indexStream, indexWriter);
      }
    }
  }

  @Override
  public void close() throws IOException {
    try {
      IOUtils.close(vectorsStream, indexWriter);
    } finally {
      vectorsStream = null;
      indexWriter = null;
    }
  }

  @Override
  public void startDocument(int numVectorFields) throws IOException {
    curDoc = addDocData(numVectorFields);
  }

  @Override
  public void finishDocument() throws IOException {
    // append the payload bytes of the doc after its terms
    termSuffixes.writeBytes(payloadBytes.bytes, payloadBytes.length);
    payloadBytes.length = 0;
    ++numDocs;
    if (triggerFlush()) {
      flush();
    }
    curDoc = null;
  }

  @Override
  public void startField(FieldInfo info, int numTerms, boolean positions,
      boolean offsets, boolean payloads) throws IOException {
    curField = curDoc.addField(info.number, numTerms, positions, offsets, payloads);
    lastTerm.length = 0;
  }

  @Override
  public void finishField() throws IOException {
    curField = null;
  }

  @Override
  public void startTerm(BytesRef term, int freq) throws IOException {
    assert freq >= 1;
    final int prefix = StringHelper.bytesDifference(lastTerm, term);
    curField.addTerm(freq, prefix, term.length - prefix);
    termSuffixes.writeBytes(term.bytes, term.offset + prefix, term.length - prefix);
    // copy last term
    if (lastTerm.bytes.length < term.length) {
      lastTerm.bytes = new byte[ArrayUtil.oversize(term.length, 1)];
    }
    lastTerm.offset = 0;
    lastTerm.length = term.length;
    System.arraycopy(term.bytes, term.offset, lastTerm.bytes, 0, term.length);
  }

  @Override
  public void addPosition(int position, int startOffset, int endOffset,
      BytesRef payload) throws IOException {
    assert curField.flags != 0;
    curField.addPosition(position, startOffset, endOffset - startOffset, payload == null ? 0 : payload.length);
    if (curField.hasPayloads && payload != null) {
      payloadBytes.writeBytes(payload.bytes, payload.offset, payload.length);
    }
  }

  private boolean triggerFlush() {
    return termSuffixes.length >= chunkSize
        || pendingDocs.size() >= MAX_DOCUMENTS_PER_CHUNK;
  }

  private void flush() throws IOException {
    final int chunkDocs = pendingDocs.size();
    assert chunkDocs > 0 : chunkDocs;

    // write the index file
    indexWriter.writeIndex(chunkDocs, vectorsStream.getFilePointer());

    final int docBase = numDocs - chunkDocs;
    vectorsStream.writeVInt(docBase);
    vectorsStream.writeVInt(chunkDocs);

    // total number of fields of the chunk
    final int totalFields = flushNumFields(chunkDocs);

    if (totalFields > 0) {
      // unique field numbers (sorted)
      final int[] fieldNums = flushFieldNums();
      // offsets in the array of unique field numbers
      flushFields(totalFields, fieldNums);
      // flags (does the field have positions, offsets, payloads?)
      flushFlags(totalFields, fieldNums);
      // number of terms of each field
      flushNumTerms(totalFields);
      // prefix and suffix lengths for each field
      flushTermLengths();
      // term freqs - 1 (because termFreq is always >=1) for each term
      flushTermFreqs();
      // positions for all terms, when enabled
      flushPositions();
      // offsets for all terms, when enabled
      flushOffsets(fieldNums);
      // payload lengths for all terms, when enabled
      flushPayloadLengths();

      // compress terms and payloads and write them to the output
      compressor.compress(termSuffixes.bytes, 0, termSuffixes.length, vectorsStream);
    }

    // reset
    pendingDocs.clear();
    curDoc = null;
    curField = null;
    termSuffixes.length = 0;
  }

  private int flushNumFields(int chunkDocs) throws IOException {
    if (chunkDocs == 1) {
      final int numFields = pendingDocs.getFirst().numFields;
      vectorsStream.writeVInt(numFields);
      return numFields;
    } else {
      writer.reset(vectorsStream);
      int totalFields = 0;
      for (DocData dd : pendingDocs) {
        writer.add(dd.numFields);
        totalFields += dd.numFields;
      }
      writer.finish();
      return totalFields;
    }
  }

  /** Returns a sorted array containing unique field numbers */
  private int[] flushFieldNums() throws IOException {
    SortedSet<Integer> fieldNums = new TreeSet<>();
    for (DocData dd : pendingDocs) {
      for (FieldData fd : dd.fields) {
        fieldNums.add(fd.fieldNum);
      }
    }

    final int numDistinctFields = fieldNums.size();
    assert numDistinctFields > 0;
    final int bitsRequired = PackedInts.bitsRequired(fieldNums.last());
    final int token = (Math.min(numDistinctFields - 1, 0x07) << 5) | bitsRequired;
    vectorsStream.writeByte((byte) token);
    if (numDistinctFields - 1 >= 0x07) {
      vectorsStream.writeVInt(numDistinctFields - 1 - 0x07);
    }
    final PackedInts.Writer writer = PackedInts.getWriterNoHeader(vectorsStream, PackedInts.Format.PACKED, fieldNums.size(), bitsRequired, 1);
    for (Integer fieldNum : fieldNums) {
      writer.add(fieldNum);
    }
    writer.finish();

    int[] fns = new int[fieldNums.size()];
    int i = 0;
    for (Integer key : fieldNums) {
      fns[i++] = key;
    }
    return fns;
  }

  private void flushFields(int totalFields, int[] fieldNums) throws IOException {
    final PackedInts.Writer writer = PackedInts.getWriterNoHeader(vectorsStream, PackedInts.Format.PACKED, totalFields, PackedInts.bitsRequired(fieldNums.length - 1), 1);
    for (DocData dd : pendingDocs) {
      for (FieldData fd : dd.fields) {
        final int fieldNumIndex = Arrays.binarySearch(fieldNums, fd.fieldNum);
        assert fieldNumIndex >= 0;
        writer.add(fieldNumIndex);
      }
    }
    writer.finish();
  }

  private void flushFlags(int totalFields, int[] fieldNums) throws IOException {
    // check if fields always have the same flags
    boolean nonChangingFlags = true;
    int[] fieldFlags = new int[fieldNums.length];
    Arrays.fill(fieldFlags, -1);
    outer:
    for (DocData dd : pendingDocs) {
      for (FieldData fd : dd.fields) {
        final int fieldNumOff = Arrays.binarySearch(fieldNums, fd.fieldNum);
        assert fieldNumOff >= 0;
        if (fieldFlags[fieldNumOff] == -1) {
          fieldFlags[fieldNumOff] = fd.flags;
        } else if (fieldFlags[fieldNumOff] != fd.flags) {
          nonChangingFlags = false;
          break outer;
        }
      }
    }

    if (nonChangingFlags) {
      // write one flag per field num
      vectorsStream.writeVInt(0);
      final PackedInts.Writer writer = PackedInts.getWriterNoHeader(vectorsStream, PackedInts.Format.PACKED, fieldFlags.length, FLAGS_BITS, 1);
      for (int flags : fieldFlags) {
        assert flags >= 0;
        writer.add(flags);
      }
      assert writer.ord() == fieldFlags.length - 1;
      writer.finish();
    } else {
      // write one flag for every field instance
      vectorsStream.writeVInt(1);
      final PackedInts.Writer writer = PackedInts.getWriterNoHeader(vectorsStream, PackedInts.Format.PACKED, totalFields, FLAGS_BITS, 1);
      for (DocData dd : pendingDocs) {
        for (FieldData fd : dd.fields) {
          writer.add(fd.flags);
        }
      }
      assert writer.ord() == totalFields - 1;
      writer.finish();
    }
  }

  private void flushNumTerms(int totalFields) throws IOException {
    int maxNumTerms = 0;
    for (DocData dd : pendingDocs) {
      for (FieldData fd : dd.fields) {
        maxNumTerms |= fd.numTerms;
      }
    }
    final int bitsRequired = PackedInts.bitsRequired(maxNumTerms);
    vectorsStream.writeVInt(bitsRequired);
    final PackedInts.Writer writer = PackedInts.getWriterNoHeader(
        vectorsStream, PackedInts.Format.PACKED, totalFields, bitsRequired, 1);
    for (DocData dd : pendingDocs) {
      for (FieldData fd : dd.fields) {
        writer.add(fd.numTerms);
      }
    }
    assert writer.ord() == totalFields - 1;
    writer.finish();
  }

  private void flushTermLengths() throws IOException {
    writer.reset(vectorsStream);
    for (DocData dd : pendingDocs) {
      for (FieldData fd : dd.fields) {
        for (int i = 0; i < fd.numTerms; ++i) {
          writer.add(fd.prefixLengths[i]);
        }
      }
    }
    writer.finish();
    writer.reset(vectorsStream);
    for (DocData dd : pendingDocs) {
      for (FieldData fd : dd.fields) {
        for (int i = 0; i < fd.numTerms; ++i) {
          writer.add(fd.suffixLengths[i]);
        }
      }
    }
    writer.finish();
  }

  private void flushTermFreqs() throws IOException {
    writer.reset(vectorsStream);
    for (DocData dd : pendingDocs) {
      for (FieldData fd : dd.fields) {
        for (int i = 0; i < fd.numTerms; ++i) {
          writer.add(fd.freqs[i] - 1);
        }
      }
    }
    writer.finish();
  }

  private void flushPositions() throws IOException {
    writer.reset(vectorsStream);
    for (DocData dd : pendingDocs) {
      for (FieldData fd : dd.fields) {
        if (fd.hasPositions) {
          int pos = 0;
          for (int i = 0; i < fd.numTerms; ++i) {
            int previousPosition = 0;
            for (int j = 0; j < fd.freqs[i]; ++j) {
              final int position = positionsBuf[fd .posStart + pos++];
              writer.add(position - previousPosition);
              previousPosition = position;
            }
          }
          assert pos == fd.totalPositions;
        }
      }
    }
    writer.finish();
  }

  private void flushOffsets(int[] fieldNums) throws IOException {
    boolean hasOffsets = false;
    long[] sumPos = new long[fieldNums.length];
    long[] sumOffsets = new long[fieldNums.length];
    for (DocData dd : pendingDocs) {
      for (FieldData fd : dd.fields) {
        hasOffsets |= fd.hasOffsets;
        if (fd.hasOffsets && fd.hasPositions) {
          final int fieldNumOff = Arrays.binarySearch(fieldNums, fd.fieldNum);
          int pos = 0;
          for (int i = 0; i < fd.numTerms; ++i) {
            int previousPos = 0;
            int previousOff = 0;
            for (int j = 0; j < fd.freqs[i]; ++j) {
              final int position = positionsBuf[fd.posStart + pos];
              final int startOffset = startOffsetsBuf[fd.offStart + pos];
              sumPos[fieldNumOff] += position - previousPos;
              sumOffsets[fieldNumOff] += startOffset - previousOff;
              previousPos = position;
              previousOff = startOffset;
              ++pos;
            }
          }
          assert pos == fd.totalPositions;
        }
      }
    }

    if (!hasOffsets) {
      // nothing to do
      return;
    }

    final float[] charsPerTerm = new float[fieldNums.length];
    for (int i = 0; i < fieldNums.length; ++i) {
      charsPerTerm[i] = (sumPos[i] <= 0 || sumOffsets[i] <= 0) ? 0 : (float) ((double) sumOffsets[i] / sumPos[i]);
    }

    // start offsets
    for (int i = 0; i < fieldNums.length; ++i) {
      vectorsStream.writeInt(Float.floatToRawIntBits(charsPerTerm[i]));
    }

    writer.reset(vectorsStream);
    for (DocData dd : pendingDocs) {
      for (FieldData fd : dd.fields) {
        if ((fd.flags & OFFSETS) != 0) {
          final int fieldNumOff = Arrays.binarySearch(fieldNums, fd.fieldNum);
          final float cpt = charsPerTerm[fieldNumOff];
          int pos = 0;
          for (int i = 0; i < fd.numTerms; ++i) {
            int previousPos = 0;
            int previousOff = 0;
            for (int j = 0; j < fd.freqs[i]; ++j) {
              final int position = fd.hasPositions ? positionsBuf[fd.posStart + pos] : 0;
              final int startOffset = startOffsetsBuf[fd.offStart + pos];
              writer.add(startOffset - previousOff - (int) (cpt * (position - previousPos)));
              previousPos = position;
              previousOff = startOffset;
              ++pos;
            }
          }
        }
      }
    }
    writer.finish();

    // lengths
    writer.reset(vectorsStream);
    for (DocData dd : pendingDocs) {
      for (FieldData fd : dd.fields) {
        if ((fd.flags & OFFSETS) != 0) {
          int pos = 0;
          for (int i = 0; i < fd.numTerms; ++i) {
            for (int j = 0; j < fd.freqs[i]; ++j) {
              writer.add(lengthsBuf[fd.offStart + pos++] - fd.prefixLengths[i] - fd.suffixLengths[i]);
            }
          }
          assert pos == fd.totalPositions;
        }
      }
    }
    writer.finish();
  }

  private void flushPayloadLengths() throws IOException {
    writer.reset(vectorsStream);
    for (DocData dd : pendingDocs) {
      for (FieldData fd : dd.fields) {
        if (fd.hasPayloads) {
          for (int i = 0; i < fd.totalPositions; ++i) {
            writer.add(payloadLengthsBuf[fd.payStart + i]);
          }
        }
      }
    }
    writer.finish();
  }

  @Override
  public void finish(FieldInfos fis, int numDocs) throws IOException {
    if (!pendingDocs.isEmpty()) {
      flush();
    }
    if (numDocs != this.numDocs) {
      throw new RuntimeException("Wrote " + this.numDocs + " docs, finish called with numDocs=" + numDocs);
    }
    indexWriter.finish(numDocs, vectorsStream.getFilePointer());
    CodecUtil.writeFooter(vectorsStream);
  }

  @Override
  public void addProx(int numProx, DataInput positions, DataInput offsets)
      throws IOException {
    assert (curField.hasPositions) == (positions != null);
    assert (curField.hasOffsets) == (offsets != null);

    if (curField.hasPositions) {
      final int posStart = curField.posStart + curField.totalPositions;
      if (posStart + numProx > positionsBuf.length) {
        positionsBuf = ArrayUtil.grow(positionsBuf, posStart + numProx);
      }
      int position = 0;
      if (curField.hasPayloads) {
        final int payStart = curField.payStart + curField.totalPositions;
        if (payStart + numProx > payloadLengthsBuf.length) {
          payloadLengthsBuf = ArrayUtil.grow(payloadLengthsBuf, payStart + numProx);
        }
        for (int i = 0; i < numProx; ++i) {
          final int code = positions.readVInt();
          if ((code & 1) != 0) {
            // This position has a payload
            final int payloadLength = positions.readVInt();
            payloadLengthsBuf[payStart + i] = payloadLength;
            payloadBytes.copyBytes(positions, payloadLength);
          } else {
            payloadLengthsBuf[payStart + i] = 0;
          }
          position += code >>> 1;
          positionsBuf[posStart + i] = position;
        }
      } else {
        for (int i = 0; i < numProx; ++i) {
          position += (positions.readVInt() >>> 1);
          positionsBuf[posStart + i] = position;
        }
      }
    }

    if (curField.hasOffsets) {
      final int offStart = curField.offStart + curField.totalPositions;
      if (offStart + numProx > startOffsetsBuf.length) {
        final int newLength = ArrayUtil.oversize(offStart + numProx, 4);
        startOffsetsBuf = Arrays.copyOf(startOffsetsBuf, newLength);
        lengthsBuf = Arrays.copyOf(lengthsBuf, newLength);
      }
      int lastOffset = 0, startOffset, endOffset;
      for (int i = 0; i < numProx; ++i) {
        startOffset = lastOffset + offsets.readVInt();
        endOffset = startOffset + offsets.readVInt();
        lastOffset = endOffset;
        startOffsetsBuf[offStart + i] = startOffset;
        lengthsBuf[offStart + i] = endOffset - startOffset;
      }
    }

    curField.totalPositions += numProx;
  }
}
