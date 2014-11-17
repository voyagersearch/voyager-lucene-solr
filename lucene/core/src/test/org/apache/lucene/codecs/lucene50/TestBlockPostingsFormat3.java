package org.apache.lucene.codecs.lucene50;

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

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Random;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.MockFixedLengthPayloadFilter;
import org.apache.lucene.analysis.MockTokenizer;
import org.apache.lucene.analysis.MockVariableLengthPayloadFilter;
import org.apache.lucene.analysis.TokenFilter;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.document.Document2;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.FieldType;
import org.apache.lucene.document.FieldTypes;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.DocsAndPositionsEnum;
import org.apache.lucene.index.DocsEnum;
import org.apache.lucene.index.IndexOptions;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig.OpenMode;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.RandomIndexWriter;
import org.apache.lucene.index.Terms;
import org.apache.lucene.index.TermsEnum.SeekStatus;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.store.Directory;
import org.apache.lucene.util.Bits;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.English;
import org.apache.lucene.util.FixedBitSet;
import org.apache.lucene.util.LuceneTestCase;
import org.apache.lucene.util.TestUtil;
import org.apache.lucene.util.automaton.AutomatonTestUtil;
import org.apache.lucene.util.automaton.CompiledAutomaton;
import org.apache.lucene.util.automaton.RegExp;

/** 
 * Tests partial enumeration (only pulling a subset of the indexed data) 
 */
public class TestBlockPostingsFormat3 extends LuceneTestCase {
  static final int MAXDOC = Lucene50PostingsFormat.BLOCK_SIZE * 20;
  
  // creates 8 fields with different options and does "duels" of fields against each other
  public void test() throws Exception {
    Directory dir = newDirectory();
    Analyzer analyzer = new Analyzer(Analyzer.PER_FIELD_REUSE_STRATEGY) {
      @Override
      protected TokenStreamComponents createComponents(String fieldName) {
        Tokenizer tokenizer = new MockTokenizer();
        if (fieldName.contains("payloadsFixed")) {
          TokenFilter filter = new MockFixedLengthPayloadFilter(new Random(0), tokenizer, 1);
          return new TokenStreamComponents(tokenizer, filter);
        } else if (fieldName.contains("payloadsVariable")) {
          TokenFilter filter = new MockVariableLengthPayloadFilter(new Random(0), tokenizer);
          return new TokenStreamComponents(tokenizer, filter);
        } else {
          return new TokenStreamComponents(tokenizer);
        }
      }
    };
    IndexWriterConfig iwc = newIndexWriterConfig(analyzer);
    iwc.setCodec(TestUtil.alwaysPostingsFormat(new Lucene50PostingsFormat()));
    // TODO we could actually add more fields implemented with different PFs
    // or, just put this test into the usual rotation?
    RandomIndexWriter iw = new RandomIndexWriter(random(), dir, iwc);

    FieldTypes fieldTypes = iw.getFieldTypes();

    // turn these on for a cross-check
    fieldTypes.enableTermVectors("field1docs");
    fieldTypes.disableHighlighting("field1docs");
    fieldTypes.setIndexOptions("field1docs", IndexOptions.DOCS);

    // turn these on for a cross-check
    fieldTypes.enableTermVectors("field2freqs");
    fieldTypes.disableHighlighting("field2freqs");
    fieldTypes.setIndexOptions("field2freqs", IndexOptions.DOCS);

    for(String fieldName : new String[] {"field3positons",
                                         "field5payloadsFixed",
                                         "field6payloadsVariable"}) {
      // turn these on for a cross-check
      fieldTypes.enableTermVectors(fieldName);
      fieldTypes.disableHighlighting(fieldName);
      fieldTypes.setIndexOptions(fieldName, IndexOptions.DOCS_AND_FREQS_AND_POSITIONS);
    }

    for(String fieldName : new String[] {"field4offsets",
                                         "field7payloadsFixedOffsets",
                                         "field8payloadsVariableOffsets"}) {
      // turn these on for a cross-check
      fieldTypes.enableTermVectors(fieldName);
      fieldTypes.setIndexOptions(fieldName, IndexOptions.DOCS_AND_FREQS_AND_POSITIONS_AND_OFFSETS);
    }

    for (int i = 0; i < MAXDOC; i++) {
      String stringValue = Integer.toString(i) + " verycommon " + English.intToEnglish(i).replace('-', ' ') + " " + TestUtil.randomSimpleString(random());
      Document2 doc = iw.newDocument();
      for(String fieldName : new String[] {
          "field1docs",
          "field2freqs",
          "field3positions",
          "field4offsets",
          "field5payloadsFixed",
          "field6payloadsVariable",
          "field7payloadsFixedOffsets",
          "field8payloadsVariableOffsets"}) { 
        doc.addLargeText(fieldName, stringValue);
      }
      iw.addDocument(doc);
    }
    iw.close();
    verify(dir);
    TestUtil.checkIndex(dir); // for some extra coverage, checkIndex before we forceMerge
    iwc = newIndexWriterConfig(analyzer);
    iwc.setCodec(TestUtil.alwaysPostingsFormat(new Lucene50PostingsFormat()));
    iwc.setOpenMode(OpenMode.APPEND);
    IndexWriter iw2 = new IndexWriter(dir, iwc);
    iw2.forceMerge(1);
    iw2.close();
    verify(dir);
    dir.close();
  }
  
