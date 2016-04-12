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

import java.util.ArrayList;
import java.util.List;

//import org.apache.lucene.geo.EarthDebugger;
import org.apache.lucene.geo.Polygon;
import org.apache.lucene.index.PointValues.Relation;
import org.apache.lucene.util.FixedBitSet;

/**
 * This is a temporary hack, until some polygon methods have better performance!
 * <p>
 * When this file is removed then we have made good progress! In general we don't call
 * the point-in-polygon algorithm that much, because of how BKD divides up the data. But
 * today the method is very slow (general to all polygons, linear with the number of vertices).
 * At the same time polygon-rectangle relation operations are also slow in the same way, this
 * just really ensures they are the bottleneck by removing most of the point-in-polygon calls.
 * <p>
 * See the "grid" algorithm description here: http://erich.realtimerendering.com/ptinpoly/
 * A few differences:
 * <ul>
 *   <li> We work in an integer encoding, so edge cases are simpler.
 *   <li> We classify each grid cell as "contained", "not contained", or "don't know".
 *   <li> We form a grid over a potentially complex multipolygon with holes.
 *   <li> Construction is less efficient because we do not do anything "smart" such
 *        as following polygon edges. 
 *   <li> Instead we construct a baby tree to reduce the number of relation operations,
 *        which are currently expensive.
 * </ul>
 */
// TODO: just make a more proper tree (maybe in-ram BKD)? then we can answer most 
// relational operations as rectangle <-> rectangle relations in integer space in log(n) time..
final class LatLonGrid {
  private static final boolean DEBUG = false;
  // must be a power of two!
  private static final int GRID_SIZE = 1<<5;
  // bounding box of polygons
  private final int minLat;
  private final int maxLat;
  private final int minLon;
  private final int maxLon;
  // TODO: something more efficient than parallel bitsets? maybe one bitset?
  private final FixedBitSet haveAnswer = new FixedBitSet(2 * GRID_SIZE * GRID_SIZE);
  private final FixedBitSet answer = new FixedBitSet(2 * GRID_SIZE * GRID_SIZE);
  private final int LEAF_NODE_ID_START = GRID_SIZE * GRID_SIZE;
  
  private final long latPerCell; // latitude range per grid cell
  private final long lonPerCell; // longitude range per grid cell
  
  private final Polygon[] polygons;

  //public EarthDebugger earth;

  LatLonGrid(int minLat, int maxLat, int minLon, int maxLon, Polygon... polygons) {
    this.minLat = minLat;
    this.maxLat = maxLat;
    this.minLon = minLon;
    this.maxLon = maxLon;
    this.polygons = polygons;
    if (minLon > maxLon) {
      // maybe make 2 grids if you want this? 
      throw new IllegalArgumentException("Grid cannot cross the dateline");
    }
    if (minLat > maxLat) {
      throw new IllegalArgumentException("bogus bounding box");
    }
    long latitudeRange = maxLat - (long) minLat;
    long longitudeRange = maxLon - (long) minLon;
    // we spill over the edge of the bounding box in each direction a bit,
    // but it prevents edge case bugs.
    latPerCell = latitudeRange / (GRID_SIZE - 1);
    lonPerCell = longitudeRange / (GRID_SIZE - 1);
    //earth = new EarthDebugger((LatLonPoint.decodeLatitude(minLat) + LatLonPoint.decodeLatitude(maxLat))/2.0, (LatLonPoint.decodeLongitude(minLon) + LatLonPoint.decodeLongitude(maxLon))/2.0, 400000);
    fill(1, 0, GRID_SIZE, 0, GRID_SIZE);
    //earth.addPolygon(polygons[0]);
  }

