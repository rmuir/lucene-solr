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
package org.apache.lucene.document;

import java.io.IOException;

import org.apache.lucene.index.DocValues;
import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.SortedNumericDocValues;
import org.apache.lucene.search.FieldComparator;
import org.apache.lucene.search.LeafFieldComparator;
import org.apache.lucene.search.Scorer;
import org.apache.lucene.spatial.util.GeoDistanceUtils;
import org.apache.lucene.spatial.util.GeoRect;
import org.apache.lucene.spatial.util.GeoUtils;

/** Compares docs by distance from an origin */
class LatLonPointDistanceComparator extends FieldComparator<Double> implements LeafFieldComparator {
  final String field;
  final double latitude;
  final double longitude;
  final double missingValue;

  final double[] values;
  double bottom;
  double topValue;
  SortedNumericDocValues currentDocs;
  
  // current bounding box for the bottom distance on the PQ.
  // used to exclude uncompetitive hits faster.
  GeoRect box1 = null;
  GeoRect box2 = null;
  // the number of times setBottom has been called
  int setBottomCounter = 0;

  public LatLonPointDistanceComparator(String field, double latitude, double longitude, int numHits, double missingValue) {
    this.field = field;
    this.latitude = latitude;
    this.longitude = longitude;
    this.values = new double[numHits];
    this.missingValue = missingValue;
  }
  
  @Override
  public void setScorer(Scorer scorer) {}

  @Override
  public int compare(int slot1, int slot2) {
    return Double.compare(values[slot1], values[slot2]);
  }
  
  @Override
  public void setBottom(int slot) {
    bottom = values[slot];
    // make bounding box(es) to exclude non-competitive hits, but start
    // sampling if we get called way too much: don't make gobs of bounding
    // boxes if comparator hits a worst case adversary (e.g. backwards distance order)
    if (setBottomCounter < 1024 || (setBottomCounter & 0x3F) == 0x3F) {
      GeoRect box = GeoUtils.circleToBBox(longitude, latitude, bottom);
      // crosses dateline: split
      if (box.crossesDateline()) {
        box1 = new GeoRect(-180.0, box.maxLon, box.minLat, box.maxLat);
        box2 = new GeoRect(box.minLon, 180.0, box.minLat, box.maxLat);
      } else {
        box1 = box;
        box2 = null;
      }
    }
    setBottomCounter++;
  }
  
  @Override
  public void setTopValue(Double value) {
    topValue = value.doubleValue();
  }
  
  @Override
  public int compareBottom(int doc) throws IOException {
    currentDocs.setDocument(doc);

    int numValues = currentDocs.count();
    if (numValues == 0) {
      return Double.compare(bottom, missingValue);
    }

    double minValue = Double.POSITIVE_INFINITY;
    for (int i = 0; i < numValues; i++) {
      long encoded = currentDocs.valueAt(i);
      double docLatitude = LatLonPoint.decodeLatitude((int)(encoded >> 32));
      double docLongitude = LatLonPoint.decodeLongitude((int)(encoded & 0xFFFFFFFF));
      boolean outsideBox = ((docLatitude < box1.minLat || docLongitude < box1.minLon || docLatitude > box1.maxLat || docLongitude > box1.maxLon) &&
            (box2 == null || docLatitude < box2.minLat || docLongitude < box2.minLon || docLatitude > box2.maxLat || docLongitude > box2.maxLon));
      // only compute actual distance if its inside "competitive bounding box"
      if (outsideBox == false) {
        minValue = Math.min(minValue, GeoDistanceUtils.haversin(latitude, longitude, docLatitude, docLongitude));
      }
    }
    return Double.compare(bottom, minValue);
  }
  
  @Override
  public void copy(int slot, int doc) throws IOException {
    values[slot] = distance(doc);
  }
  
  @Override
  public LeafFieldComparator getLeafComparator(LeafReaderContext context) throws IOException {
    LeafReader reader = context.reader();
    FieldInfo info = reader.getFieldInfos().fieldInfo(field);
    if (info != null) {
      LatLonPoint.checkCompatible(info);
    }
    currentDocs = DocValues.getSortedNumeric(reader, field);
    return this;
  }
  
  @Override
  public Double value(int slot) {
    return Double.valueOf(values[slot]);
  }
  
  @Override
  public int compareTop(int doc) throws IOException {
    return Double.compare(topValue, distance(doc));
  }
  
  // TODO: optimize for single-valued case?
  // TODO: do all kinds of other optimizations!
  double distance(int doc) {
    currentDocs.setDocument(doc);

    int numValues = currentDocs.count();
    if (numValues == 0) {
      return missingValue;
    }

    double minValue = Double.POSITIVE_INFINITY;
    for (int i = 0; i < numValues; i++) {
      long encoded = currentDocs.valueAt(i);
      double docLatitude = LatLonPoint.decodeLatitude((int)(encoded >> 32));
      double docLongitude = LatLonPoint.decodeLongitude((int)(encoded & 0xFFFFFFFF));
      minValue = Math.min(minValue, GeoDistanceUtils.haversin(latitude, longitude, docLatitude, docLongitude));
    }
    return minValue;
  }
}
