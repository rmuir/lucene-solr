package org.apache.lucene.spatial.geopoint.search;

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

import java.io.IOException;

import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.PointValues.Relation;
import org.apache.lucene.index.Terms;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.search.MultiTermQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.util.AttributeSource;
import org.apache.lucene.util.BitUtil;
import org.apache.lucene.spatial.geopoint.document.GeoPointField;
import org.apache.lucene.spatial.geopoint.document.GeoPointField.TermEncoding;
import org.apache.lucene.spatial.util.GeoEncodingUtils;
import org.apache.lucene.spatial.util.GeoRelationUtils;
import org.apache.lucene.geo.GeoUtils;
import org.apache.lucene.util.SloppyMath;

/**
 * TermQuery for GeoPointField for overriding {@link org.apache.lucene.search.MultiTermQuery} methods specific to
 * Geospatial operations
 *
 * @lucene.experimental
 */
abstract class GeoPointMultiTermQuery extends MultiTermQuery {
  // exposed for subclasses - no objects used to avoid dependencies
  // these are not quantized and are the original user-provided values
  // TODO: these really belong in the bbox impl only (for two-phase checks).
  // but everything currently subclasses that? If we fix rounding, they can get removed
  // that way too (bbox won't ever need two-phases).
  protected final double minLon;
  protected final double minLat;
  protected final double maxLon;
  protected final double maxLat;

  protected final short maxShift;
  protected final TermEncoding termEncoding;
  protected final CellComparator cellComparator;
  
  // these are used for low level MBR intersection tests
  // they are currently "expanded" 1 geopoint "ulp" in each direction to compensate for rounding/overflow issues.
  final long minLatMBR;
  final long maxLatMBR;
  final long minLonMBR;
  final long maxLonMBR;
  
  // TODO: maybe some two-phase checks could benefit from minHashMBR/maxHashMBR? if documents have multiple values
  // especially this can avoid deinterleave cost.

  /**
   * Constructs a query matching terms that cannot be represented with a single
   * Term.
   */
  GeoPointMultiTermQuery(String field, final TermEncoding termEncoding, final double minLat, final double maxLat, final double minLon, final double maxLon) {
    super(field);

    GeoUtils.checkLatitude(minLat);
    GeoUtils.checkLatitude(maxLat);
    GeoUtils.checkLongitude(minLon);
    GeoUtils.checkLongitude(maxLon);

    this.minLat = minLat;
    this.maxLat = maxLat;
    this.minLon = minLon;
    this.maxLon = maxLon;
    
    // TODO: add scaleLatFloor/scaleLatCeil instead of increasing one ulp ?
    // TODO: if we improve the encoding, factor this out
    long minLatBits = GeoEncodingUtils.scaleLat(minLat);
    long maxLatBits = GeoEncodingUtils.scaleLat(maxLat);
    long minLonBits = GeoEncodingUtils.scaleLon(minLon);
    long maxLonBits = GeoEncodingUtils.scaleLon(maxLon);
    assert minLatBits >= 0 && minLatBits <= 1L + Integer.MAX_VALUE;
    assert maxLatBits >= 0 && maxLatBits <= 1L + Integer.MAX_VALUE;
    assert minLonBits >= 0 && minLonBits <= 1L + Integer.MAX_VALUE;
    assert maxLonBits >= 0 && maxLonBits <= 1L + Integer.MAX_VALUE;
    minLatMBR = Math.max(0, minLatBits-1);
    maxLatMBR = Math.min(1L + Integer.MAX_VALUE, maxLatBits+1);
    minLonMBR = Math.max(0, minLonBits-1);
    maxLonMBR = Math.min(1L + Integer.MAX_VALUE, maxLonBits+1);

    this.maxShift = computeMaxShift();
    this.termEncoding = termEncoding;
    this.cellComparator = newCellComparator();

    this.rewriteMethod = GEO_CONSTANT_SCORE_REWRITE;
  }

  static final RewriteMethod GEO_CONSTANT_SCORE_REWRITE = new RewriteMethod() {
    @Override
    public Query rewrite(IndexReader reader, MultiTermQuery query) {
      return new GeoPointTermQueryConstantScoreWrapper<>((GeoPointMultiTermQuery)query);
    }
  };

  @Override @SuppressWarnings("unchecked")
  protected TermsEnum getTermsEnum(final Terms terms, AttributeSource atts) throws IOException {
    return GeoPointTermsEnum.newInstance(terms.iterator(), this);
  }

  /**
   * Computes the maximum shift based on the diagonal distance of the bounding box
   */
  protected short computeMaxShift() {
    // in this case a factor of 4 brings the detail level to ~0.001/0.002 degrees lat/lon respectively (or ~111m/222m)
    final short shiftFactor;

    // compute diagonal distance
    double midLon = (minLon + maxLon) * 0.5;
    double midLat = (minLat + maxLat) * 0.5;

    if (SloppyMath.haversinMeters(minLat, minLon, midLat, midLon) > 1000000) {
      shiftFactor = 5;
    } else {
      shiftFactor = 4;
    }

    return (short)(GeoPointField.PRECISION_STEP * shiftFactor);
  }

  /**
   * Abstract method to construct the class that handles all geo point relations
   * (e.g., GeoPointInPolygon)
   */
  abstract protected CellComparator newCellComparator();

  /**
   * Base class for all geo point relation comparators
   */
  static abstract class CellComparator {
    protected final GeoPointMultiTermQuery geoPointQuery;

    CellComparator(GeoPointMultiTermQuery query) {
      this.geoPointQuery = query;
    }

    final boolean cellIntersectsMBR(long minHash, long maxHash) {
      long minLon = BitUtil.deinterleave(minHash);
      long maxLon = BitUtil.deinterleave(maxHash);
      // outside of bounding box
      if (maxLon < geoPointQuery.minLonMBR || minLon > geoPointQuery.maxLonMBR) {
        return false;
      }

      long minLat = BitUtil.deinterleave(minHash >>> 1);
      long maxLat = BitUtil.deinterleave(maxHash >>> 1);
      // outside of bounding box
      if (maxLat < geoPointQuery.minLatMBR || minLat > geoPointQuery.maxLatMBR) {
        return false;
      }
      return true;
    }
    
    /** 
     * Called for non-leaf cells to test how the cell relates to the query, to
     * determine how to further recurse down the tree. 
     * <p>
     * The default implementation decodes the hashes to doubles and calls {@link #compare(double, double, double, double)}.
     */
    protected Relation compare(long minHash, long maxHash) {
      double minLon = GeoEncodingUtils.mortonUnhashLon(minHash);
      double minLat = GeoEncodingUtils.mortonUnhashLat(minHash);
      double maxLon = GeoEncodingUtils.mortonUnhashLon(maxHash);
      double maxLat = GeoEncodingUtils.mortonUnhashLat(maxHash);
      return compare(minLat, maxLat, minLon, maxLon);
    }
    
    /** 
     * Called for non-leaf cells to test how the cell relates to the query, to
     * determine how to further recurse down the tree. 
     */
    protected abstract Relation compare(double minLat, double maxLat, double minLon, double maxLon);
    
    /**
     * Called for leaf cells to test if the point is in the query
     * <p>
     * The default implementation decodes the hash to doubles and calls {@link #postFilter(double, double)}.
     */
    protected boolean postFilter(long hash) {
      return postFilter(GeoEncodingUtils.mortonUnhashLat(hash), GeoEncodingUtils.mortonUnhashLon(hash));
    }

    /**
     * Called for leaf cells to test if the point is in the query
     */
    protected abstract boolean postFilter(final double lat, final double lon);
  }
}
