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
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import org.apache.lucene.geo.Polygon;
import org.apache.lucene.index.PointValues.Relation;

// Both Polygon.contains() and Polygon.crossesSlowly() loop all edges, and first check that the edge is within a range.
// we just organize the edges to do the same computations on the same subset of edges more efficiently. 
//
// See "reliability and numerical stability" of www-ma2.upc.es/geoc/Schirra-pointPolygon.pdf. note that
// we continue to only remain consistent with ourselves. 
// TODO: clean this up, call it Polygon2D, and remove all the 2D methods from Polygon?
final class LatLonTree {
  private final LatLonTree[] holes;

  /** minimum latitude of this polygon's bounding box area */
  final double minLat;
  /** maximum latitude of this polygon's bounding box area */
  final double maxLat;
  /** minimum longitude of this polygon's bounding box area */
  final double minLon;
  /** maximum longitude of this polygon's bounding box area */
  final double maxLon;
  
  Edge root;

  LatLonTree(Polygon polygon, LatLonTree... holes) {
    assert polygon != null;
    assert holes != null;
 
    this.holes = holes.clone();
    this.minLat = polygon.minLat;
    this.maxLat = polygon.maxLat;
    this.minLon = polygon.minLon;
    this.maxLon = polygon.maxLon;
    
    // create interval tree of edges
    double polyLats[] = polygon.getPolyLats();
    double polyLons[] = polygon.getPolyLons();
    // TODO: make a real balanced tree instead :)
    List<Integer> list = new ArrayList<Integer>(polyLats.length - 1);
    for (int i = 1; i < polyLats.length; i++) {
      list.add(i);
    }
    Collections.shuffle(list, new Random(Arrays.hashCode(polyLats) ^ Arrays.hashCode(polyLons)));
    for (int i : list) {
      double lat1 = polyLats[i-1];
      double lon1 = polyLons[i-1];
      double lat2 = polyLats[i];
      double lon2 = polyLons[i];
      addEdge(lat1, lon1, lat2, lon2);
    }
  }

  /** 
   * Returns true if the point is contained within this polygon.
   * <p>
   * See <a href="https://www.ecse.rpi.edu/~wrf/Research/Short_Notes/pnpoly.html">
   * https://www.ecse.rpi.edu/~wrf/Research/Short_Notes/pnpoly.html</a> for more information.
   */
  boolean contains(double latitude, double longitude) {
    // check bounding box
    if (latitude < minLat || latitude > maxLat || longitude < minLon || longitude > maxLon) {
      return false;
    }
    
    if (contains(root, latitude, longitude)) {
      for (LatLonTree hole : holes) {
        if (hole.contains(latitude, longitude)) {
          return false;
        }
      }
      return true;
    }
    
    return false;
  }
  
  /** Returns relation to the provided rectangle */
  Relation relate(double minLat, double maxLat, double minLon, double maxLon) {
    // if the bounding boxes are disjoint then the shape does not cross
    if (maxLon < this.minLon || minLon > this.maxLon || maxLat < this.minLat || minLat > this.maxLat) {
      return Relation.CELL_OUTSIDE_QUERY;
    }
    // if the rectangle fully encloses us, we cross.
    if (minLat <= this.minLat && maxLat >= this.maxLat && minLon <= this.minLon && maxLon >= this.maxLon) {
      return Relation.CELL_CROSSES_QUERY;
    }
    // check any holes
    for (LatLonTree hole : holes) {
      Relation holeRelation = hole.relate(minLat, maxLat, minLon, maxLon);
      if (holeRelation == Relation.CELL_CROSSES_QUERY) {
        return Relation.CELL_CROSSES_QUERY;
      } else if (holeRelation == Relation.CELL_INSIDE_QUERY) {
        return Relation.CELL_OUTSIDE_QUERY;
      }
    }
    // check each corner: if < 4 are present, its cheaper than crossesSlowly
    int numCorners = numberOfCorners(minLat, maxLat, minLon, maxLon);
    if (numCorners == 4) {
      if (crosses(root, minLat, maxLat, minLon, maxLon)) {
        return Relation.CELL_CROSSES_QUERY;
      }
      return Relation.CELL_INSIDE_QUERY;
    } else if (numCorners > 0) {
      return Relation.CELL_CROSSES_QUERY;
    }
    
    // we cross
    if (crosses(root, minLat, maxLat, minLon, maxLon)) {
      return Relation.CELL_CROSSES_QUERY;
    }
    
    return Relation.CELL_OUTSIDE_QUERY;
  }
  