  /** fills a 2D range of grid cells [minLatIndex .. maxLatIndex) X [minLonIndex .. maxLonIndex) */
  private void fill(int nodeID, int minLatIndex, int maxLatIndex, int minLonIndex, int maxLonIndex) {
    // Math.min because grid cells at the edge of the bounding box are smaller than normal due to spilling.
    //System.out.println("fill parents=" + parents + " lat=" + minLatIndex + "-" + maxLatIndex + " lon=" + minLonIndex + "-" + maxLonIndex);
    long cellMinLat = minLat + (minLatIndex * latPerCell);
    long cellMaxLat = Math.min(maxLat, minLat + (maxLatIndex * latPerCell) - 1);
    long cellMinLon = minLon + (minLonIndex * lonPerCell);
    long cellMaxLon = Math.min(maxLon, minLon + (maxLonIndex * lonPerCell) - 1);

    assert cellMinLat <= maxLat;
    assert cellMinLon <= maxLon;
    assert cellMaxLat >= cellMinLat;
    assert cellMaxLon >= cellMinLon;

    Relation relation = Polygon.relate(polygons, LatLonPoint.decodeLatitude((int) cellMinLat), 
                                                 LatLonPoint.decodeLatitude((int) cellMaxLat), 
                                                 LatLonPoint.decodeLongitude((int) cellMinLon), 
                                                 LatLonPoint.decodeLongitude((int) cellMaxLon));
    if (relation != Relation.CELL_CROSSES_QUERY) {

      //System.out.println(relation + " for nodeID=" + nodeID + " lat=" + minLatIndex + "-" + maxLatIndex + " lon=" + minLonIndex + "-" + maxLonIndex);
      // all cells under here are either fully contained by or fully outside of the poly, so we "fill" our cells and stop recursing
      if (nodeID < LEAF_NODE_ID_START) {
        haveAnswer.set(nodeID);
        if (relation == Relation.CELL_INSIDE_QUERY) {
          answer.set(nodeID);
        }
      }

      /*
      if (relation == Relation.CELL_INSIDE_QUERY) {
        earth.addRect(LatLonPoint.decodeLatitude((int) cellMinLat), 
                  LatLonPoint.decodeLatitude((int) cellMaxLat), 
                  LatLonPoint.decodeLongitude((int) cellMinLon), 
                  LatLonPoint.decodeLongitude((int) cellMaxLon),
                  "#00ff00");
      } else {
        earth.addRect(LatLonPoint.decodeLatitude((int) cellMinLat), 
                  LatLonPoint.decodeLatitude((int) cellMaxLat), 
                  LatLonPoint.decodeLongitude((int) cellMinLon), 
                  LatLonPoint.decodeLongitude((int) cellMaxLon));
      }
      */

      // we know the answer for this region, fill the cell range
      for (int i = minLatIndex; i < maxLatIndex; i++) {
        for (int j = minLonIndex; j < maxLonIndex; j++) {
          int index = LEAF_NODE_ID_START + i * GRID_SIZE + j;
          //System.out.println("  set " + index);
          assert haveAnswer.get(index) == false;
          haveAnswer.set(index);
          if (relation == Relation.CELL_INSIDE_QUERY) {
            answer.set(index);
          }
        }
      }

    } else if (minLatIndex == maxLatIndex - 1) {
      // nothing more to do: this is a single grid cell (leaf node) and
      // is an edge case for the polygon.
    } else {
      // grid range crosses our polygon, keep recursing.
      int midLatIndex = (minLatIndex + maxLatIndex) >>> 1;
      int midLonIndex = (minLonIndex + maxLonIndex) >>> 1;
      fill(4*nodeID, minLatIndex, midLatIndex, minLonIndex, midLonIndex);
      fill(4*nodeID+1, minLatIndex, midLatIndex, midLonIndex, maxLonIndex);
      fill(4*nodeID+2, midLatIndex, maxLatIndex, minLonIndex, midLonIndex);
      fill(4*nodeID+3, midLatIndex, maxLatIndex, midLonIndex, maxLonIndex);
    }
  }