  private void verify(Directory dir) throws Exception {
    DirectoryReader ir = DirectoryReader.open(dir);
    for (LeafReaderContext leaf : ir.leaves()) {
      LeafReader leafReader = leaf.reader();
      assertTerms(leafReader.terms("field1docs"), leafReader.terms("field2freqs"), true);
      assertTerms(leafReader.terms("field3positions"), leafReader.terms("field4offsets"), true);
      assertTerms(leafReader.terms("field4offsets"), leafReader.terms("field5payloadsFixed"), true);
      assertTerms(leafReader.terms("field5payloadsFixed"), leafReader.terms("field6payloadsVariable"), true);
      assertTerms(leafReader.terms("field6payloadsVariable"), leafReader.terms("field7payloadsFixedOffsets"), true);
      assertTerms(leafReader.terms("field7payloadsFixedOffsets"), leafReader.terms("field8payloadsVariableOffsets"), true);
    }
    ir.close();
  }
  
  // following code is almost an exact dup of code from TestDuelingCodecs: sorry!
  
  public void assertTerms(Terms leftTerms, Terms rightTerms, boolean deep) throws Exception {
    if (leftTerms == null || rightTerms == null) {
      assertNull(leftTerms);
      assertNull(rightTerms);
      return;
    }
    assertTermsStatistics(leftTerms, rightTerms);
    
    // NOTE: we don't assert hasOffsets/hasPositions/hasPayloads because they are allowed to be different

    TermsEnum leftTermsEnum = leftTerms.iterator(null);
    TermsEnum rightTermsEnum = rightTerms.iterator(null);
    assertTermsEnum(leftTermsEnum, rightTermsEnum, true);
    
    assertTermsSeeking(leftTerms, rightTerms);
    
    if (deep) {
      int numIntersections = atLeast(3);
      for (int i = 0; i < numIntersections; i++) {
        String re = AutomatonTestUtil.randomRegexp(random());
        CompiledAutomaton automaton = new CompiledAutomaton(new RegExp(re, RegExp.NONE).toAutomaton());
        if (automaton.type == CompiledAutomaton.AUTOMATON_TYPE.NORMAL) {
          // TODO: test start term too
          TermsEnum leftIntersection = leftTerms.intersect(automaton, null);
          TermsEnum rightIntersection = rightTerms.intersect(automaton, null);
          assertTermsEnum(leftIntersection, rightIntersection, rarely());
        }
      }
    }
  }
  