  // returns 0, 4, or something in between
  private int numberOfCorners(double minLat, double maxLat, double minLon, double maxLon) {
    int containsCount = 0;
    if (contains(minLat, minLon)) {
      containsCount++;
    }
    if (contains(minLat, maxLon)) {
      containsCount++;
    }
    if (containsCount == 1) {
      return containsCount;
    }
    if (contains(maxLat, maxLon)) {
      containsCount++;
    }
    if (containsCount == 2) {
      return containsCount;
    }
    if (contains(maxLat, minLon)) {
      containsCount++;
    }
    return containsCount;
  }

  /** Helper for multipolygon logic: returns true if any of the supplied polygons contain the point */
  static boolean contains(LatLonTree[] polygons, double latitude, double longitude) {
    for (LatLonTree polygon : polygons) {
      if (polygon.contains(latitude, longitude)) {
        return true;
      }
    }
    return false;
  }

  /** Returns the multipolygon relation for the rectangle */
  static Relation relate(LatLonTree[] polygons, double minLat, double maxLat, double minLon, double maxLon) {
    for (LatLonTree polygon : polygons) {
      Relation relation = polygon.relate(minLat, maxLat, minLon, maxLon);
      if (relation != Relation.CELL_OUTSIDE_QUERY) {
        // note: we optimize for non-overlapping multipolygons. so if we cross one,
        // we won't keep iterating to try to find a contains.
        return relation;
      }
    }
    return Relation.CELL_OUTSIDE_QUERY;
  }
  
  static LatLonTree[] build(Polygon... polygons) {
    LatLonTree trees[] = new LatLonTree[polygons.length];
    for (int i = 0; i < trees.length; i++) {
      Polygon gon = polygons[i];
      Polygon gonHoles[] = gon.getHoles();
      LatLonTree holes[] = new LatLonTree[gonHoles.length];
      for (int j = 0; j < holes.length; j++) {
        holes[j] = new LatLonTree(gonHoles[j]);
      }
      trees[i] = new LatLonTree(gon, holes);
    }
    return trees;
  }
  
  /** 
   * Internal tree node: represents polygon edge from lat1,lon1 to lat2,lon2.
   * The sort value is {@code low}, which is the minimum latitude of the edge.
   * {@code max} stores the maximum latitude of this edge or any children.
   */
  static class Edge {
    final double lat1, lat2;
    final double lon1, lon2;
    final double low;
    double max;
    
    Edge left;
    Edge right;

    Edge(double lat1, double lon1, double lat2, double lon2, double low, double max) {
      this.lat1 = lat1;
      this.lon1 = lon1;
      this.lat2 = lat2;
      this.lon2 = lon2;
      this.low = low;
      this.max = max;
    }
  }

  /** adds a new edge */
  private void addEdge(double lat1, double lon1, double lat2, double lon2) {
    addEdge(lat1, lon1, lat2, lon2, Math.min(lat1, lat2), Math.max(lat1, lat2));
  }

  /** Adds a new edge, updating internal values of {@code max} for any parents along the way. */
  private Edge addEdge(double lat1, double lon1, double lat2, double lon2, double minLatitude, double maxLatitude) {
    Edge node = root;
    while (node != null) {
      node.max = Math.max(node.max, maxLatitude);
      if (minLatitude < node.low) {
        if (node.left == null) {
          return node.left = new Edge(lat1, lon1, lat2, lon2, minLatitude, maxLatitude);
        }
        node = node.left;
      } else {
        if (node.right == null) {
          return node.right = new Edge(lat1, lon1, lat2, lon2, minLatitude, maxLatitude);
        }
        node = node.right;
      }
    }
    return root = new Edge(lat1, lon1, lat2, lon2, minLatitude, maxLatitude);
  }

  /** 
   * Returns true if the point is contained within this polygon.
   * <p>
   * See <a href="https://www.ecse.rpi.edu/~wrf/Research/Short_Notes/pnpoly.html">
   * https://www.ecse.rpi.edu/~wrf/Research/Short_Notes/pnpoly.html</a> for more information.
   */
  // ported to java from https://www.ecse.rpi.edu/~wrf/Research/Short_Notes/pnpoly.html
  // original code under the BSD license (https://www.ecse.rpi.edu/~wrf/Research/Short_Notes/pnpoly.html#License%20to%20Use)
  //
  // Copyright (c) 1970-2003, Wm. Randolph Franklin
  //
  // Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated 
  // documentation files (the "Software"), to deal in the Software without restriction, including without limitation 
  // the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and 
  // to permit persons to whom the Software is furnished to do so, subject to the following conditions:
  //
  // 1. Redistributions of source code must retain the above copyright 
  //    notice, this list of conditions and the following disclaimers.
  // 2. Redistributions in binary form must reproduce the above copyright 
  //    notice in the documentation and/or other materials provided with 
  //    the distribution.
  // 3. The name of W. Randolph Franklin may not be used to endorse or 
  //    promote products derived from this Software without specific 
  //    prior written permission. 
  //
  // THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED 
  // TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL 
  // THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF 
  // CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS 
  // IN THE SOFTWARE. 
  private boolean contains(Edge n, double latitude, double longitude) {
    boolean res = false;
    if (latitude <= n.max) {
      if (n.lat1 > latitude != n.lat2 > latitude) {
        if (longitude < (n.lon1 - n.lon2) * (latitude - n.lat2) / (n.lat1 - n.lat2) + n.lon2) {
          res = !res;
        }
      }
      if (n.left != null) {
        res ^= contains(n.left, latitude, longitude);
      }
      if (n.right != null && latitude >= n.low) {
        res ^= contains(n.right, latitude, longitude);
      }
    }
    return res;
  }