  // NOTE: returns null if the tree couldn't answer the relation, and then caller must ask the Polygon
  private Relation relate(int nodeID,
                          int queryMinLatIndex, int queryMaxLatIndex, int queryMinLonIndex, int queryMaxLonIndex,
                          int cellMinLatIndex, int cellMaxLatIndex, int cellMinLonIndex, int cellMaxLonIndex) {

    if (DEBUG) System.out.println("    relate recurse nodeID=" + nodeID + " cellLat=" + cellMinLatIndex + "-" + cellMaxLatIndex + " cellLon=" + cellMinLonIndex + "-" + cellMaxLonIndex + " stack=" + stack);
    if (nodeID >= LEAF_NODE_ID_START) {
      assert cellMinLatIndex == cellMaxLatIndex-1;
      assert cellMinLonIndex == cellMaxLonIndex-1;

      // nocommit fix leaf node addressing to be consistent w/ inner?
      int index = LEAF_NODE_ID_START + cellMinLatIndex * GRID_SIZE + cellMinLonIndex;
      if (haveAnswer.get(index)) {
        if (answer.get(index)) {
          if (DEBUG) System.out.println("      leaf return INSIDE");
          return Relation.CELL_INSIDE_QUERY;
        } else {
          if (DEBUG) System.out.println("      leaf return OUTSIDE");
          return Relation.CELL_OUTSIDE_QUERY;
        }
      } else {
        if (DEBUG) System.out.println("      leaf return null");
        // tree doesn't know the answer, and we are down at a leaf, so we must do a full check
        return null;
      }

    } else {
      if (haveAnswer.get(nodeID)) {
        // we can stop recursing because this node knows if we are inside or outside, and even if
        // the query box is a subset of this area, it doesn't change the answer
        if (answer.get(nodeID)) {
          if (DEBUG) System.out.println("      node have answer: INSIDE");
          sawInside = true;
          return Relation.CELL_INSIDE_QUERY;
        } else {
          if (DEBUG) System.out.println("      node have answer: OUTSIDE");
          sawOutside = true;
          return Relation.CELL_OUTSIDE_QUERY;
        }
      } else {

        int cellMidLatIndex = (cellMinLatIndex + cellMaxLatIndex) >>> 1;
        int cellMidLonIndex = (cellMinLonIndex + cellMaxLonIndex) >>> 1;

        // nocommit make sure tests hit all these ifs!!!
        if (queryMinLatIndex < cellMidLatIndex && queryMinLonIndex < cellMidLonIndex) {
          Relation r = relate(4*nodeID,
                              queryMinLatIndex, queryMaxLatIndex, queryMinLonIndex, queryMaxLonIndex,
                              cellMinLatIndex, cellMidLatIndex, cellMinLonIndex, cellMidLonIndex);
          if (r == Relation.CELL_CROSSES_QUERY) {
            return Relation.CELL_CROSSES_QUERY;
          } else if (r == Relation.CELL_INSIDE_QUERY) {
            sawInside = true;
          } else if (r == Relation.CELL_OUTSIDE_QUERY) {
            sawOutside = true;
          } else {
            sawUnknown = true;
          }
        }

        if (sawInside && sawOutside) {
          return Relation.CELL_CROSSES_QUERY;
        }

        if (queryMinLatIndex < cellMidLatIndex && queryMaxLonIndex >= cellMidLonIndex) {
          Relation r = relate(4*nodeID+1,
                              queryMinLatIndex, queryMaxLatIndex, queryMinLonIndex, queryMaxLonIndex,
                              cellMinLatIndex, cellMidLatIndex, cellMidLonIndex, cellMaxLonIndex);
          if (r == Relation.CELL_CROSSES_QUERY) {
            return Relation.CELL_CROSSES_QUERY;
          } else if (r == Relation.CELL_INSIDE_QUERY) {
            sawInside = true;
          } else if (r == Relation.CELL_OUTSIDE_QUERY) {
            sawOutside = true;
          } else {
            sawUnknown = true;
          }
        }

        if (sawInside && sawOutside) {
          return Relation.CELL_CROSSES_QUERY;
        }

        if (queryMaxLatIndex >= cellMidLatIndex && queryMinLonIndex < cellMidLonIndex) {
          Relation r = relate(4*nodeID+2,
                              queryMinLatIndex, queryMaxLatIndex, queryMinLonIndex, queryMaxLonIndex,
                              cellMidLatIndex, cellMaxLatIndex, cellMinLonIndex, cellMidLonIndex);
          if (r == Relation.CELL_CROSSES_QUERY) {
            return Relation.CELL_CROSSES_QUERY;
          } else if (r == Relation.CELL_INSIDE_QUERY) {
            sawInside = true;
          } else if (r == Relation.CELL_OUTSIDE_QUERY) {
            sawOutside = true;
          } else {
            sawUnknown = true;
          }
        }

        if (sawInside && sawOutside) {
          return Relation.CELL_CROSSES_QUERY;
        }

        if (queryMaxLatIndex >= cellMidLatIndex && queryMaxLonIndex >= cellMidLonIndex) {
          Relation r = relate(4*nodeID+3,
                              queryMinLatIndex, queryMaxLatIndex, queryMinLonIndex, queryMaxLonIndex,
                              cellMidLatIndex, cellMaxLatIndex, cellMidLonIndex, cellMaxLonIndex);
          if (r == Relation.CELL_CROSSES_QUERY) {
            return Relation.CELL_CROSSES_QUERY;
          } else if (r == Relation.CELL_INSIDE_QUERY) {
            sawInside = true;
          } else if (r == Relation.CELL_OUTSIDE_QUERY) {
            sawOutside = true;
          } else {
            sawUnknown = true;
          }
        }

        if (sawInside && sawOutside) {
          return Relation.CELL_CROSSES_QUERY;
        } else if (sawUnknown) {
          return null;
        } else if (sawInside) {
          return Relation.CELL_INSIDE_QUERY;
        } else {
          assert sawOutside;
          return Relation.CELL_OUTSIDE_QUERY;
        }
      }
    }
  }
  
