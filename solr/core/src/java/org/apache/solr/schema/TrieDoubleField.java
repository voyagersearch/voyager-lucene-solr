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

package org.apache.solr.schema;

import java.io.IOException;
import java.util.Map;

import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.DocValues;
import org.apache.lucene.index.SortedDocValues;
import org.apache.lucene.index.SortedSetDocValues;
import org.apache.lucene.queries.function.ValueSource;
import org.apache.lucene.queries.function.FunctionValues;
import org.apache.lucene.queries.function.docvalues.DoubleDocValues;
import org.apache.lucene.queries.function.valuesource.SortedSetFieldSource;
import org.apache.lucene.search.SortedSetSelector;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.NumericUtils;
import org.apache.lucene.util.mutable.MutableValue;
import org.apache.lucene.util.mutable.MutableValueDouble;

/**
 * A numeric field that can contain double-precision 64-bit IEEE 754 floating 
 * point values.
 *
 * <ul>
 *  <li>Min Value Allowed: 4.9E-324</li>
 *  <li>Max Value Allowed: 1.7976931348623157E308</li>
 * </ul>
 *
 * <b>NOTE:</b> The behavior of this class when given values of 
 * {@link Double#NaN}, {@link Double#NEGATIVE_INFINITY}, or 
 * {@link Double#POSITIVE_INFINITY} is undefined.
 * 
 * @see Double
 * @see <a href="http://java.sun.com/docs/books/jls/third_edition/html/typesValues.html#4.2.3">Java Language Specification, s4.2.3</a>
 */
public class TrieDoubleField extends TrieField implements DoubleValueFieldType {
  {
    type=TrieTypes.DOUBLE;
  }
  
  @Override
  protected ValueSource getSingleValueSource(SortedSetSelector.Type choice, SchemaField f) {
    
    return new SortedSetFieldSource(f.getName(), choice) {
      @Override
      public FunctionValues getValues(Map context, LeafReaderContext readerContext) throws IOException {
        SortedSetFieldSource thisAsSortedSetFieldSource = this; // needed for nested anon class ref
        
        SortedSetDocValues sortedSet = DocValues.getSortedSet(readerContext.reader(), field);
        final SortedDocValues view = SortedSetSelector.wrap(sortedSet, selector);
        
        return new DoubleDocValues(thisAsSortedSetFieldSource) {
          @Override
          public double doubleVal(int doc) {
            BytesRef bytes = view.get(doc);
            if (0 == bytes.length) {
              // the only way this should be possible is for non existent value
              assert !exists(doc) : "zero bytes for doc, but exists is true";
              return 0D;
            }
            return  NumericUtils.sortableLongToDouble(NumericUtils.prefixCodedToLong(bytes));
          }

          @Override
          public boolean exists(int doc) {
            return -1 != view.getOrd(doc);
          }

          @Override
          public ValueFiller getValueFiller() {
            return new ValueFiller() {
              private final MutableValueDouble mval = new MutableValueDouble();
              
              @Override
              public MutableValue getValue() {
                return mval;
              }
              
              @Override
              public void fillValue(int doc) {
                // micro optimized (eliminate at least one redudnent ord check) 
                //mval.exists = exists(doc);
                //mval.value = mval.exists ? doubleVal(doc) : 0.0D;
                BytesRef bytes = view.get(doc);
                mval.exists = (0 == bytes.length);
                mval.value = mval.exists ? NumericUtils.sortableLongToDouble(NumericUtils.prefixCodedToLong(bytes)) : 0D;
              }
            };
          }
        };
      }
    };
  }
}
