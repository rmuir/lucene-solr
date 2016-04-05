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

import java.util.Objects;

import org.apache.lucene.search.MultiTermQuery;
import org.apache.lucene.spatial.geopoint.document.GeoPointField.TermEncoding;
import org.apache.lucene.util.BitUtil;
import org.apache.lucene.geo.Polygon;
import org.apache.lucene.index.PointValues.Relation;

/** Package private implementation for the public facing GeoPointInPolygonQuery delegate class.
 *
 *    @lucene.experimental
 */
final class GeoPointInPolygonQueryImpl extends GeoPointInBBoxQueryImpl {
  private final GeoPointInPolygonQuery polygonQuery;
  private final Polygon[] polygons;
  private final GeoPointGrid grid;

  GeoPointInPolygonQueryImpl(final String field, final TermEncoding termEncoding, final GeoPointInPolygonQuery q,
                             final double minLat, final double maxLat, final double minLon, final double maxLon) {
    super(field, termEncoding, minLat, maxLat, minLon, maxLon);
    this.polygonQuery = Objects.requireNonNull(q);
    this.polygons = Objects.requireNonNull(q.polygons);
    this.grid = new GeoPointGrid(minLatMBR, maxLatMBR, minLonMBR, maxLonMBR, polygons);
  }

  @Override
  public void setRewriteMethod(MultiTermQuery.RewriteMethod method) {
    throw new UnsupportedOperationException("cannot change rewrite method");
  }

  @Override
  protected CellComparator newCellComparator() {
    return new GeoPolygonCellComparator(this);
  }

  /**
   * Custom {@code org.apache.lucene.spatial.geopoint.search.GeoPointMultiTermQuery.CellComparator} that computes morton hash
   * ranges based on the defined edges of the provided polygon.
   */
  private final class GeoPolygonCellComparator extends CellComparator {
    GeoPolygonCellComparator(GeoPointMultiTermQuery query) {
      super(query);
    }
   
    @Override
    protected Relation compare(long minHash, long maxHash) {
      long minLon = BitUtil.deinterleave(minHash);
      long minLat = BitUtil.deinterleave(minHash >>> 1);
      long maxLon = BitUtil.deinterleave(maxHash);
      long maxLat = BitUtil.deinterleave(maxHash >>> 1);
      return grid.relate(minLat, maxLat, minLon, maxLon);
    }

    @Override
    protected boolean postFilter(long value) {
      return grid.contains(BitUtil.deinterleave(value >>> 1), BitUtil.deinterleave(value));
    }
  }

  @Override
  public int hashCode() {
    final int prime = 31;
    int result = super.hashCode();
    result = prime * result + polygonQuery.hashCode();
    return result;
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj) return true;
    if (!super.equals(obj)) return false;
    if (getClass() != obj.getClass()) return false;
    GeoPointInPolygonQueryImpl other = (GeoPointInPolygonQueryImpl) obj;
    if (!polygonQuery.equals(other.polygonQuery)) return false;
    return true;
  }
}