  private void assertTermsSeeking(Terms leftTerms, Terms rightTerms) throws Exception {
    TermsEnum leftEnum = null;
    TermsEnum rightEnum = null;
    
    // just an upper bound
    int numTests = atLeast(20);
    Random random = random();
    
    // collect this number of terms from the left side
    HashSet<BytesRef> tests = new HashSet<>();
    int numPasses = 0;
    while (numPasses < 10 && tests.size() < numTests) {
      leftEnum = leftTerms.iterator(leftEnum);
      BytesRef term = null;
      while ((term = leftEnum.next()) != null) {
        int code = random.nextInt(10);
        if (code == 0) {
          // the term
          tests.add(BytesRef.deepCopyOf(term));
        } else if (code == 1) {
          // truncated subsequence of term
          term = BytesRef.deepCopyOf(term);
          if (term.length > 0) {
            // truncate it
            term.length = random.nextInt(term.length);
          }
        } else if (code == 2) {
          // term, but ensure a non-zero offset
          byte newbytes[] = new byte[term.length+5];
          System.arraycopy(term.bytes, term.offset, newbytes, 5, term.length);
          tests.add(new BytesRef(newbytes, 5, term.length));
        }
      }
      numPasses++;
    }
    
    ArrayList<BytesRef> shuffledTests = new ArrayList<>(tests);
    Collections.shuffle(shuffledTests, random);
    
    for (BytesRef b : shuffledTests) {
      leftEnum = leftTerms.iterator(leftEnum);
      rightEnum = rightTerms.iterator(rightEnum);
      
      assertEquals(leftEnum.seekExact(b), rightEnum.seekExact(b));
      assertEquals(leftEnum.seekExact(b), rightEnum.seekExact(b));
      
      SeekStatus leftStatus;
      SeekStatus rightStatus;
      
      leftStatus = leftEnum.seekCeil(b);
      rightStatus = rightEnum.seekCeil(b);
      assertEquals(leftStatus, rightStatus);
      if (leftStatus != SeekStatus.END) {
        assertEquals(leftEnum.term(), rightEnum.term());
      }
      
      leftStatus = leftEnum.seekCeil(b);
      rightStatus = rightEnum.seekCeil(b);
      assertEquals(leftStatus, rightStatus);
      if (leftStatus != SeekStatus.END) {
        assertEquals(leftEnum.term(), rightEnum.term());
      }
    }
  }
  
  /** 
   * checks collection-level statistics on Terms 
   */
  public void assertTermsStatistics(Terms leftTerms, Terms rightTerms) throws Exception {
    if (leftTerms.getDocCount() != -1 && rightTerms.getDocCount() != -1) {
      assertEquals(leftTerms.getDocCount(), rightTerms.getDocCount());
    }
    if (leftTerms.getSumDocFreq() != -1 && rightTerms.getSumDocFreq() != -1) {
      assertEquals(leftTerms.getSumDocFreq(), rightTerms.getSumDocFreq());
    }
    if (leftTerms.getSumTotalTermFreq() != -1 && rightTerms.getSumTotalTermFreq() != -1) {
      assertEquals(leftTerms.getSumTotalTermFreq(), rightTerms.getSumTotalTermFreq());
    }
    if (leftTerms.size() != -1 && rightTerms.size() != -1) {
      assertEquals(leftTerms.size(), rightTerms.size());
    }
  }

