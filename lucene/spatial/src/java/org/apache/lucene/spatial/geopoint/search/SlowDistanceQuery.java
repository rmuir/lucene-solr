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
package org.apache.lucene.spatial.geopoint.search;

import static org.apache.lucene.spatial.util.GeoEncodingUtils.mortonUnhashLat;
import static org.apache.lucene.spatial.util.GeoEncodingUtils.mortonUnhashLon;

import java.io.IOException;

import org.apache.lucene.index.DocValues;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.SortedNumericDocValues;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.RandomAccessWeight;
import org.apache.lucene.search.Weight;
import org.apache.lucene.util.Bits;
import org.apache.lucene.util.SloppyMath;

public class SlowDistanceQuery extends Query {
  final String field;
  final double latitude;
  final double longitude;
  final double radius;
  
  public SlowDistanceQuery(String field, double latitude, double longitude, double radius) {
    this.field = field;
    this.latitude = latitude;
    this.longitude = longitude;
    this.radius = radius;
  }

  @Override
  public String toString(String field) {
    return "SLOW";
  }
  
  @Override
  public int hashCode() {
    final int prime = 31;
    int result = super.hashCode();
    result = prime * result + ((field == null) ? 0 : field.hashCode());
    long temp;
    temp = Double.doubleToLongBits(latitude);
    result = prime * result + (int) (temp ^ (temp >>> 32));
    temp = Double.doubleToLongBits(longitude);
    result = prime * result + (int) (temp ^ (temp >>> 32));
    temp = Double.doubleToLongBits(radius);
    result = prime * result + (int) (temp ^ (temp >>> 32));
    return result;
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj) return true;
    if (!super.equals(obj)) return false;
    if (getClass() != obj.getClass()) return false;
    SlowDistanceQuery other = (SlowDistanceQuery) obj;
    if (field == null) {
      if (other.field != null) return false;
    } else if (!field.equals(other.field)) return false;
    if (Double.doubleToLongBits(latitude) != Double.doubleToLongBits(other.latitude)) return false;
    if (Double.doubleToLongBits(longitude) != Double.doubleToLongBits(other.longitude)) return false;
    if (Double.doubleToLongBits(radius) != Double.doubleToLongBits(other.radius)) return false;
    return true;
  }

  @Override
  public Weight createWeight(IndexSearcher searcher, boolean needsScores) throws IOException {
    return new RandomAccessWeight(this) {
      @Override
      protected Bits getMatchingDocs(LeafReaderContext context) throws IOException {
        final SortedNumericDocValues values = DocValues.getSortedNumeric(context.reader(), field);
        return new Bits() {
          @Override
          public boolean get(int doc) {
            values.setDocument(doc);
            int count = values.count();
            for (int i = 0; i < count; i++) {
              long hash = values.valueAt(i);
              double docLat = mortonUnhashLat(hash);
              double docLon = mortonUnhashLon(hash);
              if (SloppyMath.haversinMeters(latitude, longitude, docLat, docLon) <= radius) {
                return true;
              }
            }
            return false;
          }

          @Override
          public int length() {
            return context.reader().maxDoc();
          }
        };
      }
    };
  }
}
