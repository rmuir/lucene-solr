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

import org.apache.lucene.geo.Rectangle;
import org.apache.lucene.index.PointValues.Relation;
import org.apache.lucene.search.MultiTermQuery;
import org.apache.lucene.spatial.geopoint.document.GeoPointField.TermEncoding;
import org.apache.lucene.spatial.util.GeoEncodingUtils;
import org.apache.lucene.util.BitUtil;
import org.apache.lucene.util.SloppyMath;

/** Package private implementation for the public facing GeoPointDistanceQuery delegate class.
 *
 *    @lucene.experimental
 */
final class GeoPointDistanceQueryImpl extends GeoPointInBBoxQueryImpl {
  private final GeoPointDistanceQuery distanceQuery;
  private final double centerLon;
  
  // optimization, maximum partial haversin needed to be a candidate
  private final double maxPartialDistance;
  
  // optimization, used for detecting axis cross
  final double axisLat;
  
  GeoPointDistanceQueryImpl(final String field, final TermEncoding termEncoding, final GeoPointDistanceQuery q,
                            final double centerLonUnwrapped, final Rectangle bbox) {
    super(field, termEncoding, bbox.minLat, bbox.maxLat, bbox.minLon, bbox.maxLon);
    distanceQuery = q;
    centerLon = centerLonUnwrapped;

    // unless our box is crazy, we can use this bound
    // to reject edge cases faster in postFilter()
    if (bbox.maxLon - centerLon < 90 && centerLon - bbox.minLon < 90) {
      maxPartialDistance = Math.max(SloppyMath.haversinSortKey(distanceQuery.centerLat, centerLon, distanceQuery.centerLat, bbox.maxLon),
                                    SloppyMath.haversinSortKey(distanceQuery.centerLat, centerLon, bbox.maxLat, centerLon));
    } else {
      maxPartialDistance = Double.POSITIVE_INFINITY;
    }
    axisLat = Rectangle.axisLat(distanceQuery.centerLat, distanceQuery.radiusMeters);
  }

  @Override
  public void setRewriteMethod(MultiTermQuery.RewriteMethod method) {
    throw new UnsupportedOperationException("cannot change rewrite method");
  }

  @Override
  protected CellComparator newCellComparator() {
    return new GeoPointRadiusCellComparator(this);
  }

  private final class GeoPointRadiusCellComparator extends CellComparator {
    GeoPointRadiusCellComparator(GeoPointDistanceQueryImpl query) {
      super(query);
    }

    @Override
    protected Relation compare(long minHash, long maxHash) {
      long minLonBits = BitUtil.deinterleave(minHash);
      long maxLonBits = BitUtil.deinterleave(maxHash);
      // outside of bounding box
      if (maxLonBits < geoPointQuery.minLonMBR || minLonBits > geoPointQuery.maxLonMBR) {
        return Relation.CELL_OUTSIDE_QUERY;
      }

      long minLatBits = BitUtil.deinterleave(minHash >>> 1);
      long maxLatBits = BitUtil.deinterleave(maxHash >>> 1);
      // outside of bounding box
      if (maxLatBits < geoPointQuery.minLatMBR || minLatBits > geoPointQuery.maxLatMBR) {
        return Relation.CELL_OUTSIDE_QUERY;
      }
      
      // decode and detect INSIDE or OUTSIDE
      double minLon = GeoEncodingUtils.unscaleLon(minLonBits);
      double minLat = GeoEncodingUtils.unscaleLat(minLatBits);
      double maxLon = GeoEncodingUtils.unscaleLon(maxLonBits);
      double maxLat = GeoEncodingUtils.unscaleLat(maxLatBits);
      
      if (maxLon - centerLon < 90 && centerLon - minLon < 90 &&
          SloppyMath.haversinMeters(distanceQuery.centerLat, centerLon, minLat, minLon) <= distanceQuery.radiusMeters &&
          SloppyMath.haversinMeters(distanceQuery.centerLat, centerLon, minLat, maxLon) <= distanceQuery.radiusMeters &&
          SloppyMath.haversinMeters(distanceQuery.centerLat, centerLon, maxLat, minLon) <= distanceQuery.radiusMeters &&
          SloppyMath.haversinMeters(distanceQuery.centerLat, centerLon, maxLat, maxLon) <= distanceQuery.radiusMeters) {
        // we are fully enclosed, collect everything within this subtree
        return Relation.CELL_INSIDE_QUERY;
      } else if ((centerLon < minLon || centerLon > maxLon) && (axisLat+ Rectangle.AXISLAT_ERROR < minLat || axisLat- Rectangle.AXISLAT_ERROR > maxLat)) {
        if (SloppyMath.haversinMeters(distanceQuery.centerLat, centerLon, minLat, minLon) > distanceQuery.radiusMeters &&
            SloppyMath.haversinMeters(distanceQuery.centerLat, centerLon, minLat, maxLon) > distanceQuery.radiusMeters &&
            SloppyMath.haversinMeters(distanceQuery.centerLat, centerLon, maxLat, minLon) > distanceQuery.radiusMeters &&
            SloppyMath.haversinMeters(distanceQuery.centerLat, centerLon, maxLat, maxLon) > distanceQuery.radiusMeters) {
          return Relation.CELL_OUTSIDE_QUERY;
        }
      }
      
      return Relation.CELL_CROSSES_QUERY;
    }

    /**
     * The two-phase query approach. The parent {@link GeoPointTermsEnum} class matches
     * encoded terms that fall within the minimum bounding box of the point-radius circle. Those documents that pass
     * the initial bounding box filter are then post filter compared to the provided distance using the
     * {@link org.apache.lucene.util.SloppyMath#haversinMeters(double, double, double, double)} method.
     */
    @Override
    protected boolean postFilter(long value) {
      long lonBits = BitUtil.deinterleave(value);
      long latBits = BitUtil.deinterleave(value >>> 1);
      if (lonBits < minLonMBR || lonBits > maxLonMBR || latBits < minLatMBR || latBits > maxLatMBR) {
        return false;
      }
      
      double lat = GeoEncodingUtils.unscaleLat(latBits);
      double lon = GeoEncodingUtils.unscaleLon(lonBits);

      // first check the partial distance, if its more than that, it can't be <= radiusMeters
      double h1 = SloppyMath.haversinSortKey(distanceQuery.centerLat, centerLon, lat, lon);
      if (h1 > maxPartialDistance) {
        return false;
      }

      // fully confirm with part 2:
      return SloppyMath.haversinMeters(h1) <= distanceQuery.radiusMeters;
    }
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof GeoPointDistanceQueryImpl)) return false;
    if (!super.equals(o)) return false;

    GeoPointDistanceQueryImpl that = (GeoPointDistanceQueryImpl) o;

    if (!distanceQuery.equals(that.distanceQuery)) return false;

    return true;
  }

  @Override
  public int hashCode() {
    int result = super.hashCode();
    result = 31 * result + distanceQuery.hashCode();
    return result;
  }

  public double getRadiusMeters() {
    return distanceQuery.getRadiusMeters();
  }
}