  /** 
   * checks the terms enum sequentially
   * if deep is false, it does a 'shallow' test that doesnt go down to the docsenums
   */
  public void assertTermsEnum(TermsEnum leftTermsEnum, TermsEnum rightTermsEnum, boolean deep) throws Exception {
    BytesRef term;
    Bits randomBits = new RandomBits(MAXDOC, random().nextDouble(), random());
    DocsAndPositionsEnum leftPositions = null;
    DocsAndPositionsEnum rightPositions = null;
    DocsEnum leftDocs = null;
    DocsEnum rightDocs = null;
    
    while ((term = leftTermsEnum.next()) != null) {
      assertEquals(term, rightTermsEnum.next());
      assertTermStats(leftTermsEnum, rightTermsEnum);
      if (deep) {
        // with payloads + off
        assertDocsAndPositionsEnum(leftPositions = leftTermsEnum.docsAndPositions(null, leftPositions),
                                   rightPositions = rightTermsEnum.docsAndPositions(null, rightPositions));
        assertDocsAndPositionsEnum(leftPositions = leftTermsEnum.docsAndPositions(randomBits, leftPositions),
                                   rightPositions = rightTermsEnum.docsAndPositions(randomBits, rightPositions));

        assertPositionsSkipping(leftTermsEnum.docFreq(), 
                                leftPositions = leftTermsEnum.docsAndPositions(null, leftPositions),
                                rightPositions = rightTermsEnum.docsAndPositions(null, rightPositions));
        assertPositionsSkipping(leftTermsEnum.docFreq(), 
                                leftPositions = leftTermsEnum.docsAndPositions(randomBits, leftPositions),
                                rightPositions = rightTermsEnum.docsAndPositions(randomBits, rightPositions));
        // with payloads only
        assertDocsAndPositionsEnum(leftPositions = leftTermsEnum.docsAndPositions(null, leftPositions, DocsAndPositionsEnum.FLAG_PAYLOADS),
                                   rightPositions = rightTermsEnum.docsAndPositions(null, rightPositions, DocsAndPositionsEnum.FLAG_PAYLOADS));
        assertDocsAndPositionsEnum(leftPositions = leftTermsEnum.docsAndPositions(randomBits, leftPositions, DocsAndPositionsEnum.FLAG_PAYLOADS),
                                   rightPositions = rightTermsEnum.docsAndPositions(randomBits, rightPositions, DocsAndPositionsEnum.FLAG_PAYLOADS));

        assertPositionsSkipping(leftTermsEnum.docFreq(), 
                                leftPositions = leftTermsEnum.docsAndPositions(null, leftPositions, DocsAndPositionsEnum.FLAG_PAYLOADS),
                                rightPositions = rightTermsEnum.docsAndPositions(null, rightPositions, DocsAndPositionsEnum.FLAG_PAYLOADS));
        assertPositionsSkipping(leftTermsEnum.docFreq(), 
                                leftPositions = leftTermsEnum.docsAndPositions(randomBits, leftPositions, DocsAndPositionsEnum.FLAG_PAYLOADS),
                                rightPositions = rightTermsEnum.docsAndPositions(randomBits, rightPositions, DocsAndPositionsEnum.FLAG_PAYLOADS));

        // with offsets only
        assertDocsAndPositionsEnum(leftPositions = leftTermsEnum.docsAndPositions(null, leftPositions, DocsAndPositionsEnum.FLAG_OFFSETS),
                                   rightPositions = rightTermsEnum.docsAndPositions(null, rightPositions, DocsAndPositionsEnum.FLAG_OFFSETS));
        assertDocsAndPositionsEnum(leftPositions = leftTermsEnum.docsAndPositions(randomBits, leftPositions, DocsAndPositionsEnum.FLAG_OFFSETS),
                                   rightPositions = rightTermsEnum.docsAndPositions(randomBits, rightPositions, DocsAndPositionsEnum.FLAG_OFFSETS));

        assertPositionsSkipping(leftTermsEnum.docFreq(), 
                                leftPositions = leftTermsEnum.docsAndPositions(null, leftPositions, DocsAndPositionsEnum.FLAG_OFFSETS),
                                rightPositions = rightTermsEnum.docsAndPositions(null, rightPositions, DocsAndPositionsEnum.FLAG_OFFSETS));
        assertPositionsSkipping(leftTermsEnum.docFreq(), 
                                leftPositions = leftTermsEnum.docsAndPositions(randomBits, leftPositions, DocsAndPositionsEnum.FLAG_OFFSETS),
                                rightPositions = rightTermsEnum.docsAndPositions(randomBits, rightPositions, DocsAndPositionsEnum.FLAG_OFFSETS));
        
        // with positions only
        assertDocsAndPositionsEnum(leftPositions = leftTermsEnum.docsAndPositions(null, leftPositions, DocsEnum.FLAG_NONE),
                                   rightPositions = rightTermsEnum.docsAndPositions(null, rightPositions, DocsEnum.FLAG_NONE));
        assertDocsAndPositionsEnum(leftPositions = leftTermsEnum.docsAndPositions(randomBits, leftPositions, DocsEnum.FLAG_NONE),
                                   rightPositions = rightTermsEnum.docsAndPositions(randomBits, rightPositions, DocsEnum.FLAG_NONE));

        assertPositionsSkipping(leftTermsEnum.docFreq(), 
                                leftPositions = leftTermsEnum.docsAndPositions(null, leftPositions, DocsEnum.FLAG_NONE),
                                rightPositions = rightTermsEnum.docsAndPositions(null, rightPositions, DocsEnum.FLAG_NONE));
        assertPositionsSkipping(leftTermsEnum.docFreq(), 
                                leftPositions = leftTermsEnum.docsAndPositions(randomBits, leftPositions, DocsEnum.FLAG_NONE),
                                rightPositions = rightTermsEnum.docsAndPositions(randomBits, rightPositions, DocsEnum.FLAG_NONE));
        
        // with freqs:
        assertDocsEnum(leftDocs = leftTermsEnum.docs(null, leftDocs),
            rightDocs = rightTermsEnum.docs(null, rightDocs));
        assertDocsEnum(leftDocs = leftTermsEnum.docs(randomBits, leftDocs),
            rightDocs = rightTermsEnum.docs(randomBits, rightDocs));

        // w/o freqs:
        assertDocsEnum(leftDocs = leftTermsEnum.docs(null, leftDocs, DocsEnum.FLAG_NONE),
            rightDocs = rightTermsEnum.docs(null, rightDocs, DocsEnum.FLAG_NONE));
        assertDocsEnum(leftDocs = leftTermsEnum.docs(randomBits, leftDocs, DocsEnum.FLAG_NONE),
            rightDocs = rightTermsEnum.docs(randomBits, rightDocs, DocsEnum.FLAG_NONE));
        
        // with freqs:
        assertDocsSkipping(leftTermsEnum.docFreq(), 
            leftDocs = leftTermsEnum.docs(null, leftDocs),
            rightDocs = rightTermsEnum.docs(null, rightDocs));
        assertDocsSkipping(leftTermsEnum.docFreq(), 
            leftDocs = leftTermsEnum.docs(randomBits, leftDocs),
            rightDocs = rightTermsEnum.docs(randomBits, rightDocs));

        // w/o freqs:
        assertDocsSkipping(leftTermsEnum.docFreq(), 
            leftDocs = leftTermsEnum.docs(null, leftDocs, DocsEnum.FLAG_NONE),
            rightDocs = rightTermsEnum.docs(null, rightDocs, DocsEnum.FLAG_NONE));
        assertDocsSkipping(leftTermsEnum.docFreq(), 
            leftDocs = leftTermsEnum.docs(randomBits, leftDocs, DocsEnum.FLAG_NONE),
            rightDocs = rightTermsEnum.docs(randomBits, rightDocs, DocsEnum.FLAG_NONE));
      }
    }
    assertNull(rightTermsEnum.next());
  }
  