  /** Returns true if inside one of our polygons, false otherwise */
  boolean contains(int latitude, int longitude) {
    // first see if the grid knows the answer
    int index = index(latitude, longitude);
    if (index == -1) {
      return false; // outside of bounding box range
    } else if (haveAnswer.get(index)) {
      return answer.get(index);
    }

    // the grid is unsure (boundary): do a real test.
    double docLatitude = LatLonPoint.decodeLatitude(latitude);
    double docLongitude = LatLonPoint.decodeLongitude(longitude);
    return Polygon.contains(polygons, docLatitude, docLongitude);
  }

  /** Returns 0 if contains, 1 if not contains, 2 if unsure */
  /*
  int fastContains(int latitude, int longitude) {
    // first see if the grid knows the answer
    int index = index(latitude, longitude);
    if (index == -1) {
      return 1; // outside of bounding box range
    } else if (haveAnswer.get(index)) {
      if (answer.get(index)) {
        return 0;
      } else {
        return 1;
      }
    }

    return 2;
  }
  */

  //private int relateCount;
  //private int relateSlowCount;

  private boolean sawOutside;
  private boolean sawInside;
  private boolean sawUnknown;
  
  /** Returns relation to the provided rectangle */
  Relation relate(int minLat, int maxLat, int minLon, int maxLon) {
    //relateCount++;
    // if the bounding boxes are disjoint then the shape does not cross
    if (maxLon < this.minLon || minLon > this.maxLon || maxLat < this.minLat || minLat > this.maxLat) {
      return Relation.CELL_OUTSIDE_QUERY;
    }
    // if the rectangle fully encloses us, we cross.
    if (minLat <= this.minLat && maxLat >= this.maxLat && minLon <= this.minLon && maxLon >= this.maxLon) {
      return Relation.CELL_CROSSES_QUERY;
    }

    sawInside = false;
    sawOutside = false;
    sawUnknown = false;

    if (minLat >= this.minLat && maxLat <= this.maxLat && minLon >= this.minLon && maxLon <= this.maxLon) {
      if (DEBUG) System.out.println("  top relate: within query lat=" + latCell(minLat) + "-" + latCell(maxLat) + " lon=" + lonCell(minLon) + "-" + lonCell(maxLon));
      // query box is fully within our box, so the tree is authoritative if it has an answer:
      Relation r = relate(1,
                          latCell(minLat),
                          latCell(maxLat),
                          lonCell(minLon),
                          lonCell(maxLon),
                          0, GRID_SIZE, 0, GRID_SIZE);
      if (DEBUG) System.out.println("    top relate: got " + r);
      if (r != null) {
        // nocommit make sure tests hit this
        //System.out.println("tree1 says " + r);
        return r;
      }
    } else {
      if (DEBUG) System.out.println("  top relate: crosses query lat=" + latCell(Math.max(minLat, this.minLat)) + "-" + latCell(Math.min(maxLat, this.maxLat)) + " lon=" + lonCell(Math.max(minLon, this.minLon)) + "-" + lonCell(Math.min(maxLon, this.maxLon)));
      sawOutside = true;
      Relation r = relate(1,
                          latCell(Math.max(minLat, this.minLat)),
                          latCell(Math.min(maxLat, this.maxLat)),
                          lonCell(Math.max(minLon, this.minLon)),
                          lonCell(Math.min(maxLon, this.maxLon)),
                          0, GRID_SIZE, 0, GRID_SIZE);
      if (DEBUG) System.out.println("    top relate: got " + r);
      if (r == Relation.CELL_INSIDE_QUERY) {
        return Relation.CELL_CROSSES_QUERY;
      } else if (r != null) {
        // nocommit make sure tests hit this
        //System.out.println("tree2 says " + r);
        return r;
      }
    }

    /*
    relateSlowCount++;
    System.out.println("relate count " + relateCount + " (" + relateSlowCount + " slow)");

    if (true || stackDepth == 16) {
      System.out.println("  add blue lat=" + LatLonPoint.decodeLatitude(minLat) + " TO " +
                         LatLonPoint.decodeLatitude(maxLat) + " lon=" + 
                         LatLonPoint.decodeLongitude(minLon) + " TO " + LatLonPoint.decodeLongitude(maxLon));
      earth.addRect(LatLonPoint.decodeLatitude(minLat), 
                LatLonPoint.decodeLatitude(maxLat), 
                LatLonPoint.decodeLongitude(minLon), 
                LatLonPoint.decodeLongitude(maxLon),
                "#0000ff");
    }
    */

    // do it the hard way!
    return Polygon.relate(polygons, LatLonPoint.decodeLatitude(minLat), 
                                    LatLonPoint.decodeLatitude(maxLat), 
                                    LatLonPoint.decodeLongitude(minLon), 
                                    LatLonPoint.decodeLongitude(maxLon));
  }
  
  private int latCell(int latitude) {
    assert latitude >= minLat;
    assert latitude <= maxLat;
    long latRel = latitude - (long) minLat;
    return (int) (latRel / latPerCell);
  }

  private int lonCell(int longitude) {
    assert longitude >= minLon;
    assert longitude <= maxLon;
    long lonRel = longitude - (long) minLon;
    return (int) (lonRel / lonPerCell);
  }

  /** Returns grid index of lat/lon, or -1 if the value is outside of the bounding box. */
  private int index(int latitude, int longitude) {
    if (latitude < minLat || latitude > maxLat || longitude < minLon || longitude > maxLon) {
      return -1; // outside of bounding box range
    }
    
    long latRel = latitude - (long) minLat;
    long lonRel = longitude - (long) minLon;
    
    int latIndex = (int) (latRel / latPerCell);
    int lonIndex = (int) (lonRel / lonPerCell);
    return LEAF_NODE_ID_START + latIndex * GRID_SIZE + lonIndex;
  }
}