  /** Returns true if the box crosses our polygon */
  private boolean crosses(Edge n, double minLat, double maxLat, double minLon, double maxLon) {
    if (minLat <= n.max) {
      // we compute line intersections of every polygon edge with every box line.
      // if we find one, return true.
      // for each box line (AB):
      //   for each poly line (CD):
      //     intersects = orient(C,D,A) * orient(C,D,B) <= 0 && orient(A,B,C) * orient(A,B,D) <= 0
      double cy = n.lat1;
      double dy = n.lat2;
      double cx = n.lon1;
      double dx = n.lon2;
      
      // optimization: see if the rectangle is outside of the "bounding box" of the polyline at all
      // if not, don't waste our time trying more complicated stuff
      boolean outside = (cy < minLat && dy < minLat) ||
                        (cy > maxLat && dy > maxLat) ||
                        (cx < minLon && dx < minLon) ||
                        (cx > maxLon && dx > maxLon);
      if (outside == false) {
        // does box's top edge intersect polyline?
        // ax = minLon, bx = maxLon, ay = maxLat, by = maxLat
        if (orient(cx, cy, dx, dy, minLon, maxLat) * orient(cx, cy, dx, dy, maxLon, maxLat) <= 0 &&
            orient(minLon, maxLat, maxLon, maxLat, cx, cy) * orient(minLon, maxLat, maxLon, maxLat, dx, dy) <= 0) {
          return true;
        }

        // does box's right edge intersect polyline?
        // ax = maxLon, bx = maxLon, ay = maxLat, by = minLat
        if (orient(cx, cy, dx, dy, maxLon, maxLat) * orient(cx, cy, dx, dy, maxLon, minLat) <= 0 &&
            orient(maxLon, maxLat, maxLon, minLat, cx, cy) * orient(maxLon, maxLat, maxLon, minLat, dx, dy) <= 0) {
          return true;
        }

        // does box's bottom edge intersect polyline?
        // ax = maxLon, bx = minLon, ay = minLat, by = minLat
        if (orient(cx, cy, dx, dy, maxLon, minLat) * orient(cx, cy, dx, dy, minLon, minLat) <= 0 &&
            orient(maxLon, minLat, minLon, minLat, cx, cy) * orient(maxLon, minLat, minLon, minLat, dx, dy) <= 0) {
          return true;
        }

        // does box's left edge intersect polyline?
        // ax = minLon, bx = minLon, ay = minLat, by = maxLat
        if (orient(cx, cy, dx, dy, minLon, minLat) * orient(cx, cy, dx, dy, minLon, maxLat) <= 0 &&
            orient(minLon, minLat, minLon, maxLat, cx, cy) * orient(minLon, minLat, minLon, maxLat, dx, dy) <= 0) {
          return true;
        }
      }
      
      if (n.left != null) {
        if (crosses(n.left, minLat, maxLat, minLon, maxLon)) {
          return true;
        }
      }
      
      if (n.right != null && maxLat >= n.low) {
        if (crosses(n.right, minLat, maxLat, minLon, maxLon)) {
          return true;
        }
      }
    }
    return false;
  }

  /**
   * Returns a positive value if points a, b, and c are arranged in counter-clockwise order,
   * negative value if clockwise, zero if collinear.
   */
  // see the "Orient2D" method described here:
  // http://www.cs.berkeley.edu/~jrs/meshpapers/robnotes.pdf
  // https://www.cs.cmu.edu/~quake/robust.html
  // Note that this one does not yet have the floating point tricks to be exact!
  private static int orient(double ax, double ay, double bx, double by, double cx, double cy) {
    double v1 = (bx - ax) * (cy - ay);
    double v2 = (cx - ax) * (by - ay);
    if (v1 > v2) {
      return 1;
    } else if (v1 < v2) {
      return -1;
    } else {
      return 0;
    }
  }
}