  /**
   * checks term-level statistics
   */
  public void assertTermStats(TermsEnum leftTermsEnum, TermsEnum rightTermsEnum) throws Exception {
    assertEquals(leftTermsEnum.docFreq(), rightTermsEnum.docFreq());
    if (leftTermsEnum.totalTermFreq() != -1 && rightTermsEnum.totalTermFreq() != -1) {
      assertEquals(leftTermsEnum.totalTermFreq(), rightTermsEnum.totalTermFreq());
    }
  }
  
  /**
   * checks docs + freqs + positions + payloads, sequentially
   */
  public void assertDocsAndPositionsEnum(DocsAndPositionsEnum leftDocs, DocsAndPositionsEnum rightDocs) throws Exception {
    if (leftDocs == null || rightDocs == null) {
      assertNull(leftDocs);
      assertNull(rightDocs);
      return;
    }
    assertEquals(-1, leftDocs.docID());
    assertEquals(-1, rightDocs.docID());
    int docid;
    while ((docid = leftDocs.nextDoc()) != DocIdSetIterator.NO_MORE_DOCS) {
      assertEquals(docid, rightDocs.nextDoc());
      int freq = leftDocs.freq();
      assertEquals(freq, rightDocs.freq());
      for (int i = 0; i < freq; i++) {
        assertEquals(leftDocs.nextPosition(), rightDocs.nextPosition());
        // we don't assert offsets/payloads, they are allowed to be different
      }
    }
    assertEquals(DocIdSetIterator.NO_MORE_DOCS, rightDocs.nextDoc());
  }
  
  /**
   * checks docs + freqs, sequentially
   */
  public void assertDocsEnum(DocsEnum leftDocs, DocsEnum rightDocs) throws Exception {
    if (leftDocs == null) {
      assertNull(rightDocs);
      return;
    }
    assertEquals(-1, leftDocs.docID());
    assertEquals(-1, rightDocs.docID());
    int docid;
    while ((docid = leftDocs.nextDoc()) != DocIdSetIterator.NO_MORE_DOCS) {
      assertEquals(docid, rightDocs.nextDoc());
      // we don't assert freqs, they are allowed to be different
    }
    assertEquals(DocIdSetIterator.NO_MORE_DOCS, rightDocs.nextDoc());
  }
  
  /**
   * checks advancing docs
   */
  public void assertDocsSkipping(int docFreq, DocsEnum leftDocs, DocsEnum rightDocs) throws Exception {
    if (leftDocs == null) {
      assertNull(rightDocs);
      return;
    }
    int docid = -1;
    int averageGap = MAXDOC / (1+docFreq);
    int skipInterval = 16;

    while (true) {
      if (random().nextBoolean()) {
        // nextDoc()
        docid = leftDocs.nextDoc();
        assertEquals(docid, rightDocs.nextDoc());
      } else {
        // advance()
        int skip = docid + (int) Math.ceil(Math.abs(skipInterval + random().nextGaussian() * averageGap));
        docid = leftDocs.advance(skip);
        assertEquals(docid, rightDocs.advance(skip));
      }
      
      if (docid == DocIdSetIterator.NO_MORE_DOCS) {
        return;
      }
      // we don't assert freqs, they are allowed to be different
    }
  }
  
  /**
   * checks advancing docs + positions
   */
  public void assertPositionsSkipping(int docFreq, DocsAndPositionsEnum leftDocs, DocsAndPositionsEnum rightDocs) throws Exception {
    if (leftDocs == null || rightDocs == null) {
      assertNull(leftDocs);
      assertNull(rightDocs);
      return;
    }
    
    int docid = -1;
    int averageGap = MAXDOC / (1+docFreq);
    int skipInterval = 16;

    while (true) {
      if (random().nextBoolean()) {
        // nextDoc()
        docid = leftDocs.nextDoc();
        assertEquals(docid, rightDocs.nextDoc());
      } else {
        // advance()
        int skip = docid + (int) Math.ceil(Math.abs(skipInterval + random().nextGaussian() * averageGap));
        docid = leftDocs.advance(skip);
        assertEquals(docid, rightDocs.advance(skip));
      }
      
      if (docid == DocIdSetIterator.NO_MORE_DOCS) {
        return;
      }
      int freq = leftDocs.freq();
      assertEquals(freq, rightDocs.freq());
      for (int i = 0; i < freq; i++) {
        assertEquals(leftDocs.nextPosition(), rightDocs.nextPosition());
        // we don't compare the payloads, its allowed that one is empty etc
      }
    }
  }
  
  private static class RandomBits implements Bits {
    FixedBitSet bits;
    
    RandomBits(int maxDoc, double pctLive, Random random) {
      bits = new FixedBitSet(maxDoc);
      for (int i = 0; i < maxDoc; i++) {
        if (random.nextDouble() <= pctLive) {        
          bits.set(i);
        }
      }
    }
    
    @Override
    public boolean get(int index) {
      return bits.get(index);
    }

    @Override
    public int length() {
      return bits.length();
    }
  }
}
